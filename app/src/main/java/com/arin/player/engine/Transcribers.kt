package com.arin.player.engine

import android.util.Base64
import com.arin.player.data.CloudProvider
import com.arin.player.data.LocalModel
import com.arin.player.data.Word
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * One subtitle line, times relative to the start of the piece. [startMs]/[endMs] are exactly when
 * the line is spoken. [words] carries per-word timing when the text is the spoken words themselves.
 */
data class Line(val startMs: Long, val endMs: Long, val text: String, val words: List<Word> = emptyList())

/** [lang] = detected spoken language as an upper-case code ("JA", "HI"…). */
data class ChunkResult(val lang: String, val lines: List<Line>)

/**
 * Speech recogniser. Step 1 of 2: [transcribe] writes down what is said, in the language spoken,
 * with a time for every word. Step 2 (translation to English) is done separately by [LineTranslators].
 */
interface Transcriber {
    /** Stable id, used to pause a provider whose free limit has run out. */
    val id: String
    val label: String
    suspend fun transcribe(pcm: FloatArray): ChunkResult
    /** Last resort when no text translator can handle the language: audio -> English in one go. */
    suspend fun audioToEnglish(pcm: FloatArray): ChunkResult?
}

class CloudException(val code: Int, msg: String) : RuntimeException(msg)

val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .callTimeout(180, TimeUnit.SECONDS)
    .build()

/** POST with retries on short rate limits / server errors. */
suspend fun postWithRetry(url: String, auth: String, body: RequestBody): String = withContext(Dispatchers.IO) {
    var attempt = 0
    while (true) {
        val req = Request.Builder().url(url).header("Authorization", "Bearer $auth").post(body).build()
        var retryAfterMs = -1L
        http.newCall(req).execute().use { resp ->
            val txt = resp.body?.string().orEmpty()
            when {
                resp.isSuccessful -> return@withContext txt
                resp.code == 401 || resp.code == 403 -> throw CloudException(resp.code, "API key rejected — check it in Settings")
                (resp.code == 429 || resp.code >= 500) && attempt < 3 -> {
                    val ra = resp.header("retry-after")?.toDoubleOrNull()?.times(1000)?.toLong() ?: (2_000L shl attempt)
                    // A long wait means the hourly/daily free limit is used up: let the caller switch provider.
                    if (resp.code == 429 && ra > 60_000) throw CloudException(429, "Free limit reached")
                    retryAfterMs = ra
                }
                resp.code == 429 -> throw CloudException(429, "Free limit reached")
                else -> throw CloudException(resp.code, "Cloud error ${resp.code}: ${errorMessage(txt)}")
            }
        }
        attempt++
        delay(retryAfterMs.coerceIn(1_000, 60_000))
    }
    @Suppress("UNREACHABLE_CODE") error("unreachable")
}

private fun errorMessage(txt: String): String = runCatching {
    val o = JSONObject(txt)
    o.optJSONObject("error")?.optString("message")
        ?: o.optJSONArray("errors")?.optJSONObject(0)?.optString("message")
        ?: txt.take(120)
}.getOrDefault(txt.take(120))

private fun isSilentSeg(s: JSONObject) =
    s.optDouble("no_speech_prob", 0.0) > 0.6 && s.optDouble("avg_logprob", 0.0) < -1.0

// ---------------------------------------------------------------------------------------------
// Groq / OpenAI
// ---------------------------------------------------------------------------------------------

class CloudTranscriber(private val provider: CloudProvider, private val apiKey: String) : Transcriber {
    override val id = "cloud-${provider.name}"
    override val label = provider.label.substringBefore(" (")

