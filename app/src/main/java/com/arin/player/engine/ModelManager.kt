package com.arin.player.engine

import android.content.Context
import com.arin.player.data.LocalModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File

/** Downloads/stores whisper.cpp GGML models. Downloads survive leaving the Settings screen. */
class ModelManager(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "models").apply { mkdirs() }

    fun file(m: LocalModel) = File(dir, m.file)
    fun isReady(m: LocalModel) = file(m).let { it.exists() && it.length() > 10_000_000 }

    fun download(m: LocalModel) {
        if (jobs[m]?.isActive == true) return
        jobs[m] = scope.launch {
            val tmp = File(dir, m.file + ".part")
            try {
                progress.update { it + (m to 0f) }
                val resp = CloudTranscriber.http.newBuilder().callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
                    .build().newCall(Request.Builder().url(m.url).build()).execute()
                resp.use {
                    if (!it.isSuccessful) throw IllegalStateException("HTTP ${it.code}")
                    val body = it.body!!
                    val total = body.contentLength().takeIf { n -> n > 0 } ?: (m.sizeMb * 1_048_576L)
                    body.byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(256 * 1024)
                            var done = 0L
                            while (isActive) {
                                val n = input.read(buf); if (n < 0) break
                                out.write(buf, 0, n); done += n
                                progress.update { p -> p + (m to (done.toFloat() / total).coerceAtMost(0.99f)) }
                            }
                        }
                    }
                }
                if (isActive) { file(m).delete(); tmp.renameTo(file(m)) }
                errors.update { it - m }
            } catch (e: Exception) {
                tmp.delete()
                errors.update { it + (m to (e.message ?: "Download failed")) }
            } finally {
                progress.update { it - m }
            }
        }
    }

    fun cancel(m: LocalModel) { jobs[m]?.cancel() }

    fun delete(m: LocalModel) {
        cancel(m)
        file(m).delete()
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val jobs = mutableMapOf<LocalModel, Job>()
        /** 0..1 for models currently downloading. */
        val progress = MutableStateFlow<Map<LocalModel, Float>>(emptyMap())
        val errors = MutableStateFlow<Map<LocalModel, String>>(emptyMap())
    }
}
