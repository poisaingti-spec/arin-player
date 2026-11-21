package com.arin.player.engine

import com.arin.player.data.CloudProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** A translated line, times relative to the start of the chunk. */
data class Segment(val startMs: Long, val endMs: Long, val text: String)

/** [lang] = detected spoken language as an upper-case code ("JA", "HI"…). */
data class ChunkResult(val lang: String, val segments: List<Segment>)

interface Transcriber {
    val label: String
    /** Detects the spoken language in [pcm] (16 kHz mono) and returns English subtitles for it. */
    suspend fun translate(pcm: FloatArray): ChunkResult
}

class CloudException(msg: String) : RuntimeException(msg)

/** Whisper large-v3 (Groq) / whisper-1 (OpenAI) translations endpoint: audio in any language -> English. */
class CloudTranscriber(private val provider: CloudProvider, private val apiKey: String) : Transcriber {
    override val label = "cloud"

    companion object {
        val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun translate(pcm: FloatArray): ChunkResult = withContext(Dispatchers.IO) {
        val wav = Wav.encode(pcm)
        var attempt = 0
        while (true) {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", provider.model)
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart("temperature", "0")
                .addFormDataPart("file", "chunk.wav", wav.toRequestBody("audio/wav".toMediaType()))
                .build()
            val req = Request.Builder().url(provider.url)
                .header("Authorization", "Bearer $apiKey")
                .post(body).build()

            var retryAfterMs = -1L
            var result: ChunkResult? = null
            http.newCall(req).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                when {
                    resp.isSuccessful -> result = parse(txt)
                    resp.code == 401 -> throw CloudException("API key rejected — check it in Settings")
                    (resp.code == 429 || resp.code >= 500) && attempt < 4 ->
                        retryAfterMs = resp.header("retry-after")?.toDoubleOrNull()?.times(1000)?.toLong()
                            ?: (2_000L shl attempt)
                    else -> throw CloudException("Cloud error ${resp.code}: ${errorMessage(txt)}")
                }
            }
            result?.let { return@withContext it }
            attempt++
            delay(retryAfterMs.coerceIn(1_000, 60_000))
        }
        error("unreachable")
    }

    private fun parse(json: String): ChunkResult {
        val o = JSONObject(json)
        val lang = langCode(o.optString("language"))
        val segs = o.optJSONArray("segments")
        val list = mutableListOf<Segment>()
        if (segs != null) {
            for (i in 0 until segs.length()) {
                val s = segs.getJSONObject(i)
                val text = s.optString("text").trim()
                // Whisper's own silence heuristic: drop "Thank you."-style hallucinations on silence.
                val silent = s.optDouble("no_speech_prob", 0.0) > 0.6 && s.optDouble("avg_logprob", 0.0) < -1.0
                if (text.isBlank() || silent) continue
                list += Segment((s.getDouble("start") * 1000).toLong(), (s.getDouble("end") * 1000).toLong(), text)
            }
        } else {
            val text = o.optString("text").trim()
            if (text.isNotBlank()) list += Segment(0, (o.optDouble("duration", 30.0) * 1000).toLong(), text)
        }
        return ChunkResult(lang, list)
    }

    private fun errorMessage(txt: String) =
        runCatching { JSONObject(txt).getJSONObject("error").getString("message") }.getOrDefault(txt.take(120))
}

/** whisper.cpp running on the phone's CPU. */
class LocalTranscriber(private val modelFile: File) : Transcriber {
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
                ctx = WhisperLib.initContext(modelFile.path)
                loadedPath = if (ctx != 0L) modelFile.path else null
                if (ctx == 0L) throw IllegalStateException("Couldn't load the offline model — try downloading it again")
            }
            val raw = String(WhisperLib.translate(ctx, pcm, threads, true), Charsets.UTF_8)
            val lines = raw.split('\n')
            val lang = langCode(lines.firstOrNull().orEmpty())
            val segs = lines.drop(1).mapNotNull { line ->
                val p = line.split('\t', limit = 4)
                if (p.size < 4) return@mapNotNull null
                val text = p[3].trim()
                val noSpeech = p[2].toFloatOrNull() ?: 0f
                if (text.isBlank() || noSpeech > 0.8f || text.startsWith("[") && text.endsWith("]")) null
                else Segment(p[0].toLong(), p[1].toLong(), text)
            }
            ChunkResult(lang, segs)
        }
    }
}

object WhisperLib {
    init { System.loadLibrary("arinwhisper") }
    external fun initContext(path: String): Long
    external fun freeContext(ptr: Long)
    external fun translate(ptr: Long, samples: FloatArray, threads: Int, translate: Boolean): ByteArray
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
