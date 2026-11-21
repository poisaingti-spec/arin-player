package com.arin.player.engine

import com.arin.player.data.CloudProvider
import com.arin.player.data.LocalModel
import com.arin.player.data.Word
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * One subtitle line, times relative to the start of the chunk. [startMs]/[endMs] are exactly when
 * the line is spoken. [words] carries per-word timing when the text is the spoken words themselves.
 */
data class Line(val startMs: Long, val endMs: Long, val text: String, val words: List<Word> = emptyList())

/** [lang] = detected spoken language as an upper-case code ("JA", "HI"…). Lines are in English. */
data class ChunkResult(val lang: String, val lines: List<Line>)

interface Transcriber {
    val label: String
    /** Detects the spoken language in [pcm] (16 kHz mono) and returns timed English subtitle lines. */
    suspend fun translate(pcm: FloatArray): ChunkResult
}

class CloudException(val code: Int, msg: String) : RuntimeException(msg)

/**
 * Cloud pipeline for tight sync:
 *  1. Whisper large-v3 *transcribes* the audio in its own language with a timestamp for every word.
 *  2. Words are grouped into short lines at pauses and sentence ends -> each line has exact start/end.
 *  3. Non-English lines are translated to English by a text model, one-to-one, keeping those times.
 *     English audio keeps its words, so they light up word by word.
 * If step 3 fails, falls back to Whisper's own audio->English translation.
 */
class CloudTranscriber(private val provider: CloudProvider, private val apiKey: String) : Transcriber {
    override val label = "cloud"

