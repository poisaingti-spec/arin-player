package com.arin.player.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import com.arin.player.data.Cue
import com.arin.player.data.EngineMode
import com.arin.player.data.Settings
import com.arin.player.data.SubtitleCache
import com.arin.player.data.Word
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
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Turns the WHOLE video's audio into English subtitles, any length.
 *
 *  - Audio is processed in ~30 s pieces, starting where you're watching, then the rest of the video
 *    (after the end it wraps round to the beginning) until every piece is done.
 *  - Piece edges are moved to the quietest moment nearby, so no word is cut in half.
 *  - Each line is timed from word timestamps, then snapped to the actual start/end of the voice.
 *  - Every piece gets its own language detection, so videos that switch languages work.
 *  - A piece that fails is retried later instead of blocking the rest; in Auto mode the offline
 *    model takes over when the cloud is unreachable or out of quota.
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
        /** How far a piece edge may move to find a quiet moment. */
        const val EDGE_SEARCH_MS = 2_500L
        /** Keep a line on screen a little after the voice stops (easier to read). */
        const val HOLD_MS = 350L
        const val MIN_SHOW_MS = 900L
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
    private var durationMs = 0L
    private var done = BooleanArray(0)
    private val inFlight = mutableSetOf<Int>()
    /** chunk -> time it last failed, so a failing piece doesn't block the rest of the video. */
    private val failedAt = ConcurrentHashMap<Int, Long>()
    private val edges = ConcurrentHashMap<Int, Long>()
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
            // 2 workers: while one waits on the network/model, the other decodes the next piece.
            repeat(2) { launch { workerLoop() } }
        }
    }

    fun onPosition(ms: Long) {
        val old = positionMs
        positionMs = ms
        if (abs(ms - old) > 5_000) wake.trySend(Unit) // seek -> re-prioritise
    }

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
                withTimeoutOrNull(2_000) { wake.receive() }
                continue
            }
            try {
                process(i)
                failedAt.remove(i)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                synchronized(this) { inFlight -= i }
                failedAt[i] = System.currentTimeMillis()
                status.value = "⚠ " + (e.message ?: e.javaClass.simpleName)
                delay(3_000)
            }
        }
    }

    /** Current position first, then onward to the end, then the beginning: the whole video. */
    private fun nextChunk(): Int? = synchronized(this) {
        if (done.isEmpty()) return null
        val n = done.size
        val cur = (positionMs / CHUNK_MS).toInt().coerceIn(0, n - 1)
        val now = System.currentTimeMillis()
        fun ready(i: Int) = !done[i] && i !in inFlight && (failedAt[i]?.let { now - it > 60_000 } ?: true)
        val pick = ((cur until n) + (0 until cur)).firstOrNull { ready(it) }
        pick?.also { inFlight += it }
    }

    private suspend fun process(i: Int) {
        val start = edge(i)
        val end = edge(i + 1)
        status.value = "Listening ${fmt(start)}–${fmt(end)}"

        val pcm = readAudio(start, end)
        if (pcm == null) {
            markDone(i, start, end, emptyList())
            status.value = "No audio track found"
            return
        }
        // Total silence: nothing to subtitle (saves API cost and avoids hallucinated lines).
        if (pcm.size < SAMPLE_RATE / 2 || rms(pcm) < 0.0015f) {
            markDone(i, start, end, emptyList()); return
        }

        val result = recognise(pcm, start, end)

        val voice = VoiceMap(pcm)
        val refined = result.lines.map { l -> voice.refine(l) }
        val newCues = refined.mapIndexedNotNull { idx, l ->
            if (l.text.isBlank()) return@mapIndexedNotNull null
            val nextStart = refined.getOrNull(idx + 1)?.startMs ?: Long.MAX_VALUE
            val s = start + l.startMs
            // Show until the voice stops (+ a short hold), at least MIN_SHOW, never over the next line.
            val e = start + min(max(l.endMs + HOLD_MS, l.startMs + MIN_SHOW_MS), nextStart - 40)
            if (e <= s) null
            else Cue(s, e, l.text, result.lang, l.words.map { w -> Word(start + w.startMs, start + w.endMs, w.text) })
        }
        markDone(i, start, end, newCues)
    }

    /** Start of piece [k]: near k × 30 s, moved to the quietest 200 ms within ±2.5 s. */
    private suspend fun edge(k: Int): Long {
        if (k <= 0) return 0
        if (k >= done.size) return durationMs
        edges[k]?.let { return it }
        val center = k * CHUNK_MS
        val from = max(0, center - EDGE_SEARCH_MS)
        val to = min(durationMs, center + EDGE_SEARCH_MS)
        val pcm = runCatching { readAudio(from, to) }.getOrNull()
        val win = SAMPLE_RATE / 5 // 200 ms
        val step = SAMPLE_RATE / 50 // 20 ms
        var best = center
        if (pcm != null && pcm.size > win) {
            var bestE = Double.MAX_VALUE
            var p = 0
            while (p + win <= pcm.size) {
                var e = 0.0
                for (j in p until p + win) e += pcm[j] * pcm[j]
                // slight preference for staying near the 30 s mark
                val tMs = from + (p + win / 2) * 1000L / SAMPLE_RATE
                e *= 1.0 + abs(tMs - center) / 10_000.0
                if (e < bestE) { bestE = e; best = tMs }
                p += step
            }
        }
        edges[k] = best
        return best
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

    /** Providers in order of preference; one whose free limit ran out is skipped for a while. */
    private val pausedUntil = ConcurrentHashMap<String, Long>()

    private fun candidates(): List<Transcriber> {
        val mode = settings.mode
        val now = System.currentTimeMillis()
        val list = buildList {
            if (mode != EngineMode.OFFLINE && online()) {
                cloud()?.let(::add)
                cloudflare()?.let(::add)
            }
            if (mode != EngineMode.CLOUD) local()?.let(::add)
        }
        if (list.isEmpty()) throw IllegalStateException(
            when {
                mode == EngineMode.OFFLINE -> "Download an offline model in Settings"
                !online() && local() == null -> "No internet — download an offline model in Settings"
                else -> "Add a free Groq API key (or download an offline model) in Settings"
            }
        )
        val active = list.filter { (pausedUntil[it.id] ?: 0) < now }
        return active.ifEmpty { list } // all paused: try anyway (limits may have reset)
    }

    /**
     * Step 1: hear the words with exact timings (first provider that works).
     * Step 2: translate each timed line to English (AI text model, else Google's offline translator,
     *         else the recogniser's own audio translation).
     */
    private suspend fun recognise(pcm: FloatArray, start: Long, end: Long): ChunkResult {
        var last: Exception? = null
        for (t in candidates()) {
            status.value = "Listening ${fmt(start)}–${fmt(end)} (${t.label})"
            val heard = try {
                t.transcribe(pcm)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                // Free limit used up: pause this provider for 15 min, use the next one meanwhile.
                if (e is CloudException && e.code == 429) pausedUntil[t.id] = System.currentTimeMillis() + 15 * 60_000L
                continue
            }
            if (heard.lines.isEmpty() || heard.lang == "EN") return heard
            status.value = "Translating ${fmt(start)}–${fmt(end)}"
            val english = translateLines(heard.lines.map { it.text }, heard.lang)
            if (english != null) {
                return ChunkResult(heard.lang, heard.lines.mapIndexed { i, l -> Line(l.startMs, l.endMs, english[i]) })
            }
            val direct = runCatching { t.audioToEnglish(pcm) }.getOrNull()
            return if (direct != null && direct.lines.isNotEmpty()) direct.copy(lang = heard.lang)
            else heard.copy(lines = heard.lines.map { it.copy(words = emptyList()) }) // original language
        }
        throw last ?: IllegalStateException("No subtitle engine available")
    }

    private suspend fun translateLines(lines: List<String>, lang: String): List<String>? {
        val key = settings.apiKey()
        if (key.isNotBlank() && settings.mode != EngineMode.OFFLINE && online()) {
            runCatching { ChatTranslator(settings.provider, key).toEnglish(lines, lang) }
                .getOrNull()?.let { return it }
        }
        if (MlKitTranslator.supports(lang)) {
            runCatching { MlKitTranslator.toEnglish(lines, lang) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun cloud(): Transcriber? =
        settings.apiKey().takeIf { it.isNotBlank() }?.let { CloudTranscriber(settings.provider, it) }

    private fun cloudflare(): Transcriber? =
        if (settings.cfAccountId.isNotBlank() && settings.cfToken.isNotBlank())
            CloudflareTranscriber(settings.cfAccountId, settings.cfToken) else null

    private fun local(): Transcriber? =
        settings.localModel.takeIf { models.isReady(it) }?.let { LocalTranscriber(models.file(it), it) }

    private fun online(): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun markDone(i: Int, start: Long, end: Long, newCues: List<Cue>) = synchronized(this) {
        done[i] = true
        inFlight -= i
        val merged = cues.value.filterNot { it.startMs >= start && it.startMs < end } + newCues
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
        val (all, failed) = synchronized(this) { (done.isNotEmpty() && done.all { it }) to failedAt.size }
        if (status.value.startsWith("⚠") && failed > 0) return
        status.value = when {
            done.isEmpty() -> status.value
            all -> "✓ Subtitles ready for the whole video"
            failed > 0 -> "Retrying $failed part(s) shortly…"
            else -> status.value
        }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }
}

/**
 * Simple voice-activity map of a piece (20 ms frames). Used to snap each line's start to the moment
 * the voice actually begins and its end to when it stops.
 */
private class VoiceMap(pcm: FloatArray) {
    private val frameMs = 20
    private val frame = SAMPLE_RATE * frameMs / 1000
    private val voiced: BooleanArray

    init {
        val n = pcm.size / frame
        val energy = FloatArray(n) { f ->
            var s = 0.0
            for (j in f * frame until (f + 1) * frame) s += pcm[j] * pcm[j]
            sqrt(s / frame).toFloat()
        }
        val sorted = energy.sorted()
        val noise = if (sorted.isEmpty()) 0f else sorted[(sorted.size * 0.15).toInt().coerceIn(0, sorted.size - 1)]
        val loud = if (sorted.isEmpty()) 0f else sorted[(sorted.size * 0.9).toInt().coerceIn(0, sorted.size - 1)]
        val thr = max(noise * 2.5f, noise + (loud - noise) * 0.12f).coerceAtLeast(0.004f)
        voiced = BooleanArray(n) { energy[it] > thr }
    }

    private fun isVoiced(ms: Long): Boolean {
        val f = (ms / frameMs).toInt()
        return f in voiced.indices && voiced[f]
    }

    fun refine(l: Line): Line {
        if (voiced.isEmpty()) return l
        var s = l.startMs
        var e = l.endMs
        // Start: if the timestamp falls in silence, move forward to where the voice begins (max 0.6 s);
        // if it's already in voice, step back to the true onset (max 0.25 s).
        if (!isVoiced(s)) {
            var t = s
            while (t < s + 600 && t < e - 200 && !isVoiced(t)) t += frameMs
            if (isVoiced(t)) s = t
        } else {
            var t = s
            while (t > l.startMs - 250 && t > 0 && isVoiced(t - frameMs)) t -= frameMs
            s = t
        }
        // End: if it falls in silence, pull back to where the voice stopped (max 0.6 s).
        if (!isVoiced(e)) {
            var t = e
            while (t > e - 600 && t > s + 300 && !isVoiced(t)) t -= frameMs
            if (isVoiced(t)) e = t + frameMs
        }
        return l.copy(startMs = s, endMs = max(e, s + 200))
    }
}
