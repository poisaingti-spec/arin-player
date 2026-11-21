package com.arin.player.online

import android.content.Context
import android.net.Uri
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the user asked to play: a local file, a direct media link, or a web page (YouTube etc). */
data class PlayRequest(
    val uri: Uri,
    /** Extra HTTP headers (Referer/User-Agent) when the link was found in the in-app browser. */
    val headers: Map<String, String> = emptyMap(),
    /** true = already a media URL, don't run yt-dlp on it. */
    val direct: Boolean = false,
)

/** Something the player can actually open. */
data class PlaybackSource(
    val uri: Uri,
    /** Separate audio stream (YouTube serves HD video and audio separately). */
    val audioUri: Uri?,
    val headers: Map<String, String>,
    val title: String,
    /** Stable id for the subtitle cache (stream URLs expire, the page URL doesn't). */
    val cacheId: String,
) {
    val isLocal get() = !isNetworkUri(uri)
}

fun isNetworkUri(uri: Uri) = uri.scheme?.lowercase() in setOf("http", "https", "rtsp", "rtmp", "mms", "udp", "rtp")

data class ResolvedMedia(
    val videoUrl: String,
    val audioUrl: String?,
    val headers: Map<String, String>,
    val title: String?,
)

/**
 * Turns a web page link (YouTube, Facebook, Instagram, X, Vimeo, Dailymotion, Reddit… 1000+ sites)
 * into playable stream URLs using yt-dlp, bundled with Python + QuickJS by youtubedl-android.
 */
object OnlineResolver {
    private val MEDIA_EXT = setOf(
        "mp4", "m4v", "mkv", "webm", "mov", "avi", "flv", "ts", "m3u8", "mpd", "3gp",
        "wmv", "mpg", "mpeg", "ogv", "mp3", "m4a", "aac", "ogg", "opus", "wav",
    )

    private val initLock = Mutex()
    @Volatile private var ready = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** "Updating video website support…" etc, shown in Settings. */
    val engineStatus = MutableStateFlow("")

    /** A link that already points at a media file/stream (no page to extract from). */
    fun looksDirect(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        if (scheme != null && scheme != "http" && scheme != "https") return true
        val ext = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return ext in MEDIA_EXT
    }

    suspend fun ensureInit(ctx: Context) = withContext(Dispatchers.IO) {
        initLock.withLock {
            if (!ready) {
                YoutubeDL.getInstance().init(ctx.applicationContext) // first run unpacks Python (~few s)
                ready = true
            }
        }
    }

    /** Page link -> streams, remembered for a while so re-opening a video starts at once. */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, ResolvedMedia>>()
    private const val CACHE_MS = 2 * 3_600_000L // stream links usually expire after ~6 h

    suspend fun resolve(ctx: Context, url: String): ResolvedMedia {
        cache[url]?.let { (t, r) -> if (System.currentTimeMillis() - t < CACHE_MS) return r }
        return resolveFresh(ctx, url).also { cache[url] = System.currentTimeMillis() to it }
    }

    private suspend fun resolveFresh(ctx: Context, url: String): ResolvedMedia = withContext(Dispatchers.IO) {
        ensureInit(ctx)
        val req = YoutubeDLRequest(url)
            // Best video up to 1080p (avoid AV1: many phones can't hardware-decode it) + best audio,
            // falling back to a single combined stream.
            .addOption(
                "-f",
                "bv*[height<=1080][vcodec!^=av01]+ba[ext=m4a]/bv*[height<=1080][vcodec!^=av01]+ba/" +
                    "bv*[height<=1080]+ba/b[height<=1080]/b"
            )
            .addOption("--no-playlist")
            .addOption("--no-warnings")
            .addOption("--socket-timeout", "15")
        val info = YoutubeDL.getInstance().getInfo(req)
        val rf = info.requestedFormats
        val video: String?
        val audio: String?
        val headers: Map<String, String>
        if (rf != null && rf.size >= 2) {
            val v = rf.firstOrNull { it.vcodec != null && it.vcodec != "none" } ?: rf[0]
            val a = rf.firstOrNull { it !== v }
            video = v.url ?: v.manifestUrl
            audio = a?.url
            headers = v.httpHeaders ?: info.httpHeaders ?: emptyMap()
        } else {
            video = info.url ?: info.manifestUrl
            audio = null
            headers = info.httpHeaders ?: emptyMap()
        }
        ResolvedMedia(video ?: throw IllegalStateException("No playable video found on this page"), audio, headers, info.title)
    }

    /** Turns yt-dlp's stderr into one readable line. */
    fun friendlyError(e: Throwable): String {
        val msg = generateSequence(e) { it.cause }.mapNotNull { it.message }.joinToString("\n")
        val line = msg.lines().lastOrNull { "ERROR" in it } ?: msg.lines().firstOrNull { it.isNotBlank() } ?: "Unknown error"
        return line.replace(Regex("""^.*?ERROR:\s*(\[[^\]]+\]\s*)?([\w-]+:\s*)?"""), "").take(220)
    }

    /** Sites change often (especially YouTube), so keep yt-dlp fresh: at most once a day, in the background. */
    fun updateInBackground(ctx: Context) {
        val app = ctx.applicationContext
        val prefs = app.getSharedPreferences("arin_player", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong("ytdlp_updated", 0) < 24 * 3_600_000L) {
            scope.launch { runCatching { ensureInit(app) } } // warm up so the first link opens faster
            return
        }
        scope.launch {
            runCatching { updateNow(app) }
                .onSuccess { prefs.edit().putLong("ytdlp_updated", System.currentTimeMillis()).apply() }
        }
    }

    suspend fun updateNow(ctx: Context): String = withContext(Dispatchers.IO) {
        engineStatus.value = "Updating…"
        try {
            ensureInit(ctx)
            val r = YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel.STABLE)
            val v = version(ctx)
            engineStatus.value = if (r == YoutubeDL.UpdateStatus.DONE) "Updated to $v" else "Up to date ($v)"
            engineStatus.value
        } catch (e: Exception) {
            engineStatus.value = "Update failed: ${e.message ?: "no connection"}"
            throw e
        }
    }

    fun version(ctx: Context): String =
        runCatching { YoutubeDL.getInstance().versionName(ctx.applicationContext) }.getOrNull() ?: "built-in"
}
