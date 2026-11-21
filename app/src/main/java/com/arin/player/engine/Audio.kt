package com.arin.player.engine

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.math.sqrt

/** Whisper wants 16 kHz mono. */
const val SAMPLE_RATE = 16_000

interface AudioSource : Closeable {
    /** 16 kHz mono samples for [startMs, endMs). Returns null if this source can't decode the file. */
    suspend fun read(startMs: Long, endMs: Long): FloatArray?
    override fun close() {}
}

/**
 * Fast path: Android's own demuxer + hardware/software decoders.
 * Handles MP4, MKV, WebM, 3GP, TS, MOV with AAC/MP3/Opus/Vorbis/FLAC etc.
 * Can seek straight to any chunk, so subtitles follow the playback position.
 */
class MediaCodecAudioSource(private val context: Context, private val uri: Uri) : AudioSource {

    override suspend fun read(startMs: Long, endMs: Long): FloatArray? = withContext(Dispatchers.IO) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@withContext null
            ex.selectTrack(track)
            val inFmt = ex.getTrackFormat(track)
            val mime = inFmt.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime).apply { configure(inFmt, null, null, 0); start() }

            val startUs = startMs * 1000
            val endUs = endMs * 1000
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            var rate = inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            val mono = FloatList()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idle = 0

            while (true) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        val t = ex.sampleTime
                        if (n < 0 || t > endUs + 500_000) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, t, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (o >= 0) {
                    idle = 0
                    val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val bytesPerFrame = (if (floatPcm) 4 else 2) * channels
                    val frames = info.size / bytesPerFrame
                    for (f in 0 until frames) {
                        var s = 0f
                        for (c in 0 until channels) s += if (floatPcm) buf.float else buf.short / 32768f
                        val tUs = info.presentationTimeUs + f * 1_000_000L / rate
                        if (tUs in startUs until endUs) mono.add(s / channels)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    if (info.presentationTimeUs > endUs) break
                } else if (inputDone && ++idle > 100) break
            }
            resample(mono.toArray(), rate, SAMPLE_RATE)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null // unsupported container/codec on this device -> caller falls back to VLC
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }
}

/**
 * Fallback for anything Android can't decode (AVI, WMV, FLV, RMVB, AC3/DTS on some phones…).
 * libVLC transcodes the whole audio track to a 16 kHz mono WAV in the background;
 * chunks become readable as soon as that part of the file has been written.
 */
class VlcAudioSource(
    private val context: Context,
    private val libVlc: LibVLC,
    private val uri: Uri,
) : AudioSource {
    private val wav = File(context.cacheDir, "vlc_audio.wav")
    private val finished = CompletableDeferred<Boolean>()
    private var pfd: ParcelFileDescriptor? = null
    private var player: MediaPlayer? = null
    @Volatile private var dataOffset = -1L

    /** Must be called on the main thread (libVLC event handler). */
    fun start() {
        wav.delete()
        val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: run { finished.complete(false); return }
        pfd = fd
        val media = Media(libVlc, fd.fileDescriptor)
        media.addOption(
            ":sout=#transcode{vcodec=none,scodec=none,acodec=s16l,channels=1,samplerate=$SAMPLE_RATE}" +
                ":std{access=file,mux=wav,dst=${wav.absolutePath}}"
        )
        media.addOption(":no-sout-video")
        media.addOption(":no-sout-spu")
        media.addOption(":sout-keep")
        val mp = MediaPlayer(media)
        media.release()
        mp.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.EndReached -> finished.complete(true)
                MediaPlayer.Event.EncounteredError -> finished.complete(false)
            }
        }
        player = mp
        mp.play()
    }

    override suspend fun read(startMs: Long, endMs: Long): FloatArray? = withContext(Dispatchers.IO) {
        val bytesPerMs = SAMPLE_RATE * 2 / 1000L
        while (true) {
            coroutineContext.ensureActive()
            val off = findDataOffset()
            if (off > 0 && wav.length() >= off + endMs * bytesPerMs) break
            if (finished.isCompleted) break
            delay(500)
        }
        val off = findDataOffset()
        if (off <= 0) return@withContext null
        RandomAccessFile(wav, "r").use { f ->
            val from = off + startMs * bytesPerMs
            val to = min(off + endMs * bytesPerMs, f.length())
            if (to <= from) return@withContext FloatArray(0)
            val bytes = ByteArray(((to - from) / 2 * 2).toInt())
            f.seek(from); f.readFully(bytes)
            val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            FloatArray(sb.remaining()) { sb.get(it) / 32768f }
        }
    }

    private fun findDataOffset(): Long {
        if (dataOffset > 0) return dataOffset
        if (!wav.exists() || wav.length() < 44) return -1
        RandomAccessFile(wav, "r").use { f ->
            val head = ByteArray(min(4096L, f.length()).toInt()); f.readFully(head)
            val bb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            if (String(head, 0, 4) != "RIFF" || String(head, 8, 4) != "WAVE") return -1
            var pos = 12
            while (pos + 8 <= head.size) {
                val id = String(head, pos, 4)
                val size = bb.getInt(pos + 4).toLong() and 0xFFFFFFFFL
                if (id == "data") { dataOffset = pos + 8L; return dataOffset }
                pos += 8 + size.toInt() + (size and 1).toInt()
            }
        }
        return -1
    }

    override fun close() {
        player?.let { it.setEventListener(null); it.stop(); it.release() }
        player = null
        runCatching { pfd?.close() }
        wav.delete()
    }
}

// ---- helpers -------------------------------------------------------------

class FloatList {
    private var a = FloatArray(16_000 * 32)
    private var n = 0
    fun add(v: Float) {
        if (n == a.size) a = a.copyOf(a.size * 2)
        a[n++] = v
    }
    fun toArray(): FloatArray = a.copyOf(n)
}

/** Downsamples with box-averaging (cheap anti-aliasing), upsamples with nearest sample. */
fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
    if (from == to || x.isEmpty()) return x
    val outN = (x.size.toLong() * to / from).toInt()
    val r = from.toDouble() / to
    return FloatArray(outN) { i ->
        val s = (i * r).toInt()
        val e = min(((i + 1) * r).toInt(), x.size)
        if (e <= s + 1) x[min(s, x.size - 1)]
        else { var sum = 0f; for (j in s until e) sum += x[j]; sum / (e - s) }
    }
}

fun rms(x: FloatArray): Float {
    if (x.isEmpty()) return 0f
    var s = 0.0
    for (v in x) s += v * v
    return sqrt(s / x.size).toFloat()
}

object Wav {
    fun encode(pcm: FloatArray, rate: Int = SAMPLE_RATE): ByteArray {
        val dataLen = pcm.size * 2
        val b = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + dataLen).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(dataLen)
        for (s in pcm) b.putShort((s.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        return b.array()
    }
}
