package com.arin.player.ui

import android.app.Activity
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionDisabled
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.arin.player.data.Settings
import com.arin.player.data.Srt
import com.arin.player.engine.SubtitleEngine
import kotlinx.coroutines.delay
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import com.arin.player.online.OnlineResolver
import com.arin.player.online.StreamBooster
import com.arin.player.online.PlaybackSource
import com.arin.player.online.PlayRequest
import com.arin.player.online.isNetworkUri
import kotlinx.coroutines.CancellationException

/**
 * Works out what to play first: a local file, a direct link, or (for web pages like YouTube)
 * the real stream URLs via yt-dlp. Then shows the player.
 */
@Composable
fun PlayerScreen(
    request: PlayRequest,
    settingsVersion: Int,
    settingsOpen: Boolean,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onFindInBrowser: (String) -> Unit,
) {
    val ctx = LocalContext.current
    var source by remember(request) { mutableStateOf<PlaybackSource?>(null) }
    var failure by remember(request) { mutableStateOf<String?>(null) }
    val u = request.uri
    val host = u.host?.removePrefix("www.")?.removePrefix("m.") ?: "the website"

    LaunchedEffect(request) {
        source = when {
            !isNetworkUri(u) -> PlaybackSource(u, null, emptyMap(), displayName(ctx, u), u.toString())
            request.direct || OnlineResolver.looksDirect(u) ->
                PlaybackSource(u, null, request.headers, Uri.decode(u.lastPathSegment ?: host), u.toString())
            else -> try {
                val r = OnlineResolver.resolve(ctx, u.toString())
                PlaybackSource(
                    Uri.parse(r.videoUrl), r.audioUrl?.let(Uri::parse), r.headers,
                    r.title ?: host, cacheId = u.buildUpon().clearQuery().build().toString() + "?" + (u.getQueryParameter("v") ?: ""),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = OnlineResolver.friendlyError(e)
                null
            }
        }
    }

    val s = source
    if (s != null) {
        // Online videos go through the booster: many parallel connections = full internet speed.
        val boosted = remember(s) { StreamBooster.boost(s) }
        PlayerContent(boosted, settingsVersion, settingsOpen, onBack, onSettings)
        return
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        IconButton(onClick = onBack, modifier = Modifier.padding(8.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
        }
        Column(
            Modifier.align(Alignment.Center).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val f = failure
            if (f == null) {
                CircularProgressIndicator(color = Accent)
                Text("Getting video from $host…", color = Color.White)
                Text("The first link can take a few seconds", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            } else {
                Text("Couldn't get the video from this link", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(f, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, textAlign = TextAlign.Center)
                Button(onClick = { onFindInBrowser(u.toString()) }) { Text("Find the video on the page") }
                TextButton(onClick = onBack) { Text("Go back") }
            }
        }
    }
}

@Composable
private fun PlayerContent(
    source: PlaybackSource,
    settingsVersion: Int,
    settingsOpen: Boolean,
    onBack: () -> Unit,
    onSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val settings = remember(settingsVersion) { Settings(ctx) }
    val subSize = remember(settingsVersion) { settings.subtitleSizeSp }
    val showLangTag = remember(settingsVersion) { settings.showLanguageTag }

    val libVlc = remember { LibVLC(ctx, arrayListOf("--audio-time-stretch", "--avcodec-skiploopfilter=0")) }
    val player = remember { MediaPlayer(libVlc) }
    val videoLayout = remember { VLCVideoLayout(ctx) }
    val engine = remember { SubtitleEngine(ctx, source, libVlc, scope) }
    val title = source.title

    var isPlaying by remember { mutableStateOf(false) }
    var posMs by remember { mutableLongStateOf(0L) }
    var lenMs by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var showControls by remember { mutableStateOf(true) }
    var subsOn by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var lastTouch by remember { mutableLongStateOf(0L) }

    val cues by engine.cues.collectAsState()
    val status by engine.status.collectAsState()
    val langs by engine.languages.collectAsState()
    val progress by engine.progress.collectAsState()
    val coverage by engine.coverage.collectAsState()
    val eta by engine.eta.collectAsState()

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { out ->
        out ?: return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.openOutputStream(out)?.use { it.write(Srt.build(cues).toByteArray()) } }
    }

    // ---- player setup / teardown
    DisposableEffect(source) {
        player.attachViews(videoLayout, null, false, false)
        val pfd = if (!source.isLocal) null
        else runCatching { ctx.contentResolver.openFileDescriptor(source.uri, "r") }.getOrNull()
        val media = when {
            !source.isLocal -> Media(libVlc, source.uri).apply {
                // Booster serves from the phone itself, so a short buffer is enough -> faster start.
                addOption(if (source.uri.host == "127.0.0.1") ":network-caching=1200" else ":network-caching=2500")
                addOption(":http-reconnect")
                // HD streams (YouTube etc.) come as separate video + audio
                source.audioUri?.let { addOption(":input-slave=$it") }
                source.headers.forEach { (k, v) ->
                    when (k.lowercase()) {
                        "user-agent" -> addOption(":http-user-agent=$v")
                        "referer" -> addOption(":http-referrer=$v")
                    }
                }
            }
            pfd != null -> Media(libVlc, pfd.fileDescriptor)
            else -> null
        }
        if (media == null) {
            error = "Can't open this file"
        } else {
            media.setHWDecoderEnabled(true, false)
            player.media = media
            media.release()
            player.play()
        }
        player.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Playing -> isPlaying = true
                MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> isPlaying = false
                MediaPlayer.Event.EndReached -> { isPlaying = false; showControls = true }
                MediaPlayer.Event.LengthChanged -> if (e.lengthChanged > 0) {
                    lenMs = e.lengthChanged
                }
                MediaPlayer.Event.EncounteredError -> error = "This video couldn't be played"
            }
        }
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            engine.stop()
            player.setEventListener(null)
            player.stop()
            player.detachViews()
            player.release()
            libVlc.release()
            runCatching { pfd?.close() }
        }
    }

    // ---- immersive fullscreen
    DisposableEffect(Unit) {
        val window = (ctx as? Activity)?.window
        val ctrl = window?.let { WindowCompat.getInsetsController(it, view) }
        ctrl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctrl?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { ctrl?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // ---- pause when app goes to background
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, ev -> if (ev == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(settingsOpen) { if (settingsOpen) player.pause() }

    // ---- clock: drives subtitles + tells the engine where we are
    LaunchedEffect(Unit) {
        while (true) {
            val t = player.time
            if (t >= 0) { posMs = t; engine.onPosition(t) }
            if (lenMs <= 0 && player.length > 0) lenMs = player.length
            delay(50)
        }
    }

    // ---- start subtitles once the video is actually playing, so they don't slow its start
    var everPlayed by remember { mutableStateOf(false) }
    LaunchedEffect(isPlaying) { if (isPlaying) everPlayed = true }
    LaunchedEffect(everPlayed, lenMs) {
        if (everPlayed && lenMs > 0) {
            if (!source.isLocal) delay(4_000) // let the video fill its buffer first
            engine.start(lenMs)
        }
    }

    // ---- auto-hide controls
    LaunchedEffect(showControls, isPlaying, lastTouch) {
        if (showControls && isPlaying) { delay(4_000); showControls = false }
    }

    val current = remember(posMs, cues) { cues.lastOrNull { posMs >= it.startMs && posMs <= it.endMs } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                showControls = !showControls; lastTouch = System.currentTimeMillis()
            }
    ) {
        AndroidView(factory = { videoLayout }, modifier = Modifier.fillMaxSize())

        // ---- subtitle
        if (subsOn && current != null) {
            val tag = if (showLangTag && current.lang.isNotBlank() && current.lang != "EN") "[${current.lang}] " else ""
            // English speech: words light up exactly as they're spoken. Translated lines appear whole,
            // timed to when that line is spoken.
            val styled = buildAnnotatedString {
                append(tag)
                if (current.words.isEmpty()) {
                    append(current.text)
                } else {
                    current.words.forEachIndexed { i, w ->
                        if (i > 0) append(" ")
                        val color = when {
                            posMs >= w.startMs && posMs < w.endMs + 120 -> Color(0xFFFFE066) // being spoken
                            posMs >= w.startMs -> Color.White                                // already said
                            else -> Color.White.copy(alpha = 0.55f)                         // coming up
                        }
                        withStyle(SpanStyle(color = color)) { append(w.text) }
                    }
                }
            }
            Text(
                styled,
                color = Color.White,
                fontSize = subSize.sp,
                textAlign = TextAlign.Center,
                style = TextStyle(shadow = Shadow(Color.Black, blurRadius = 6f)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 24.dp, end = 24.dp, bottom = if (showControls) 120.dp else 36.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }

        error?.let {
            Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp))
        }

        // While subtitles are still being made, keep a small progress badge visible.
        if (!showControls && coverage.isNotEmpty() && progress < 1f) {
            Row(
                Modifier.align(Alignment.TopEnd).padding(12.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(progress = { progress }, color = Accent, strokeWidth = 2.dp,
                    trackColor = Color.White.copy(alpha = 0.2f), modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Subtitles ${(progress * 100).toInt()}%", color = Color.White, fontSize = 12.sp)
            }
        }

        // ---- controls
        AnimatedVisibility(showControls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                // top bar
                Row(
                    Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                    Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = { subsOn = !subsOn }) {
                        Icon(if (subsOn) Icons.Filled.ClosedCaption else Icons.Filled.ClosedCaptionDisabled, "Subtitles", tint = Color.White)
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "More", tint = Color.White) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Export subtitles (.srt)") },
                                enabled = cues.isNotEmpty(),
                                onClick = { menuOpen = false; exporter.launch(title.substringBeforeLast('.') + ".en.srt") },
                            )
                            DropdownMenuItem(text = { Text("Settings") }, onClick = { menuOpen = false; onSettings() })
                        }
                    }
                }

                // centre buttons
                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { player.setTime((posMs - 10_000).coerceAtLeast(0)); lastTouch = System.currentTimeMillis() }) {
                        Icon(Icons.Filled.Replay10, "Back 10s", tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                    IconButton(
                        onClick = { if (player.isPlaying) player.pause() else player.play(); lastTouch = System.currentTimeMillis() },
                        modifier = Modifier.size(72.dp).background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(36.dp)),
                    ) {
                        Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/Pause", tint = Color.White, modifier = Modifier.size(48.dp))
                    }
                    IconButton(onClick = { player.setTime(posMs + 10_000); lastTouch = System.currentTimeMillis() }) {
                        Icon(Icons.Filled.Forward10, "Forward 10s", tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                }

                // bottom bar
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    // Subtitle progress for the whole video
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val pct = (progress * 100).toInt()
                        Text(
                            if (coverage.isNotEmpty() && pct >= 100) "✓ Subtitles ready for the whole video"
                            else "Subtitles: $pct% of video" + (if (eta.isNotBlank()) " · $eta" else ""),
                            color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        if (langs.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Text(langs.joinToString(" · ") + " → EN", color = Accent,
                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    // Coverage bar: purple = parts of the video that already have subtitles,
                    // white line = where you are.
                    val posFrac = if (lenMs > 0) (posMs.toFloat() / lenMs).coerceIn(0f, 1f) else 0f
                    Canvas(Modifier.fillMaxWidth().padding(top = 6.dp).height(8.dp)) {
                        val n = coverage.size
                        drawRoundRect(Color.White.copy(alpha = 0.15f), cornerRadius = CornerRadius(4f, 4f))
                        if (n > 0) {
                            val w = size.width / n
                            var i = 0
                            while (i < n) {
                                if (coverage[i]) {
                                    var j = i
                                    while (j + 1 < n && coverage[j + 1]) j++
                                    drawRect(Accent, topLeft = Offset(i * w, 0f), size = Size((j - i + 1) * w, size.height))
                                    i = j + 1
                                } else i++
                            }
                        }
                        drawRect(Color.White, topLeft = Offset(posFrac * size.width - 2f, -4f), size = Size(4f, size.height + 8f))
                    }
                    Text(status, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    val sliderMax = lenMs.coerceAtLeast(1).toFloat()
                    Slider(
                        value = (dragging ?: posMs.toFloat()).coerceIn(0f, sliderMax),
                        valueRange = 0f..sliderMax,
                        onValueChange = { dragging = it; lastTouch = System.currentTimeMillis() },
                        onValueChangeFinished = { dragging?.let { player.setTime(it.toLong()) }; dragging = null },
                        colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
                    )
                    Row {
                        Text(fmt((dragging?.toLong()) ?: posMs), color = Color.White, fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        Text(fmt(lenMs), color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

private fun displayName(ctx: android.content.Context, uri: Uri): String =
    runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "Video"