    override suspend fun transcribe(pcm: FloatArray): ChunkResult {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", provider.model)
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("timestamp_granularities[]", "word")
            .addFormDataPart("timestamp_granularities[]", "segment")
            .addFormDataPart("temperature", "0")
            .addFormDataPart("file", "chunk.wav", Wav.encode(pcm).toRequestBody("audio/wav".toMediaType()))
            .build()
        val o = JSONObject(postWithRetry(provider.transcribeUrl, apiKey, body))
        val lang = langCode(o.optString("language"))

        // Segments Whisper itself flags as silence/noise ("Thank you." hallucinations): drop their words.
        val bad = mutableListOf<LongRange>()
        val segs = mutableListOf<Line>()
        o.optJSONArray("segments")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val st = (s.getDouble("start") * 1000).toLong()
                val en = (s.getDouble("end") * 1000).toLong()
                if (isSilentSeg(s)) bad += st..en
                else s.optString("text").trim().takeIf { it.isNotBlank() }?.let { segs += Line(st, en, it) }
            }
        }
        val words = mutableListOf<Word>()
        o.optJSONArray("words")?.let { arr ->
            for (i in 0 until arr.length()) {
                val w = arr.getJSONObject(i)
                val st = (w.getDouble("start") * 1000).toLong()
                val en = (w.getDouble("end") * 1000).toLong()
                val text = w.optString("word").trim()
                if (text.isNotEmpty() && bad.none { st in it }) words += Word(st, en, text)
            }
        }
        return ChunkResult(lang, if (words.isNotEmpty()) LineBuilder.fromWords(words, lang) else segs)
    }

    override suspend fun audioToEnglish(pcm: FloatArray): ChunkResult {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", provider.model)
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("temperature", "0")
            .addFormDataPart("file", "chunk.wav", Wav.encode(pcm).toRequestBody("audio/wav".toMediaType()))
            .build()
        val o = JSONObject(postWithRetry(provider.translateUrl, apiKey, body))
        val lines = mutableListOf<Line>()
        o.optJSONArray("segments")?.let { segs ->
            for (i in 0 until segs.length()) {
                val s = segs.getJSONObject(i)
                val text = s.optString("text").trim()
                if (text.isBlank() || isSilentSeg(s)) continue
                lines += Line((s.getDouble("start") * 1000).toLong(), (s.getDouble("end") * 1000).toLong(), text)
            }
        }
        return ChunkResult(langCode(o.optString("language")), lines)
    }
}

// ---------------------------------------------------------------------------------------------
// Cloudflare Workers AI (free daily allowance) — whisper-large-v3-turbo with word timings
// ---------------------------------------------------------------------------------------------

class CloudflareTranscriber(private val accountId: String, private val token: String) : Transcriber {
    override val id = "cloud-CF"
    override val label = "Cloudflare"
    private val url get() = "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/run/@cf/openai/whisper-large-v3-turbo"

    private suspend fun run(pcm: FloatArray, task: String): JSONObject {
        val audio = Base64.encodeToString(Wav.encode(pcm), Base64.NO_WRAP)
        val req = JSONObject()
            .put("audio", audio)
            .put("task", task)
            .put("condition_on_previous_text", false)
        val o = JSONObject(postWithRetry(url, token, req.toString().toRequestBody("application/json".toMediaType())))
        if (o.has("success") && !o.optBoolean("success")) {
            val msg = errorMessage(o.toString())
            // Daily free allowance used up
            if ("neuron" in msg.lowercase() || "limit" in msg.lowercase()) throw CloudException(429, "Free limit reached")
            throw CloudException(500, "Cloudflare: $msg")
        }
        return o.optJSONObject("result") ?: o
    }

    override suspend fun transcribe(pcm: FloatArray): ChunkResult = parse(run(pcm, "transcribe"), wordsWanted = true)

    override suspend fun audioToEnglish(pcm: FloatArray): ChunkResult? =
        runCatching { parse(run(pcm, "translate"), wordsWanted = false) }.getOrNull()

    private fun parse(r: JSONObject, wordsWanted: Boolean): ChunkResult {
        val lang = langCode(r.optJSONObject("transcription_info")?.optString("language").orEmpty())
        val words = mutableListOf<Word>()
        val segs = mutableListOf<Line>()
        r.optJSONArray("segments")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                if (isSilentSeg(s)) continue
                val text = s.optString("text").trim()
                if (text.isNotBlank()) segs += Line((s.getDouble("start") * 1000).toLong(), (s.getDouble("end") * 1000).toLong(), text)
                s.optJSONArray("words")?.let { ws ->
                    for (j in 0 until ws.length()) {
                        val w = ws.getJSONObject(j)
                        val t = w.optString("word").trim()
                        if (t.isNotEmpty()) words += Word((w.getDouble("start") * 1000).toLong(), (w.getDouble("end") * 1000).toLong(), t)
                    }
                }
            }
        }
        return ChunkResult(lang, if (wordsWanted && words.isNotEmpty()) LineBuilder.fromWords(words, lang) else segs)
    }
}

// ---------------------------------------------------------------------------------------------
// Offline: whisper.cpp on the phone, with DTW word timing
// ---------------------------------------------------------------------------------------------

class LocalTranscriber(private val modelFile: File, private val model: LocalModel) : Transcriber {
    override val id = "local"
    override val label = "offline"

    companion object {
        private val lock = Mutex()
        private var ctx = 0L
        private var loadedPath: String? = null
        private val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)

