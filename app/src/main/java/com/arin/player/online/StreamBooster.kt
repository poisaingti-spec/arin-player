package com.arin.player.online

import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.min

/**
 * Makes online videos load at the phone's full internet speed.
 *
 * Many sites (YouTube especially) limit how fast ONE connection can download. A normal player uses
 * one connection, so it crawls even on fast internet. The booster is a tiny local server the player
 * reads from; behind it, the file is downloaded in 1 MB pieces over 6 connections at once, and the
 * next ~8 MB are fetched ahead of where the video is playing.
 */
object StreamBooster {
    private const val BLOCK = 1 shl 20      // 1 MB pieces
    private const val CONNECTIONS = 6       // parallel downloads
    private const val AHEAD = 8             // pieces fetched ahead of the play position
    private const val MAX_CACHED = 24       // pieces kept in memory per stream
    private const val MAX_STREAMS = 6

    private val fetchPool = Executors.newFixedThreadPool(CONNECTIONS) { r -> Thread(r, "booster-fetch").apply { isDaemon = true } }
    private val servePool = Executors.newCachedThreadPool { r -> Thread(r, "booster-serve").apply { isDaemon = true } }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private var server: ServerSocket? = null
    private val streams = ConcurrentHashMap<String, Upstream>()
    private val order = ArrayDeque<String>()

    /** Plain downloadable files are boosted; live/segmented streams (HLS/DASH) are played directly. */
    fun canBoost(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val s = uri.toString().lowercase()
        return ".m3u8" !in s && ".mpd" !in s && "manifest" !in s && "/hls" !in s
    }

    /** Returns a copy of [src] whose online links go through the booster. */
    fun boost(src: PlaybackSource): PlaybackSource {
        if (src.isLocal || !canBoost(src.uri)) return src
        return runCatching {
            src.copy(
                uri = Uri.parse(register(src.uri.toString(), src.headers)),
                audioUri = src.audioUri?.let { a -> if (canBoost(a)) Uri.parse(register(a.toString(), src.headers)) else a },
                headers = if (src.audioUri != null && !canBoost(src.audioUri)) src.headers else emptyMap(),
            )
        }.getOrDefault(src)
    }

    @Synchronized
    private fun register(url: String, headers: Map<String, String>): String {
        val port = ensureServer()
        val id = UUID.randomUUID().toString().replace("-", "")
        streams[id] = Upstream(url, headers)
        order.addLast(id)
        while (order.size > MAX_STREAMS) streams.remove(order.removeFirst())
        return "http://127.0.0.1:$port/s/$id"
    }

