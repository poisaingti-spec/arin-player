package com.arin.player.vr

import android.app.Activity
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.arin.player.data.Settings
import com.arin.player.data.VrFormat
import org.videolan.libvlc.MediaPlayer

/**
 * Full-screen stereo VR view. The libVLC player draws into the renderer's texture instead of the
 * normal screen; leaving VR hands the picture back.
 */
@Composable
fun VrScreen(
    player: MediaPlayer,
    title: String,
    posMs: Long,
    lenMs: Long,
    isPlaying: Boolean,
    subtitle: String,
    subtitlesOn: Boolean,
    onToggleSubtitles: () -> Unit,
    onExit: () -> Unit,
) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val settings = remember { Settings(ctx) }
    val audio = remember { ctx.getSystemService(AudioManager::class.java) }

    val format = remember {
        val f = settings.vrFormat
        if (f != VrFormat.AUTO) f else detectFormat(title)
    }
    val actions = remember {
        object : VrActions {
            override fun seekBy(deltaMs: Long) {
                val len = player.length
                val t = (player.time + deltaMs).coerceIn(0, if (len > 0) len - 1000 else Long.MAX_VALUE)
                player.setTime(t)
            }
            override fun seekTo(ms: Long) { player.setTime(ms) }
            override fun togglePlay() { if (player.isPlaying) player.pause() else player.play() }
            override fun toggleSubtitles() = onToggleSubtitles()
            override fun exit() = onExit()
        }
    }
    val renderer = remember {
        VrRenderer(
            ctx,
            VrConfig(format, settings.vrScreenScale, settings.vrIpdMm, settings.vrLensCorrection, settings.vrTiltSeek),
            actions,
        )
    }

    // Keep the renderer up to date with the player
    SideEffect {
        renderer.posMs = posMs
        renderer.lenMs = lenMs
        renderer.playing = isPlaying
        renderer.subtitle = if (subtitlesOn) subtitle else ""
        renderer.subtitlesOn = subtitlesOn
    }

    // Hand the video output over to the VR renderer
    renderer.onSurfaceReady = { st ->
        val vout = player.getVLCVout()
        if (vout.areViewsAttached()) vout.detachViews()
        vout.setVideoSurface(Surface(st), null)
        vout.setWindowSize(1920, 1080)
        vout.attachViews { _, w, h, vw, vh, sarNum, sarDen ->
            if (w > 0 && h > 0) {
                st.setDefaultBufferSize(w, h)
                vout.setWindowSize(w, h)
                val sar = if (sarNum > 0 && sarDen > 0) sarNum.toFloat() / sarDen else 1f
                val vw2 = if (vw > 0) vw else w
                val vh2 = if (vh > 0) vh else h
                renderer.videoAspect = vw2.toFloat() * sar / vh2
            }
        }
    }

    // Voice commands (optional)
    val voice = remember {
        if (settings.vrVoice) VoiceCommands(ctx) { cmd ->
            when (cmd) {
                is VoiceCommand.Seek -> { actions.seekBy(cmd.deltaMs); renderer.showOsd(seekLabel(cmd.deltaMs)) }
                VoiceCommand.Play -> { if (!player.isPlaying) player.play(); renderer.showOsd("▶  Playing") }
                VoiceCommand.Pause -> { if (player.isPlaying) player.pause(); renderer.showOsd("⏸  Paused") }
                VoiceCommand.Exit -> onExit()
                VoiceCommand.Recenter -> { renderer.recenter(); renderer.showOsd("◎  Recentred") }
                VoiceCommand.Subtitles -> onToggleSubtitles()
            }
        } else null
    }

    DisposableEffect(Unit) {
        val oldOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        renderer.start()
        voice?.start()

        // Headset button, Bluetooth remotes / gamepads, volume keys
        var lastRepeatSeek = 0L
        KeyRouter.handler = handler@{ e ->
            val seekKeys = settings.vrVolumeKeys
            fun seek(ms: Long): Boolean {
                if (e.action != KeyEvent.ACTION_DOWN) return true
                if (e.repeatCount > 0) { // held: keep seeking, a bit slower
                    if (e.eventTime - lastRepeatSeek < 400) return true
                }
                lastRepeatSeek = e.eventTime
                actions.seekBy(ms); renderer.showOsd(seekLabel(ms)); return true
            }
            when (e.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> if (seekKeys) seek(10_000) else false
                KeyEvent.KEYCODE_VOLUME_DOWN -> if (seekKeys) seek(-10_000) else false
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_BUTTON_R1 -> seek(10_000)
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_BUTTON_L1 -> seek(-10_000)
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_BUTTON_R2 -> seek(60_000)
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_BUTTON_L2 -> seek(-60_000)
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_SPACE,
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                    if (e.action == KeyEvent.ACTION_UP) renderer.onTap(); true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { if (e.action == KeyEvent.ACTION_UP) player.play(); true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (e.action == KeyEvent.ACTION_UP) player.pause(); true }
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE -> { if (e.action == KeyEvent.ACTION_UP) onExit(); true }
                KeyEvent.KEYCODE_BUTTON_Y -> { if (e.action == KeyEvent.ACTION_UP) renderer.recenter(); true }
                else -> false
            }
        }
        onDispose {
            KeyRouter.handler = null
            voice?.stop()
            renderer.release()
            runCatching { player.getVLCVout().detachViews() }
            activity?.requestedOrientation = oldOrientation
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c ->
            GLSurfaceView(c).apply {
                setEGLContextClientVersion(2)
                preserveEGLContextOnPause = true
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                keepScreenOn = true
                // Headset trigger (Cardboard button) taps the screen
                setOnTouchListener { v, ev ->
                    if (ev.action == MotionEvent.ACTION_UP) { v.performClick(); queueEvent { renderer.onTap() } }
                    true
                }
                addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                    val rot = if (Build.VERSION.SDK_INT >= 30) v.display?.rotation else
                        @Suppress("DEPRECATION") activity?.windowManager?.defaultDisplay?.rotation
                    renderer.displayRotation = rot ?: Surface.ROTATION_90
                }
            }
        },
    )
}

private fun seekLabel(ms: Long): String {
    val s = kotlin.math.abs(ms) / 1000
    val l = if (s >= 60) "${s / 60} min" else "$s s"
    return if (ms > 0) "⏩  +$l" else "⏪  −$l"
}

/** Guess the VR layout from the file name. */
fun detectFormat(title: String): VrFormat {
    val t = title.lowercase()
    return when {
        "180" in t && ("vr" in t || "sbs" in t || "lr" in t) -> VrFormat.HALF180_SBS
        "360" in t && (Regex("""\b(tb|ou|top.?bottom|3d)\b""").containsMatchIn(t)) -> VrFormat.SPHERE360_TB
        "360" in t -> VrFormat.SPHERE360
        Regex("""\b(h?sbs|side.?by.?side|3d.?lr|lr)\b""").containsMatchIn(t) -> VrFormat.SBS
        Regex("""\b(h?tab|h?ou|top.?bottom|over.?under)\b""").containsMatchIn(t) -> VrFormat.TB
        else -> VrFormat.FLAT
    }
}