        suspend fun release() = lock.withLock {
            if (ctx != 0L) WhisperLib.freeContext(ctx)
            ctx = 0L; loadedPath = null
        }
    }

    private suspend fun run(pcm: FloatArray, translate: Boolean): String = withContext(Dispatchers.Default) {
        lock.withLock {
            if (loadedPath != modelFile.path) {
                if (ctx != 0L) WhisperLib.freeContext(ctx)
                ctx = WhisperLib.initContext(modelFile.path, model.aheads)
                loadedPath = if (ctx != 0L) modelFile.path else null
                if (ctx == 0L) throw IllegalStateException("Couldn't load the offline model — try downloading it again")
            }
            String(WhisperLib.translate(ctx, pcm, threads, translate), Charsets.UTF_8)
        }
    }

    override suspend fun transcribe(pcm: FloatArray): ChunkResult = parse(run(pcm, false), translated = false)

    /** Turbo was not trained to translate, so it can't do this step. */
    override suspend fun audioToEnglish(pcm: FloatArray): ChunkResult? =
        if (model == LocalModel.LARGE_TURBO) null else parse(run(pcm, true), translated = true)

    private class Seg(val t0: Long, val t1: Long, val text: String, val noSpeech: Float) {
        val tokens = mutableListOf<Pair<Long, String>>()
    }

    private fun parse(raw: String, translated: Boolean): ChunkResult {
        val rows = raw.split('\n')
        val lang = langCode(rows.firstOrNull().orEmpty())
        val textLang = if (translated) "EN" else lang
        val segs = mutableListOf<Seg>()
        for (r in rows.drop(1)) {
            val p = r.split('\t')
            when (p.firstOrNull()) {
                "S" -> if (p.size >= 5) segs += Seg(p[1].toLong(), p[2].toLong(), p[4].trim(), p[3].toFloatOrNull() ?: 0f)
                "T" -> if (p.size >= 3) segs.lastOrNull()?.tokens?.add(p[1].toLong() to p.subList(2, p.size).joinToString("\t"))
            }
        }
        val noSpace = LineBuilder.isNoSpace(textLang)
        val lines = mutableListOf<Line>()
        for (s in segs) {
            if (s.text.isBlank() || s.noSpeech > 0.8f || (s.text.startsWith("[") && s.text.endsWith("]"))) continue
            // Tokens -> words. Spaced languages: a token starting with a space begins a new word.
            // Japanese/Chinese/Thai: every token is its own unit.
            val words = mutableListOf<Word>()
            var curText = StringBuilder()
            var curStart = -1L
            fun flush(nextStart: Long) {
                val t = curText.toString().trim()
                if (t.isNotEmpty() && curStart >= 0) words += Word(curStart, maxOf(curStart + 80, nextStart), t)
                curText = StringBuilder(); curStart = -1
            }
            for ((t, txt) in s.tokens) {
                val tt = t.coerceIn(s.t0, s.t1)
                if ((noSpace || txt.startsWith(" ")) && curText.isNotEmpty()) flush(tt)
                if (curStart < 0) curStart = tt
                curText.append(txt)
            }
            flush(s.t1)
            val usable = words.size >= 2 && words.zipWithNext().all { (a, b) -> b.startMs >= a.startMs }
            lines += if (usable) LineBuilder.fromWords(words, textLang) else listOf(Line(s.t0, s.t1, s.text))
        }
        return ChunkResult(lang, if (translated) lines.map { it.copy(words = emptyList()) } else lines)
    }
}

object WhisperLib {
    init { System.loadLibrary("arinwhisper") }
    external fun initContext(path: String, aheadsPreset: Int): Long
    external fun freeContext(ptr: Long)
    external fun translate(ptr: Long, samples: FloatArray, threads: Int, translate: Boolean): ByteArray
}

// ---------------------------------------------------------------------------------------------
// Step 2: text translation of timed lines -> English (one English line per spoken line)
// ---------------------------------------------------------------------------------------------

interface LineTranslator {
    /** Exactly one English string per input line, or null if this translator can't do it. */
    suspend fun toEnglish(lines: List<String>, lang: String): List<String>?
}

