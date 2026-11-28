package com.arin.player.engine

import android.content.Context
import com.arin.player.data.Word
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Meta's Omnilingual ASR (1,600+ languages, incl. Khasi, Mizo, Garo, Bodo, Manipuri, Kokborok,
 * Nagamese, Odia, Bhojpuri, Maithili…) running on the phone through sherpa-onnx. 348 MB download.
 */
object WorldModel {
    private const val REPO =
        "https://huggingface.co/csukuangfj/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12/resolve/main"
    private val FILES = listOf("model.int8.onnx" to 348L * 1_048_576, "tokens.txt" to 200_000L)
    const val SIZE_MB = 350

    val progress = MutableStateFlow<Float?>(null)
    val error = MutableStateFlow<String?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, "models/omnilingual").apply { mkdirs() }

    fun isReady(ctx: Context): Boolean {
        val d = dir(ctx)
        return File(d, "model.int8.onnx").length() > 300_000_000 && File(d, "tokens.txt").length() > 1_000
    }

    fun download(ctx: Context) {
        if (job?.isActive == true) return
        val d = dir(ctx)
        job = scope.launch {
            error.value = null
            progress.value = 0f
            try {
                val total = FILES.sumOf { it.second }.toFloat()
                var doneBefore = 0L
                for ((name, approx) in FILES) {
                    val target = File(d, name)
                    if (target.exists() && target.length() > approx / 2) { doneBefore += approx; continue }
                    val tmp = File(d, "$name.part")
                    val client = http.newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
                    client.newCall(Request.Builder().url("$REPO/$name").build()).execute().use { r ->
                        if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                        r.body!!.byteStream().use { input ->
                            tmp.outputStream().use { out ->
                                val buf = ByteArray(256 * 1024)
                                var got = 0L
                                while (isActive) {
                                    val n = input.read(buf); if (n < 0) break
                                    out.write(buf, 0, n); got += n
                                    progress.value = ((doneBefore + got) / total).coerceAtMost(0.99f)
                                }
                            }
                        }
                    }
                    if (!isActive) { tmp.delete(); return@launch }
                    target.delete(); tmp.renameTo(target)
                    doneBefore += approx
                }
            } catch (e: Exception) {
                error.value = e.message ?: "Download failed"
            } finally {
                progress.value = null
            }
        }
    }

    fun cancel() { job?.cancel() }

    fun delete(ctx: Context) {
        cancel()
        dir(ctx).listFiles()?.forEach { it.delete() }
    }
}

/** Speech recogniser using the world-languages model. Writes down what is said, with word timing. */
class WorldTranscriber(private val ctx: Context, private val languageCode: String?) : Transcriber {
    override val id = "world"
    override val label = "world languages"

    companion object {
        private val lock = Mutex()
        private var recognizer: OfflineRecognizer? = null
        private val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
    }

    override suspend fun transcribe(pcm: FloatArray, forceLang: String?): ChunkResult = withContext(Dispatchers.Default) {
        lock.withLock {
            val rec = recognizer ?: run {
                val d = WorldModel.dir(ctx)
                val cfg = OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        omnilingual = OfflineOmnilingualAsrCtcModelConfig(model = File(d, "model.int8.onnx").absolutePath),
                        tokens = File(d, "tokens.txt").absolutePath,
                        numThreads = threads,
                        provider = "cpu",
                    ),
                    decodingMethod = "greedy_search",
                )
                OfflineRecognizer(config = cfg).also { recognizer = it }
            }
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(pcm, SAMPLE_RATE)
                rec.decode(stream)
                val res = rec.getResult(stream)
                val lang = forceLang ?: languageCode ?: ""
                val words = toWords(res.tokens, res.timestamps, pcm.size * 1000L / SAMPLE_RATE)
                val lines = if (words.isNotEmpty()) LineBuilder.fromWords(words, lang)
                else res.text.trim().takeIf { it.isNotEmpty() }?.let { listOf(Line(0, pcm.size * 1000L / SAMPLE_RATE, it)) }.orEmpty()
                ChunkResult(lang, lines)
            } finally {
                stream.release()
            }
        }
    }

    /** No direct speech→English here; the text translator does that step. */
    override suspend fun audioToEnglish(pcm: FloatArray): ChunkResult? = null

    /** CTC tokens (characters / pieces) with start times -> words. */
    private fun toWords(tokens: Array<String>, times: FloatArray, endMs: Long): List<Word> {
        val out = mutableListOf<Word>()
        var cur = StringBuilder()
        var start = -1L
        var lastT = 0L
        fun flush(next: Long) {
            val t = cur.toString().trim()
            if (t.isNotEmpty() && start >= 0) out += Word(start, maxOf(start + 80, next), t)
            cur = StringBuilder(); start = -1
        }
        for (i in tokens.indices) {
            val tok = tokens[i]
            val t = ((times.getOrNull(i) ?: 0f) * 1000).toLong()
            lastT = t
            val boundary = tok == " " || tok == "|" || tok.startsWith("▁") || tok.startsWith(" ")
            if (boundary) {
                flush(t)
                val rest = tok.trimStart('▁', ' ', '|')
                if (rest.isNotEmpty()) { start = t; cur.append(rest) }
            } else {
                if (start < 0) start = t
                cur.append(tok)
            }
        }
        flush(maxOf(lastT + 300, minOf(endMs, lastT + 800)))
        return out
    }
}
