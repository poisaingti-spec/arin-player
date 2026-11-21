package com.arin.player.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import com.arin.player.data.Cue
import com.arin.player.data.EngineMode
import com.arin.player.data.Settings
import com.arin.player.data.SubtitleCache
import com.arin.player.online.PlaybackSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.videolan.libvlc.LibVLC
import java.io.IOException
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * Splits the video's audio into 30 s chunks and turns each into English subtitles.
 *
 *  - Chunks at/just ahead of the playback position are done first, so subtitles are ready
 *    before you reach them; seeking re-prioritises instantly.
 *  - Every chunk gets its own language detection, so videos that switch languages work.
 *  - Segment timestamps from Whisper are offset by the chunk start -> cues line up with speech.
 *  - Results are cached per video.
 */
class SubtitleEngine(
    context: Context,
    private val source: PlaybackSource,
    private val libVlc: LibVLC,
    private val scope: CoroutineScope,
) {
    companion object {
        const val CHUNK_MS = 30_000L
        /** How far ahead of playback to translate (6 × 30 s = 3 min). */
        const val LOOKAHEAD = 6
    }

    private val ctx = context.applicationContext
    private val settings = Settings(ctx)
    private val models = ModelManager(ctx)
    private val cache = SubtitleCache(ctx)

    val cues = MutableStateFlow<List<Cue>>(emptyList())
    val status = MutableStateFlow("Waiting for video…")
    val languages = MutableStateFlow<List<String>>(emptyList())
    /** Fraction of the whole video that has subtitles. */
    val progress = MutableStateFlow(0f)

    @Volatile private var positionMs = 0L
    @Volatile private var fullMode = false
    private var durationMs = 0L
    private var done = BooleanArray(0)
    private val inFlight = mutableSetOf<Int>()
    private var key = ""
    private var job: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** For YouTube-style sources the separate audio stream is all we need to listen to. */
    private val audioUri: Uri = source.audioUri ?: source.uri
    private val primary: AudioSource = MediaCodecAudioSource(ctx, audioUri, source.headers)
    private var vlc: VlcAudioSource? = null
    private val vlcLock = Mutex()
    @Volatile private var useVlc = false

    fun start(videoDurationMs: Long) {
        if (job != null || videoDurationMs <= 0) return
        durationMs = videoDurationMs
        val n = ((durationMs + CHUNK_MS - 1) / CHUNK_MS).toInt()
        done = BooleanArray(n)
        key = cache.key(source.cacheId, durationMs)
        cache.load(key)?.let { (d, c) ->
            d.filter { it < n }.forEach { done[it] = true }
            publish(c)
        }
        job = scope.launch(Dispatchers.Default) {
            // 2 workers: while one waits on the network/model, the other decodes the next chunk.
            repeat(2) { launch { workerLoop() } }
        }
    }

    fun onPosition(ms: Long) {
        val old = positionMs
        positionMs = ms
        if (abs(ms - old) > 5_000) wake.trySend(Unit) // seek
    }

    /** Translate the whole video (e.g. before exporting an .srt), not just what's ahead. */
    fun generateAll() { fullMode = true; wake.trySend(Unit) }

    fun stop() {
        job?.cancel()
        vlc?.close()
        primary.close()
    }

    // --------------------------------------------------------------------

    private suspend fun workerLoop() {
        while (currentCoroutineContext().isActive) {
            val i = nextChunk()
            if (i == null) {
                idleStatus()
                withTimeoutOrNull(1_000) { wake.receive() }
                continue
            }
            try {
                process(i)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                synchronized(this) { inFlight -= i }
                status.value = "⚠ " + (e.message ?: e.javaClass.simpleName)
                delay(5_000) // retry later (e.g. after the user fixes Settings or goes online)
            }
        }
    }

    private fun nextChunk(): Int? = synchronized(this) {
        if (done.isEmpty()) return null
        val cur = (positionMs / CHUNK_MS).toInt().coerceIn(0, done.size - 1)
        val limit = if (fullMode) done.size else min(done.size, cur + LOOKAHEAD)
        val pick = (cur until limit).firstOrNull { !done[it] && it !in inFlight }
            ?: if (fullMode) (0 until cur).firstOrNull { !done[it] && it !in inFlight } else null
        pick?.also { inFlight += it }
    }

    private suspend fun process(i: Int) {
        val start = i * CHUNK_MS
        val end = min(durationMs, start + CHUNK_MS)
        status.value = "Listening ${fmt(start)}–${fmt(end)}"

        val pcm = readAudio(start, end)
        if (pcm == null) {
            markDone(i, emptyList())
            status.value = "No audio track found"
            return
        }
        // Silence / music-free gaps: skip (saves API cost and avoids hallucinated lines).
        if (pcm.size < SAMPLE_RATE / 2 || rms(pcm) < 0.004f) {
            markDone(i, emptyList()); return
        }

        val t = pickTranscriber()
        status.value = "Translating ${fmt(start)}–${fmt(end)} (${t.label})"
        val result = try {
            t.translate(pcm)
        } catch (e: IOException) {
            // Network dropped mid-way: in Auto mode, fall back to the offline model if we have one.
            val local = local()
            if (settings.mode == EngineMode.AUTO && t is CloudTranscriber && local != null) {
                status.value = "Offline fallback ${fmt(start)}–${fmt(end)}"
                local.translate(pcm)
            } else throw IOException("No internet connection")
        }

        val newCues = result.segments.mapNotNull { s ->
            val cs = start + s.startMs
            val ce = min(start + s.endMs, end + 1_000)
            if (ce <= cs) null else Cue(cs, ce, s.text, result.lang)
        }
        markDone(i, newCues)
    }

    private suspend fun readAudio(startMs: Long, endMs: Long): FloatArray? {
        if (!useVlc) {
            primary.read(startMs, endMs)?.let { return it }
            useVlc = true
        }
        val src = vlcLock.withLock {
            vlc ?: withContext(Dispatchers.Main) {
                status.value = "Using VLC decoder for this format…"
                VlcAudioSource(ctx, libVlc, audioUri, source.headers).also { it.start() }
            }.also { vlc = it }
        }
        return src.read(startMs, endMs)
    }

    private fun pickTranscriber(): Transcriber = when (settings.mode) {
        EngineMode.CLOUD -> cloud() ?: throw IllegalStateException("Add a cloud API key in Settings")
        EngineMode.OFFLINE -> local() ?: throw IllegalStateException("Download an offline model in Settings")
        EngineMode.AUTO -> (if (online()) cloud() else null) ?: local()
            ?: if (cloud() != null) throw IOException("Offline — download an offline model in Settings")
            else throw IllegalStateException("Add an API key or download an offline model in Settings")
    }

    private fun cloud(): Transcriber? =
        settings.apiKey().takeIf { it.isNotBlank() }?.let { CloudTranscriber(settings.provider, it) }

    private fun local(): Transcriber? =
        settings.localModel.takeIf { models.isReady(it) }?.let { LocalTranscriber(models.file(it)) }

    private fun online(): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun markDone(i: Int, newCues: List<Cue>) = synchronized(this) {
        done[i] = true
        inFlight -= i
        val start = i * CHUNK_MS
        val merged = cues.value.filterNot { it.startMs >= start && it.startMs < start + CHUNK_MS } + newCues
        publish(merged)
        runCatching { cache.save(key, done.indices.filter { done[it] }.toSet(), cues.value) }
    }

    private fun publish(list: List<Cue>) {
        val sorted = list.sortedBy { it.startMs }
        cues.value = sorted
        languages.value = sorted.map { it.lang }.filter { it.isNotBlank() }.distinct()
        progress.value = if (done.isEmpty()) 0f else done.count { it }.toFloat() / done.size
    }

    private fun idleStatus() {
        val all = synchronized(this) { done.isNotEmpty() && done.all { it } }
        if (status.value.startsWith("⚠")) return
        status.value = when {
            done.isEmpty() -> status.value
            all -> "✓ Subtitles ready for the whole video"
            else -> "✓ Subtitles ready ahead"
        }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }
}