/** Groq (gpt-oss-120b) / OpenAI text model: best quality, uses context between lines. */
class ChatTranslator(private val provider: CloudProvider, private val apiKey: String) : LineTranslator {
    override suspend fun toEnglish(lines: List<String>, lang: String): List<String>? {
        val sys = "You translate video subtitles into natural, fluent, everyday English. " +
            "The input is JSON {\"lines\": [...]} spoken mostly in language code $lang (lines may mix languages). " +
            "Reply with ONLY JSON {\"en\": [...]} containing exactly ${lines.size} strings: the English for each " +
            "input line, same order, one-to-one. Never merge, split, skip or add lines. Keep lines short. " +
            "Lines already in English stay as they are. Use the neighbouring lines as context."
        val req = JSONObject()
            .put("model", provider.chatModel)
            .put("temperature", 0.2)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", sys))
                .put(JSONObject().put("role", "user").put("content", JSONObject().put("lines", JSONArray(lines)).toString())))
        if (provider == CloudProvider.GROQ) req.put("reasoning_effort", "low")
        val resp = JSONObject(postWithRetry(provider.chatUrl, apiKey, req.toString().toRequestBody("application/json".toMediaType())))
        val content = resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        val json = content.substring(content.indexOf('{'), content.lastIndexOf('}') + 1)
        val arr = JSONObject(json).getJSONArray("en")
        if (arr.length() != lines.size) return null
        return (0 until arr.length()).map { arr.optString(it).trim() }
    }
}

/**
 * Google ML Kit on-device translation: free, unlimited, works offline once the language pack
 * (~30 MB, downloaded automatically the first time a language is heard) is on the phone.
 */
object MlKitTranslator : LineTranslator {
    private val clients = mutableMapOf<String, Translator>()
    private val lock = Mutex()

    fun supports(lang: String) = TranslateLanguage.fromLanguageTag(lang.lowercase()) != null

    override suspend fun toEnglish(lines: List<String>, lang: String): List<String>? {
        val src = TranslateLanguage.fromLanguageTag(lang.lowercase()) ?: return null
        if (src == TranslateLanguage.ENGLISH) return lines
        val t = lock.withLock {
            clients.getOrPut(src) {
                Translation.getClient(
                    TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(TranslateLanguage.ENGLISH).build()
                )
            }
        }
        t.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        return lines.map { t.translate(it).await() }
    }
}

// ---------------------------------------------------------------------------------------------

/** Groups timed words into readable subtitle lines, breaking at pauses and sentence ends. */
object LineBuilder {
    private val NO_SPACE_LANGS = setOf("JA", "ZH", "YUE", "TH", "LO", "MY", "KM", "BO")
    private val SENTENCE_END = Regex("""[.?!。？！…।]["'”’)」』]*$""")
    private const val PAUSE_MS = 600
    private const val MAX_LINE_MS = 5_500

    fun isNoSpace(lang: String) = lang in NO_SPACE_LANGS

    fun fromWords(words: List<Word>, lang: String): List<Line> {
        val noSpace = isNoSpace(lang)
        val maxChars = if (noSpace) 22 else 64
        val out = mutableListOf<Line>()
        var cur = mutableListOf<Word>()
        fun text(ws: List<Word>) = if (noSpace) ws.joinToString("") { it.text } else ws.joinToString(" ") { it.text }
        fun flush() {
            if (cur.isNotEmpty()) out += Line(cur.first().startMs, cur.last().endMs, text(cur).trim(), cur.toList())
            cur = mutableListOf()
        }
        for (w in words) {
            if (cur.isNotEmpty()) {
                val gap = w.startMs - cur.last().endMs
                val tooLong = text(cur + w).length > maxChars || w.endMs - cur.first().startMs > MAX_LINE_MS
                if (gap > PAUSE_MS || tooLong) flush()
            }
            cur += w
            if (SENTENCE_END.containsMatchIn(w.text) && cur.last().endMs - cur.first().startMs > 700) flush()
        }
        flush()
        return out
    }
}

private val LANG_NAMES = mapOf(
    "english" to "EN", "japanese" to "JA", "chinese" to "ZH", "korean" to "KO", "hindi" to "HI",
    "bengali" to "BN", "assamese" to "AS", "spanish" to "ES", "french" to "FR", "german" to "DE",
    "russian" to "RU", "portuguese" to "PT", "italian" to "IT", "arabic" to "AR", "tamil" to "TA",
    "telugu" to "TE", "urdu" to "UR", "marathi" to "MR", "gujarati" to "GU", "kannada" to "KN",
    "malayalam" to "ML", "punjabi" to "PA", "nepali" to "NE", "indonesian" to "ID", "thai" to "TH",
    "vietnamese" to "VI", "turkish" to "TR", "persian" to "FA", "dutch" to "NL", "polish" to "PL",
    "ukrainian" to "UK", "tagalog" to "TL", "malay" to "MS", "cantonese" to "YUE", "hebrew" to "HE",
)

/** Groq/OpenAI return names ("japanese"), whisper.cpp/Cloudflare return codes ("ja"). Normalise to "JA". */
fun langCode(s: String): String {
    val t = s.trim().lowercase()
    if (t.isEmpty()) return ""
    if (t.length <= 3) return t.uppercase()
    return LANG_NAMES[t] ?: t.take(2).uppercase()
}
