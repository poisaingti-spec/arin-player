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
import com.arin.player.data.Languages
import com.arin.player.data.SpokenLanguage
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
 *  - Each piece is heard with 2 s of overlap and silence padding, the last piece reads to the very end,
 *    and any voiced stretch left without a line is heard again, so nothing from start to end is missed.
 *  - Audio is processed in ~60 s pieces, starting where you're watching, then the rest of the video
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
    /** Spoken language chosen by the user for this video ([Languages.AUTO] = detect). */
    private val language: SpokenLanguage = Languages.AUTO,
) {
    companion object {
        /** 60 s pieces: half the requests of 30 s, so the free cloud limit goes twice as far. */
        const val CHUNK_MS = 60_000L
        /** How far a piece edge may move to find a quiet moment. */
        const val EDGE_SEARCH_MS = 2_500L
        /** Keep a line on screen a little after the voice stops (easier to read). */
        const val HOLD_MS = 350L
        const val MIN_SHOW_MS = 900L
        /** Each piece is heard with 2 s of the neighbouring audio on both sides, so words at the edges aren't lost. */
        const val OVERLAP_MS = 2_000L
        /** Silence added before/after the audio sent to Whisper: it often drops the very first/last words otherwise. */
        const val PAD_MS = 700L
        /** A stretch of voice this long with no subtitle is heard again on its own. */
        const val GAP_MS = 3_500L
        /** Average log-probability below this = Whisper is guessing (unknown language). */
        const val LOW_CONFIDENCE = -1.05f
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
    /** Which parts of the video have subtitles (one entry per piece), for the coverage bar. */
    val coverage = MutableStateFlow(BooleanArray(0))
    /** e.g. "about 6 min left" while working. */
    val eta = MutableStateFlow("")

    /** Language found per piece, and how many lines it had (to spot misheard pieces). */
    private val pieceLang = ConcurrentHashMap<Int, String>()
    private val pieceLines = ConcurrentHashMap<Int, Int>()
    private val redone = ConcurrentHashMap.newKeySet<Int>()
    private val finishTimes = ArrayDeque<Long>()

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
        key = cache.key(source.cacheId + "|" + language.code, durationMs)
        cache.load(key)?.let { (d, c) ->
            d.filter { it < n }.forEach { done[it] = true }
            // rebuild per-piece language info from the cached lines
            c.groupBy { (it.startMs / CHUNK_MS).toInt() }.forEach { (i, cs) ->
                pieceLang[i] = cs.groupingBy { it.lang }.eachCount().maxByOrNull { it.value }?.key ?: ""
                pieceLines[i] = cs.size
            }
            publish(c)
        }
        job = scope.launch(Dispatchers.Default) {
            // 3 workers: while some wait on the network/model, another decodes the next piece.
            repeat(3) { launch { workerLoop() } }
        }
    }

    fun onPosition(ms: Long) {
        val old = positionMs
        positionMs = ms
        if (abs(ms - old) > 5_000) wake.trySend(Unit) // seek -> re-prioritise
    }

    /** Loudness of the loudest 20 ms in the audio: 0 only when the whole stretch is silent. */
    private fun peakFrameRms(pcm: FloatArray): Float {
        val frame = SAMPLE_RATE / 50
        var best = 0f
        var p = 0
        while (p + frame <= pcm.size) {
            var e = 0.0
            for (j in p until p + frame) e += pcm[j] * pcm[j]
            best = max(best, sqrt(e / frame).toFloat())
            p += frame
        }
        return best
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
        val last = i == done.size - 1
        status.value = "Listening ${fmt(start)}–${fmt(end)}"

        // Read a little extra on both sides. The last piece reads past the reported length, so
        // speech in the final seconds (or after a slightly short duration) is never cut off.
        val readFrom = max(0, start - OVERLAP_MS)
        val readTo = if (last) durationMs + 10_000 else min(durationMs, end + OVERLAP_MS)
        val pcm = readAudio(readFrom, readTo)
        if (pcm == null) {
            markDone(i, start, end, emptyList())
            status.value = "No audio track found"
            return
        }
        // Only skip when there is truly no sound at all (quiet speech at the start/end must still be heard).
        if (pcm.size < SAMPLE_RATE / 4 || peakFrameRms(pcm) < 0.002f) {
            markDone(i, start, end, emptyList()); return
        }

        // Times below are relative to readFrom. This piece owns lines that START inside [start, end).
        val ownFrom = start - readFrom
        val ownTo = if (last) Long.MAX_VALUE else end - readFrom
        val result = recognise(padded(pcm), start, end).shifted(-PAD_MS)
        val voice = VoiceMap(pcm)
        val lines = result.lines.filter { it.startMs in ownFrom until ownTo }.toMutableList()
        val langOf = HashMap<Line, String>().apply { lines.forEach { put(it, result.lang) } }

        // Gap check: any stretch of voice with no subtitle (Whisper sometimes skips speech under
        // music, or right after a long silence) is heard again on its own.
        val ownEndMs = min(ownTo, pcm.size * 1000L / SAMPLE_RATE)
        for ((gs, ge) in voice.uncovered(lines, ownFrom, ownEndMs, GAP_MS).take(4)) {
            val a = max(0, gs - 400)
            val b = min(pcm.size * 1000L / SAMPLE_RATE, ge + 400)
            status.value = "Re-checking ${fmt(readFrom + a)}–${fmt(readFrom + b)}"
            val sub = runCatching {
                recognise(padded(pcm.copyOfRange((a * SAMPLE_RATE / 1000).toInt(), (b * SAMPLE_RATE / 1000).toInt())),
                    readFrom + a, readFrom + b).shifted(a - PAD_MS)
            }.getOrNull() ?: continue
            sub.lines.filter { it.startMs in gs until ge && lines.none { o -> overlaps(o, it) } }
                .forEach { lines += it; langOf[it] = sub.lang }
        }
        lines.sortBy { it.startMs }

        val refined = lines.map { l -> voice.refine(l) to (langOf[l] ?: result.lang) }
        val newCues = refined.mapIndexedNotNull { idx, (l, lang) ->
            if (l.text.isBlank()) return@mapIndexedNotNull null
            val nextStart = refined.getOrNull(idx + 1)?.first?.startMs ?: Long.MAX_VALUE
            val s = readFrom + l.startMs
            // Show until the voice stops (+ a short hold), at least MIN_SHOW, never over the next line.
            val e = readFrom + min(max(l.endMs + HOLD_MS, l.startMs + MIN_SHOW_MS), nextStart - 40)
            if (e <= s) null
            else Cue(s, e, l.text, lang, l.words.map { w -> Word(readFrom + w.startMs, readFrom + w.endMs, w.text) })
        }
        markDone(i, start, end, newCues, result.lang)
    }

    private fun padded(pcm: FloatArray): FloatArray {
        val pad = (PAD_MS * SAMPLE_RATE / 1000).toInt()
        return FloatArray(pcm.size + 2 * pad).also { pcm.copyInto(it, pad) }
    }

    private fun ChunkResult.shifted(by: Long) = copy(lines = lines.map { l ->
        l.copy(
            startMs = max(0, l.startMs + by), endMs = max(0, l.endMs + by),
            words = l.words.map { w -> Word(max(0, w.startMs + by), max(0, w.endMs + by), w.text) },
        )
    })

    private fun overlaps(a: Line, b: Line): Boolean {
        val o = min(a.endMs, b.endMs) - max(a.startMs, b.startMs)
        return o > 0 && o > 0.5 * min(a.endMs - a.startMs, b.endMs - b.startMs)
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
        // A language only the world model knows (e.g. Khasi, Mizo, Nagamese, Karbi…)
        if (language.usesWorldModel && language != Languages.AUTO) {
            if (!WorldModel.isReady(ctx)) throw IllegalStateException(
                "${language.name} needs the World languages model — download it in Settings")
            status.value = "Listening ${fmt(start)}–${fmt(end)} (${language.name})"
            val code = if (language.code == "OTHER") "" else language.code
            val heard = WorldTranscriber(ctx, code).transcribe(pcm, code.ifBlank { null })
            return finishTranslation(null, pcm, heard, start, end)
        }
        var last: Exception? = null
        for (t in candidates()) {
            status.value = "Listening ${fmt(start)}–${fmt(end)} (${t.label})"
            val forced = language.whisper?.uppercase()
            val heard = try {
                t.transcribe(pcm, forced)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                // Free limit used up: pause this provider for 15 min, use the next one meanwhile.
                if (e is CloudException && e.code == 429) pausedUntil[t.id] = System.currentTimeMillis() + 15 * 60_000L
                continue
            }
            var heard2 = heard
            if (language == Languages.AUTO) {
                // Whisper doesn't know this language at all (very low confidence): if the world model
                // is on the phone, let it write down the speech instead.
                if (heard.lines.isNotEmpty() && heard.confidence < LOW_CONFIDENCE && WorldModel.isReady(ctx)) {
                    status.value = "Listening ${fmt(start)}–${fmt(end)} (world languages)"
                    val w = runCatching { WorldTranscriber(ctx, null).transcribe(pcm, null) }.getOrNull()
                    if (w != null && w.lines.isNotEmpty()) return finishTranslation(null, pcm, w, start, end)
                }
                // Whisper sometimes "hears" a random language in music / quiet parts (Norwegian, Khmer…).
                // Once the video's main language is clear, a piece with few lines in another language is
                // re-heard as the main language.
                val main = mainLanguage()
                if (main != null && heard.lang != main && heard.lines.size < 6) {
                    runCatching { t.transcribe(pcm, main) }.getOrNull()?.let { heard2 = it }
                }
            }
            return finishTranslation(t, pcm, heard2, start, end)
        }
        throw last ?: IllegalStateException("No subtitle engine available")
    }

    private suspend fun finishTranslation(t: Transcriber?, pcm: FloatArray, heard: ChunkResult, start: Long, end: Long): ChunkResult {
        if (heard.lines.isEmpty() || heard.lang == "EN") return heard
        status.value = "Translating ${fmt(start)}–${fmt(end)}"
        val english = translateLines(heard.lines.map { it.text }, heard.lang)
        if (english != null) {
            return ChunkResult(heard.lang, heard.lines.mapIndexed { i, l -> Line(l.startMs, l.endMs, english[i]) })
        }
        val direct = runCatching { t?.audioToEnglish(pcm) }.getOrNull()
        return if (direct != null && direct.lines.isNotEmpty()) direct.copy(lang = heard.lang)
        else heard.copy(lines = heard.lines.map { it.copy(words = emptyList()) }) // original language
    }

    /** The language most of the video is spoken in, once that's clear (≥ 4 pieces, ≥ 60 %). */
    private fun mainLanguage(): String? {
        val counts = HashMap<String, Int>()
        pieceLang.forEach { (i, l) -> if (l.isNotBlank()) counts[l] = (counts[l] ?: 0) + (pieceLines[i] ?: 0) }
        val total = counts.values.sum()
        val top = counts.maxByOrNull { it.value } ?: return null
        val pieces = pieceLang.count { (_, l) -> l == top.key }
        return if (pieces >= 4 && total > 0 && top.value >= total * 0.6) top.key else null
    }

    /** Pieces done before the main language was known, heard as some other language with few lines: redo once. */
    private fun requeueMisheard() {
        val main = mainLanguage() ?: return
        synchronized(this) {
            pieceLang.forEach { (i, l) ->
                if (l.isNotBlank() && l != main && (pieceLines[i] ?: 0) < 6 && i !in inFlight && redone.add(i) && i < done.size) {
                    done[i] = false
                }
            }
        }
    }

    private suspend fun translateLines(lines: List<String>, lang: String): List<String>? {
        val key = settings.apiKey()
        val name = when {
            lang.isBlank() -> "an unidentified language (possibly a regional language of India, e.g. from North-East India)"
            else -> Languages.nameOf(lang).let { n ->
                val l = Languages.byCode(lang)
                if (l.region.isNotBlank()) "$n (spoken in ${l.region}, India)" else n
            }
        }
        if (key.isNotBlank() && online()) {
            runCatching { ChatTranslator(settings.provider, key).toEnglish(lines, name) }
                .getOrNull()?.let { return it }
        }
        if (lang.isNotBlank() && MlKitTranslator.supports(lang)) {
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

    private fun markDone(i: Int, start: Long, end: Long, newCues: List<Cue>, lang: String = "") {
        markDoneLocked(i, start, end, newCues, lang)
        requeueMisheard()
        updateEta()
    }

    private fun updateEta() {
        val now = System.currentTimeMillis()
        val remaining = synchronized(this) { done.count { !it } }
        synchronized(finishTimes) {
            finishTimes.addLast(now)
            while (finishTimes.size > 12) finishTimes.removeFirst()
            eta.value = when {
                remaining == 0 -> ""
                finishTimes.size < 3 -> "estimating time…"
                else -> {
                    val perPiece = (finishTimes.last() - finishTimes.first()) / (finishTimes.size - 1).toDouble()
                    val mins = Math.ceil(perPiece * remaining / 60_000.0).toInt()
                    if (mins <= 1) "about 1 min left" else "about $mins min left"
                }
            }
        }
    }

    private fun markDoneLocked(i: Int, start: Long, end: Long, newCues: List<Cue>, lang: String) = synchronized(this) {
        done[i] = true
        inFlight -= i
        if (lang.isNotBlank()) pieceLang[i] = lang
        pieceLines[i] = newCues.size
        val others = cues.value.filterNot { it.startMs >= start && (it.startMs < end || i == done.size - 1) }
        // Near the piece edges, skip a line the neighbouring piece already has (same words heard twice).
        val fresh = newCues.filter { c ->
            (c.startMs - start > 2_500 && end - c.endMs > 2_500) || others.none { o ->
                val ov = min(o.endMs, c.endMs) - max(o.startMs, c.startMs)
                ov > 0 && ov > 0.5 * min(o.endMs - o.startMs, c.endMs - c.startMs)
            }
        }
        val merged = others + fresh
        publish(merged)
        runCatching { cache.save(key, done.indices.filter { done[it] }.toSet(), cues.value) }
    }

    private fun publish(list: List<Cue>) {
        val sorted = list.sortedBy { it.startMs }
        cues.value = sorted
        // Only show languages that make up a real part of the video (hides one-off misdetections).
        val counts = sorted.filter { it.lang.isNotBlank() }.groupingBy { it.lang }.eachCount()
        val total = counts.values.sum().coerceAtLeast(1)
        languages.value = counts.entries.filter { it.value >= 3 && it.value * 20 >= total }
            .sortedByDescending { it.value }.map { it.key }.take(4)
        progress.value = if (done.isEmpty()) 0f else done.count { it }.toFloat() / done.size
        coverage.value = done.copyOf()
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

    /** Stretches (relative ms) of at least [minMs] that are mostly voice but have no line. */
    fun uncovered(lines: List<Line>, from: Long, to: Long, minMs: Long): List<Pair<Long, Long>> {
        if (voiced.isEmpty() || to <= from) return emptyList()
        val covered = BooleanArray(voiced.size)
        for (l in lines) for (f in (l.startMs / frameMs).toInt()..(l.endMs / frameMs).toInt())
            if (f in covered.indices) covered[f] = true
        val out = mutableListOf<Pair<Long, Long>>()
        var gapStart = -1
        var voicedFrames = 0
        val first = (from / frameMs).toInt().coerceIn(0, voiced.size)
        val lastF = (to / frameMs).toInt().coerceIn(0, voiced.size)
        fun close(f: Int) {
            if (gapStart < 0) return
            val len = f - gapStart
            if (len * frameMs >= minMs && voicedFrames >= len * 0.35) {
                // split long gaps into ≤ 30 s parts
                var a = gapStart
                while (a < f) { val b = min(f, a + 30_000 / frameMs); out += a * frameMs.toLong() to b * frameMs.toLong(); a = b }
            }
            gapStart = -1; voicedFrames = 0
        }
        for (f in first until lastF) {
            if (covered[f]) close(f)
            else { if (gapStart < 0) gapStart = f; if (voiced[f]) voicedFrames++ }
        }
        close(lastF)
        return out
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