    companion object {
        val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun translate(pcm: FloatArray): ChunkResult = withContext(Dispatchers.IO) {
        val wav = Wav.encode(pcm)
        val t = transcribe(wav)
        if (t.lines.isEmpty() || t.lang == "EN") return@withContext t
        val english = runCatching { translateLines(t.lines.map { it.text }, t.lang) }.getOrNull()
        if (english != null) {
            ChunkResult(t.lang, t.lines.mapIndexed { i, l -> Line(l.startMs, l.endMs, english[i]) }
                .filter { it.text.isNotBlank() })
        } else {
            audioTranslate(wav, t.lang)
        }
    }

    /** Step 1+2: same-language transcript with word timestamps, grouped into lines. */
    private suspend fun transcribe(wav: ByteArray): ChunkResult {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", provider.model)
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("timestamp_granularities[]", "word")
            .addFormDataPart("timestamp_granularities[]", "segment")
            .addFormDataPart("temperature", "0")
            .addFormDataPart("file", "chunk.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        val o = JSONObject(post(provider.transcribeUrl, body))
        val lang = langCode(o.optString("language"))

        // Segments Whisper itself flags as silence/noise ("Thank you." hallucinations): drop their words.
        val bad = mutableListOf<LongRange>()
        val segs = mutableListOf<Line>()
        o.optJSONArray("segments")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val st = (s.getDouble("start") * 1000).toLong()
                val en = (s.getDouble("end") * 1000).toLong()
                val silent = s.optDouble("no_speech_prob", 0.0) > 0.6 && s.optDouble("avg_logprob", 0.0) < -1.0
                if (silent) bad += st..en
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
        val lines = if (words.isNotEmpty()) LineBuilder.fromWords(words, lang) else segs
        return ChunkResult(lang, if (lang == "EN") lines else lines.map { it.copy(words = emptyList()) })
    }

    /** Step 3: translate N lines -> exactly N English lines. Returns null if the model doesn't comply. */
    private suspend fun translateLines(lines: List<String>, lang: String): List<String>? {
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
        val resp = JSONObject(post(provider.chatUrl, req.toString().toRequestBody("application/json".toMediaType())))
        val content = resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        val json = content.substring(content.indexOf('{'), content.lastIndexOf('}') + 1)
        val arr = JSONObject(json).getJSONArray("en")
        if (arr.length() != lines.size) return null
        return (0 until arr.length()).map { arr.optString(it).trim() }
    }

    /** Fallback: Whisper's own audio -> English (segment-level timing). */
    private suspend fun audioTranslate(wav: ByteArray, lang: String): ChunkResult {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", provider.model)
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("temperature", "0")
            .addFormDataPart("file", "chunk.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        val o = JSONObject(post(provider.translateUrl, body))
        val lines = mutableListOf<Line>()
        o.optJSONArray("segments")?.let { segs ->
            for (i in 0 until segs.length()) {
                val s = segs.getJSONObject(i)
                val text = s.optString("text").trim()
                val silent = s.optDouble("no_speech_prob", 0.0) > 0.6 && s.optDouble("avg_logprob", 0.0) < -1.0
                if (text.isBlank() || silent) continue
                lines += Line((s.getDouble("start") * 1000).toLong(), (s.getDouble("end") * 1000).toLong(), text)
            }
        }
        return ChunkResult(langCode(o.optString("language")).ifBlank { lang }, lines)
    }

    /** POST with retries on rate limits / server errors. */
    private suspend fun post(url: String, body: RequestBody): String {
        var attempt = 0
        while (true) {
            val req = Request.Builder().url(url).header("Authorization", "Bearer $apiKey").post(body).build()
            var retryAfterMs = -1L
            http.newCall(req).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                when {
                    resp.isSuccessful -> return txt
                    resp.code == 401 -> throw CloudException(401, "API key rejected — check it in Settings")
                    (resp.code == 429 || resp.code >= 500) && attempt < 4 ->
                        retryAfterMs = resp.header("retry-after")?.toDoubleOrNull()?.times(1000)?.toLong()
                            ?: (2_000L shl attempt)
                    resp.code == 429 -> throw CloudException(429, "Cloud limit reached: ${errorMessage(txt)}")
                    else -> throw CloudException(resp.code, "Cloud error ${resp.code}: ${errorMessage(txt)}")
                }
            }
            attempt++
            delay(retryAfterMs.coerceIn(1_000, 60_000))
        }
    }

    private fun errorMessage(txt: String) =
        runCatching { JSONObject(txt).getJSONObject("error").getString("message") }.getOrDefault(txt.take(120))
}

/** whisper.cpp running on the phone's CPU, with DTW word timing. */
class LocalTranscriber(private val modelFile: File, private val model: LocalModel) : Transcriber {
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

    override suspend fun translate(pcm: FloatArray): ChunkResult = withContext(Dispatchers.Default) {
        lock.withLock {
            if (loadedPath != modelFile.path) {
                if (ctx != 0L) WhisperLib.freeContext(ctx)
                ctx = WhisperLib.initContext(modelFile.path, model.aheads)
                loadedPath = if (ctx != 0L) modelFile.path else null
                if (ctx == 0L) throw IllegalStateException("Couldn't load the offline model — try downloading it again")
            }
            parse(String(WhisperLib.translate(ctx, pcm, threads, true), Charsets.UTF_8))
        }
    }

    private class Seg(val t0: Long, val t1: Long, val text: String, val noSpeech: Float) {
        val tokens = mutableListOf<Pair<Long, String>>()
    }

    private fun parse(raw: String): ChunkResult {
        val rows = raw.split('\n')
        val lang = langCode(rows.firstOrNull().orEmpty())
        val segs = mutableListOf<Seg>()
        for (r in rows.drop(1)) {
            val p = r.split('\t')
            when (p.firstOrNull()) {
                "S" -> if (p.size >= 5) segs += Seg(p[1].toLong(), p[2].toLong(), p[4].trim(), p[3].toFloatOrNull() ?: 0f)
                "T" -> if (p.size >= 3) segs.lastOrNull()?.tokens?.add(p[1].toLong() to p.subList(2, p.size).joinToString("\t"))
            }
        }
        val lines = mutableListOf<Line>()
        for (s in segs) {
            if (s.text.isBlank() || s.noSpeech > 0.8f || (s.text.startsWith("[") && s.text.endsWith("]"))) continue
            // Tokens -> words: a token starting with a space begins a new word.
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
                if (txt.startsWith(" ") && curText.isNotEmpty()) flush(tt)
                if (curStart < 0) curStart = tt
                curText.append(txt)
            }
            flush(s.t1)
            val usable = words.size >= 2 && words.zipWithNext().all { (a, b) -> b.startMs >= a.startMs }
            if (usable) {
                val built = LineBuilder.fromWords(words, "EN") // output text is English (translate mode)
                lines += if (lang == "EN") built else built.map { it.copy(words = emptyList()) }
            } else {
                lines += Line(s.t0, s.t1, s.text)
            }
        }
        return ChunkResult(lang, lines)
    }
}

object WhisperLib {
    init { System.loadLibrary("arinwhisper") }
    external fun initContext(path: String, aheadsPreset: Int): Long
    external fun freeContext(ptr: Long)
    external fun translate(ptr: Long, samples: FloatArray, threads: Int, translate: Boolean): ByteArray
}

/** Groups timed words into readable subtitle lines, breaking at pauses and sentence ends. */
object LineBuilder {
    private val NO_SPACE_LANGS = setOf("JA", "ZH", "YUE", "TH", "LO", "MY", "KM", "BO")
    private val SENTENCE_END = Regex("""[.?!。？！…]["'”’)]*$""")
    private const val PAUSE_MS = 600
    private const val MAX_LINE_MS = 5_500

    fun fromWords(words: List<Word>, lang: String): List<Line> {
        val noSpace = lang in NO_SPACE_LANGS
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
    "ukrainian" to "UK", "tagalog" to "TL", "malay" to "MS", "cantonese" to "YUE",
)

/** Groq/OpenAI return names ("japanese"), whisper.cpp returns codes ("ja"). Normalise to "JA". */
fun langCode(s: String): String {
    val t = s.trim().lowercase()
    if (t.isEmpty()) return ""
    if (t.length <= 3) return t.uppercase()
    return LANG_NAMES[t] ?: t.take(2).uppercase()
}