    @Synchronized
    private fun ensureServer(): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = s
        thread(isDaemon = true, name = "booster-accept") {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                servePool.execute { serve(c) }
            }
        }
        return s.localPort
    }

    // ---------------------------------------------------------------------------------------

    private class Upstream(val url: String, val headers: Map<String, String>) {
        @Volatile var length = -1L
        @Volatile var contentType = "application/octet-stream"
        @Volatile var rangeOk = true
        private val blocks = object : LinkedHashMap<Int, Future<ByteArray>>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Future<ByteArray>>) = size > MAX_CACHED
        }

        fun request(range: String?): Request {
            val b = Request.Builder().url(url)
            headers.forEach { (k, v) -> b.header(k, v) }
            if (range != null) b.header("Range", range)
            return b.build()
        }

        /** Learns the total size and whether the server allows partial downloads. */
        @Synchronized
        fun probe() {
            if (length >= 0) return
            client.newCall(request("bytes=0-0")).execute().use { r ->
                r.header("Content-Type")?.let { contentType = it }
                if (r.code == 206) {
                    length = r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1L
                    if (length < 0) rangeOk = false
                } else {
                    rangeOk = false
                    length = r.body?.contentLength() ?: -1L
                }
            }
        }

        fun block(i: Int): Future<ByteArray> = synchronized(blocks) {
            val f = blocks[i]
            if (f != null && !(f.isDone && runCatching { f.get() }.isFailure)) return f
            fetchPool.submit<ByteArray> { fetch(i) }.also { blocks[i] = it }
        }

        private fun fetch(i: Int): ByteArray {
            val from = i.toLong() * BLOCK
            val to = min(length, from + BLOCK) - 1
            var lastError: Exception? = null
            repeat(3) {
                try {
                    client.newCall(request("bytes=$from-$to")).execute().use { r ->
                        if (r.code != 206) throw IllegalStateException("HTTP ${r.code}")
                        val bytes = r.body!!.bytes()
                        if (bytes.size.toLong() != to - from + 1) throw IllegalStateException("short read")
                        return bytes
                    }
                } catch (e: Exception) {
                    lastError = e
                    Thread.sleep(400L * (it + 1))
                }
            }
            throw lastError ?: IllegalStateException("download failed")
        }
    }

    // ---------------------------------------------------------------------------------------

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    private fun serve(socket: Socket) = runCatching {
        socket.use { s ->
            s.soTimeout = 120_000
            val input = BufferedInputStream(s.getInputStream())
            val requestLine = readLine(input) ?: return@use
            val reqHeaders = mutableMapOf<String, String>()
            while (true) {
                val l = readLine(input) ?: break
                if (l.isEmpty()) break
                val i = l.indexOf(':')
                if (i > 0) reqHeaders[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0) ?: "GET"
            val id = parts.getOrNull(1)?.removePrefix("/s/")?.substringBefore('?')
            val out = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
            val up = id?.let { streams[it] }
            if (up == null) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush()
                return@use
            }
            up.probe()
            val rangeHeader = reqHeaders["range"]
            if (!up.rangeOk || up.length <= 0) {
                passThrough(up, rangeHeader, method, out); return@use
            }
            val total = up.length
            var start = 0L
            var end = total - 1
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                val (a, b) = rangeHeader.removePrefix("bytes=").substringBefore(',').split('-', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                if (a.isEmpty() && b.isNotEmpty()) { start = (total - b.toLong()).coerceAtLeast(0) } // last N bytes
                else {
                    start = a.toLongOrNull() ?: 0
                    if (b.isNotEmpty()) end = min(b.toLong(), total - 1)
                }
            }
            if (start >= total) {
                out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush(); return@use
            }
            val len = end - start + 1
            val head = StringBuilder()
            head.append(if (rangeHeader != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            head.append("Content-Type: ${up.contentType}\r\nAccept-Ranges: bytes\r\nContent-Length: $len\r\n")
            if (rangeHeader != null) head.append("Content-Range: bytes $start-$end/$total\r\n")
            head.append("Connection: close\r\n\r\n")
            out.write(head.toString().toByteArray())
            if (method == "HEAD") { out.flush(); return@use }

            var pos = start
            val lastBlock = (end / BLOCK).toInt()
            while (pos <= end) {
                val bi = (pos / BLOCK).toInt()
                for (k in bi..min(bi + AHEAD, lastBlock)) up.block(k) // keep the pipeline full
                val data = try {
                    up.block(bi).get(90, TimeUnit.SECONDS)
                } catch (e: ExecutionException) {
                    up.block(bi).get(90, TimeUnit.SECONDS) // one fresh retry
                }
                val off = (pos - bi.toLong() * BLOCK).toInt()
                val n = min((data.size - off).toLong(), end - pos + 1).toInt()
                if (n <= 0) break
                out.write(data, off, n)
                pos += n
            }
            out.flush()
        }
    }

    /** Server doesn't support partial downloads: just relay it. */
    private fun passThrough(up: Upstream, range: String?, method: String, out: BufferedOutputStream) {
        client.newCall(up.request(range)).execute().use { r ->
            val head = StringBuilder("HTTP/1.1 ${r.code} OK\r\n")
            r.header("Content-Type")?.let { head.append("Content-Type: $it\r\n") }
            r.header("Content-Length")?.let { head.append("Content-Length: $it\r\n") }
            r.header("Content-Range")?.let { head.append("Content-Range: $it\r\n") }
            head.append("Connection: close\r\n\r\n")
            out.write(head.toString().toByteArray())
            if (method != "HEAD") r.body?.byteStream()?.copyTo(out, 64 * 1024)
            out.flush()
        }
    }
}
