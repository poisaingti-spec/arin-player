package com.arin.player.vr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Surface
import com.arin.player.data.VrFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.Locale
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/** What the VR view asks the player to do. Always called on the main thread. */
interface VrActions {
    fun seekBy(deltaMs: Long)
    fun seekTo(ms: Long)
    fun togglePlay()
    fun toggleSubtitles()
    fun exit()
}

data class VrConfig(
    val format: VrFormat,
    val screenScale: Float,
    val ipdMm: Float,
    val lensCorrection: Boolean,
    val tiltSeek: Boolean,
    val screenPitch: Float = 0f,
)

/**
 * Stereo VR renderer for phone headsets (Cardboard style).
 *
 *  - The video arrives as a camera-style external texture (libVLC renders into our SurfaceTexture).
 *  - Each eye gets its own view (with eye separation) and lens pre-distortion.
 *  - Head tracking from the rotation sensor; the scene is centred on where you look when VR starts.
 *  - Hands-free controls: look at a button in the control panel below the screen and hold your gaze,
 *    or tilt your head left/right to rewind/forward, or press the headset button / any remote.
 */
class VrRenderer(
    context: Context,
    initial: VrConfig,
    private val actions: VrActions,
) : GLSurfaceView.Renderer, SensorEventListener {

    /** Current settings; changed from inside the headset via the ⚙ Screen page. */
    @Volatile var config: VrConfig = initial
        private set
    /** Called (main thread) after the user changes a setting in the headset, to save it. */
    var onConfigChanged: ((VrConfig) -> Unit)? = null

    private fun update(c: VrConfig, osd: String) {
        config = c
        showOsd(osd)
        main.post { onConfigChanged?.invoke(c) }
    }

    // ---- state pushed in from the player (any thread)
    @Volatile var posMs = 0L
    @Volatile var lenMs = 0L
    @Volatile var playing = false
    @Volatile var subtitle = ""
    @Volatile var subtitlesOn = true
    @Volatile var videoAspect = 16f / 9f
    /** Called (main thread) whenever a new SurfaceTexture is ready for the video. */
    var onSurfaceReady: ((SurfaceTexture) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val density = context.resources.displayMetrics.density

    // ---- head tracking
    private val rotVec = FloatArray(5)
    @Volatile private var hasRotation = false
    private val rotMatrix = FloatArray(16)
    private val remapped = FloatArray(16)
    private val headView = FloatArray(16)
    private var recenterYawDeg = 0f
    @Volatile private var recenterRequested = true
    @Volatile var displayRotation = Surface.ROTATION_90

    // ---- GL objects
    private var oesTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    @Volatile private var frameAvailable = false
    private val stMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val flipY = FloatArray(16).also { Matrix.setIdentityM(it, 0); it[5] = -1f; it[13] = 1f }
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private var progOes = 0
    private var prog2d = 0
    private var progReticle = 0
    private var progDistort = 0

    private lateinit var quad: Mesh
    private lateinit var sphere: Mesh
    private lateinit var hemisphere: Mesh
    private lateinit var fullscreen: FloatBuffer

    private var width = 1
    private var height = 1
    private var eyeW = 1
    private var eyeH = 1
    private val fbo = IntArray(2)
    private val fboTex = IntArray(2)

    // ---- panels (text drawn into bitmaps, shown as textured quads)
    private val subtitlePanel = TextPanel(1600, 240)
    private val controlPanel = TextPanel(1200, 420)
    private val osdPanel = TextPanel(700, 130)
    private var lastSubtitle: String? = null
    private var lastControlKey = ""
    private var osdText = ""
    private var osdUntil = 0L

    // ---- control panel appears only when you look down
    private var ctrlAlpha = 0f
    private var page = Page.MAIN

    // ---- gaze interaction
    private var hovered: Btn? = null
    private var hoverStart = 0L
    private var hoverSeekFrac = 0f
    private var lastClick = 0L
    private var reticleProgress = 0f

    // ---- head-tilt seeking
    private var tiltDir = 0
    private var tiltSince = 0L
    private var tiltRepeats = 0
    private var lastTiltSeek = 0L

    private val format: VrFormat get() = config.format

    // =============================================================================================
    // Lifecycle
    // =============================================================================================

    fun start() {
        osdText = "Look down for controls"
        osdUntil = SystemClock.uptimeMillis() + 4000
        val s = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (s != null) sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_FASTEST)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    fun recenter() { recenterRequested = true }

    fun showOsd(text: String) {
        osdText = text
        osdUntil = SystemClock.uptimeMillis() + 1300
    }

    override fun onSensorChanged(event: SensorEvent) {
        synchronized(rotVec) {
            val n = min(event.values.size, 5)
            for (i in 0 until n) rotVec[i] = event.values[i]
            if (n < 4) rotVec[3] = 0f
            hasRotation = true
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // =============================================================================================
    // GL
    // =============================================================================================

    override fun onSurfaceCreated(gl: GL10?, cfg: EGLConfig?) {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        oesTex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(oesTex)
        st.setDefaultBufferSize(1920, 1080)
        st.setOnFrameAvailableListener { frameAvailable = true }
        surfaceTexture?.release()
        surfaceTexture = st
        main.post { onSurfaceReady?.invoke(st) }

        progOes = program(VS_TEX, FS_OES)
        prog2d = program(VS_TEX, FS_2D)
        progReticle = program(VS_TEX, FS_RETICLE)
        progDistort = program(VS_DISTORT, FS_DISTORT)

        quad = Mesh.quad()
        sphere = Mesh.sphere(-180f, 180f, 20f)
        hemisphere = Mesh.sphere(-90f, 90f, 20f)
        fullscreen = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))

        subtitlePanel.tex = 0; controlPanel.tex = 0; osdPanel.tex = 0
        lastSubtitle = null; lastControlKey = ""
        GLES20.glClearColor(0f, 0f, 0f, 1f)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        eyeW = w / 2; eyeH = h
        if (fboTex[0] != 0) {
            GLES20.glDeleteTextures(2, fboTex, 0)
            GLES20.glDeleteFramebuffers(2, fbo, 0)
        }
        GLES20.glGenFramebuffers(2, fbo, 0)
        GLES20.glGenTextures(2, fboTex, 0)
        for (i in 0..1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[i])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, eyeW, eyeH, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex[i], 0)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (frameAvailable) {
            frameAvailable = false
            runCatching {
                surfaceTexture?.updateTexImage()
                surfaceTexture?.getTransformMatrix(stMatrix)
            }
        }
        updateHead()
        val now = SystemClock.uptimeMillis()
        updateGaze(now)
        if (config.tiltSeek) updateTilt(now)
        refreshPanels(now)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        val lens = config.lensCorrection
        val fovY = if (lens) 95f else 80f
        val proj = FloatArray(16)
        Matrix.perspectiveM(proj, 0, fovY, eyeW.toFloat() / eyeH, 0.05f, 100f)

        for (eye in 0..1) {
            if (lens) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[eye])
                GLES20.glViewport(0, 0, eyeW, eyeH)
            } else {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(eye * eyeW, 0, eyeW, eyeH)
            }
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            drawEye(eye, proj, now)
        }

        if (lens) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glDisable(GLES20.GL_BLEND)
            for (eye in 0..1) {
                GLES20.glViewport(eye * eyeW, 0, eyeW, eyeH)
                GLES20.glUseProgram(progDistort)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[eye])
                GLES20.glUniform1i(GLES20.glGetUniformLocation(progDistort, "uTex"), 0)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(progDistort, "uK1"), 0.22f)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(progDistort, "uK2"), 0.24f)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(progDistort, "uAspect"), eyeW.toFloat() / eyeH)
                val a = GLES20.glGetAttribLocation(progDistort, "aPos")
                fullscreen.position(0)
                GLES20.glEnableVertexAttribArray(a)
                GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 8, fullscreen)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GLES20.glDisableVertexAttribArray(a)
            }
        }
    }

    // =============================================================================================
    // Scene
    // =============================================================================================

    private val isSphere get() = format == VrFormat.SPHERE360 || format == VrFormat.SPHERE360_TB || format == VrFormat.HALF180_SBS

    private fun drawEye(eye: Int, proj: FloatArray, now: Long) {
        // Eye view = eye offset * head rotation. Spheres are "at infinity", so no eye offset there.
        val view = FloatArray(16)
        val ipd = config.ipdMm / 1000f
        if (isSphere) System.arraycopy(headView, 0, view, 0, 16)
        else {
            val t = FloatArray(16)
            Matrix.setIdentityM(t, 0)
            Matrix.translateM(t, 0, if (eye == 0) ipd / 2 else -ipd / 2, 0f, 0f)
            Matrix.multiplyMM(view, 0, t, 0, headView, 0)
        }
        val vp = FloatArray(16)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        // Which part of the video frame this eye sees
        var uOff = 0f; var vOff = 0f; var uScale = 1f; var vScale = 1f
        when (format) {
            VrFormat.SBS, VrFormat.HALF180_SBS -> { uScale = 0.5f; uOff = if (eye == 0) 0f else 0.5f }
            VrFormat.TB, VrFormat.SPHERE360_TB -> { vScale = 0.5f; vOff = if (eye == 0) 0.5f else 0f }
            else -> {}
        }

        when (format) {
            VrFormat.SPHERE360, VrFormat.SPHERE360_TB -> drawVideo(sphere, vp, identity, uOff, vOff, uScale, vScale)
            VrFormat.HALF180_SBS -> drawVideo(hemisphere, vp, identity, uOff, vOff, uScale, vScale)
            else -> {
                val (sw, sh) = screenSize()
                val m = panelModel(config.screenPitch, 0f, SCREEN_DIST, sw, sh)
                drawVideo(quad, vp, m, uOff, vOff, uScale, vScale)
            }
        }

        // Subtitles: just under the cinema screen, or a bit below eye level for 360 videos.
        if (subtitlesOn && subtitle.isNotBlank()) {
            if (isSphere) {
                draw2d(subtitlePanel.tex, quad, vp, panelModel(-16f, 0f, 2.4f, 2.0f, 2.0f * subtitlePanel.h / subtitlePanel.w), 1f)
            } else {
                val (sw, sh) = screenSize()
                val ph = sw * subtitlePanel.h / subtitlePanel.w
                val m = FloatArray(16)
                Matrix.setIdentityM(m, 0)
                Matrix.rotateM(m, 0, config.screenPitch, 1f, 0f, 0f)
                Matrix.translateM(m, 0, 0f, -sh / 2 - ph / 2 - 0.04f, -SCREEN_DIST + 0.01f)
                Matrix.scaleM(m, 0, sw, ph, 1f)
                draw2d(subtitlePanel.tex, quad, vp, m, 1f)
            }
        }

        // Control panel: only there when you look down.
        if (ctrlAlpha > 0.02f) {
            draw2d(controlPanel.tex, quad, vp, panelModel(CTRL_PITCH, 0f, CTRL_DIST, CTRL_W, CTRL_W * controlPanel.h / controlPanel.w),
                ctrlAlpha)
        }

        // Seek / status messages in front of you
        if (now < osdUntil) {
            draw2d(osdPanel.tex, quad, vp, panelModel(-6f, 0f, 1.9f, 0.7f, 0.7f * osdPanel.h / osdPanel.w), 1f)
        }

        // Gaze dot (fixed in front of the eyes), only while the controls are showing
        if (ctrlAlpha <= 0.02f) return
        val rm = FloatArray(16)
        Matrix.setIdentityM(rm, 0)
        Matrix.translateM(rm, 0, if (isSphere) 0f else (if (eye == 0) ipd / 2 else -ipd / 2) * 0f, 0f, -1.5f)
        val s = if (hovered != null) 0.045f else 0.03f
        Matrix.scaleM(rm, 0, s, s, 1f)
        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvp, 0, proj, 0, rm, 0)
        GLES20.glUseProgram(progReticle)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(progReticle, "uMVP"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(progReticle, "uST"), 1, false, identity, 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progReticle, "uUvOff"), 0f, 0f)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progReticle, "uUvScale"), 1f, 1f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(progReticle, "uProgress"), reticleProgress)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(progReticle, "uActive"), if (hovered != null) 1f else 0f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(progReticle, "uAlpha"), ctrlAlpha)
        quad.draw(progReticle)
    }

    /** Screen width/height in metres at SCREEN_DIST. */
    private fun screenSize(): Pair<Float, Float> {
        val w = 3.2f * config.screenScale
        var a = videoAspect
        // Full-width 3D frames hold two eyes; half-width ("half SBS") frames are squeezed.
        if (format == VrFormat.SBS && a >= 3f) a /= 2f
        if (format == VrFormat.TB && a <= 1.2f) a *= 2f
        return w to w / a.coerceIn(0.5f, 4f)
    }

    /** Model matrix for a panel facing the viewer at [pitchDeg] (negative = below) and [yawDeg]. */
    private fun panelModel(pitchDeg: Float, yawDeg: Float, dist: Float, w: Float, h: Float): FloatArray {
        val m = FloatArray(16)
        Matrix.setIdentityM(m, 0)
        Matrix.rotateM(m, 0, yawDeg, 0f, 1f, 0f)
        Matrix.rotateM(m, 0, pitchDeg, 1f, 0f, 0f)
        Matrix.translateM(m, 0, 0f, 0f, -dist)
        Matrix.scaleM(m, 0, w, h, 1f)
        return m
    }

    private fun drawVideo(mesh: Mesh, vp: FloatArray, model: FloatArray, uOff: Float, vOff: Float, uScale: Float, vScale: Float) {
        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES20.glUseProgram(progOes)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progOes, "uTex"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(progOes, "uMVP"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(progOes, "uST"), 1, false, stMatrix, 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progOes, "uUvOff"), uOff, vOff)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progOes, "uUvScale"), uScale, vScale)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(progOes, "uAlpha"), 1f)
        mesh.draw(progOes)
    }

    private fun draw2d(tex: Int, mesh: Mesh, vp: FloatArray, model: FloatArray, alpha: Float) {
        if (tex == 0) return
        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES20.glUseProgram(prog2d)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog2d, "uTex"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog2d, "uMVP"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog2d, "uST"), 1, false, flipY, 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(prog2d, "uUvOff"), 0f, 0f)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(prog2d, "uUvScale"), 1f, 1f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog2d, "uAlpha"), alpha)
        mesh.draw(prog2d)
    }

    // =============================================================================================
    // Head tracking
    // =============================================================================================

    private fun updateHead() {
        if (!hasRotation) {
            Matrix.setIdentityM(headView, 0)
            return
        }
        synchronized(rotVec) { SensorManager.getRotationMatrixFromVector(rotMatrix, rotVec) }
        when (displayRotation) {
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(rotMatrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remapped)
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(rotMatrix, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, remapped)
            Surface.ROTATION_0 -> System.arraycopy(rotMatrix, 0, remapped, 0, 16)
            else -> SensorManager.remapCoordinateSystem(rotMatrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remapped)
        }
        // remapped (row-major, device->world) read as column-major = world->device.
        // Convert our GL world (y up, -z forward) to Android's world (z up, y north).
        val raw = FloatArray(16)
        Matrix.multiplyMM(raw, 0, remapped, 0, GL_TO_ANDROID, 0)

        if (recenterRequested) {
            recenterRequested = false
            val fx = -raw[2]; val fz = -raw[10]
            recenterYawDeg = Math.toDegrees(atan2(fx.toDouble(), (-fz).toDouble())).toFloat()
        }
        val rot = FloatArray(16)
        Matrix.setRotateM(rot, 0, -recenterYawDeg, 0f, 1f, 0f)
        Matrix.multiplyMM(headView, 0, raw, 0, rot, 0)
    }

    /** Direction you're looking, in scene coordinates. */
    private fun forward(): FloatArray = floatArrayOf(-headView[2], -headView[6], -headView[10])

    // =============================================================================================
    // Gaze buttons
    // =============================================================================================

    private enum class Page { MAIN, SCREEN }

    private enum class Btn {
        BACK60, BACK10, PLAY, FWD10, FWD60, SEEKBAR, SETTINGS, SUBS, EXIT,
        SIZE_MINUS, SIZE_PLUS, UP, DOWN, TYPE, EYE_MINUS, EYE_PLUS, LENS, TILT, RECENTER, RESET, DONE,
    }

    private val mainButtons = linkedMapOf(
        Btn.BACK60 to RectF(20f, 20f, 240f, 170f),
        Btn.BACK10 to RectF(255f, 20f, 475f, 170f),
        Btn.PLAY to RectF(490f, 20f, 710f, 170f),
        Btn.FWD10 to RectF(725f, 20f, 945f, 170f),
        Btn.FWD60 to RectF(960f, 20f, 1180f, 170f),
        Btn.SEEKBAR to RectF(30f, 185f, 1170f, 265f),
        Btn.SETTINGS to RectF(470f, 285f, 700f, 400f),
        Btn.SUBS to RectF(715f, 285f, 945f, 400f),
        Btn.EXIT to RectF(960f, 285f, 1180f, 400f),
    )

    /** ⚙ Screen page: adjust everything from inside the headset. */
    private val screenButtons = linkedMapOf(
        Btn.SIZE_MINUS to RectF(20f, 20f, 240f, 145f),
        Btn.SIZE_PLUS to RectF(255f, 20f, 475f, 145f),
        Btn.UP to RectF(490f, 20f, 710f, 145f),
        Btn.DOWN to RectF(725f, 20f, 945f, 145f),
        Btn.TYPE to RectF(960f, 20f, 1180f, 145f),
        Btn.EYE_MINUS to RectF(20f, 160f, 240f, 280f),
        Btn.EYE_PLUS to RectF(255f, 160f, 475f, 280f),
        Btn.LENS to RectF(490f, 160f, 710f, 280f),
        Btn.TILT to RectF(725f, 160f, 945f, 280f),
        Btn.RECENTER to RectF(960f, 160f, 1180f, 280f),
        Btn.RESET to RectF(725f, 295f, 945f, 400f),
        Btn.DONE to RectF(960f, 295f, 1180f, 400f),
    )

    private val buttons get() = if (page == Page.MAIN) mainButtons else screenButtons
    private val repeating = setOf(
        Btn.BACK10, Btn.FWD10, Btn.BACK60, Btn.FWD60,
        Btn.SIZE_MINUS, Btn.SIZE_PLUS, Btn.UP, Btn.DOWN, Btn.EYE_MINUS, Btn.EYE_PLUS,
    )

    private fun updateGaze(now: Long) {
        if (!hasRotation) return
        // Show the controls only while looking down (or while using them).
        val pitchDeg = Math.toDegrees(asin(forward()[1].coerceIn(-1f, 1f).toDouble())).toFloat()
        val want = pitchDeg < SHOW_BELOW_DEG || (hovered != null && pitchDeg < SHOW_BELOW_DEG + 12f)
        ctrlAlpha += ((if (want) 1f else 0f) - ctrlAlpha) * 0.18f
        if (!want && ctrlAlpha < 0.05f && page != Page.MAIN) page = Page.MAIN
        if (ctrlAlpha < 0.6f) {
            hovered = null; reticleProgress = 0f
            return
        }
        val m = panelModel(CTRL_PITCH, 0f, CTRL_DIST, CTRL_W, CTRL_W * controlPanel.h / controlPanel.w)
        val inv = FloatArray(16)
        if (!Matrix.invertM(inv, 0, m, 0)) return
        val f = forward()
        val o = FloatArray(4); val d = FloatArray(4)
        Matrix.multiplyMV(o, 0, inv, 0, floatArrayOf(0f, 0f, 0f, 1f), 0)
        Matrix.multiplyMV(d, 0, inv, 0, floatArrayOf(f[0], f[1], f[2], 0f), 0)
        var hit: Btn? = null
        var frac = 0f
        if (abs(d[2]) > 1e-5f) {
            val t = -o[2] / d[2]
            if (t > 0) {
                val lx = o[0] + t * d[0]
                val ly = o[1] + t * d[1]
                if (lx in -0.5f..0.5f && ly in -0.5f..0.5f) {
                    val px = (lx + 0.5f) * controlPanel.w
                    val py = (0.5f - ly) * controlPanel.h
                    for ((b, r) in buttons) if (r.contains(px, py)) { hit = b; break }
                    if (hit == Btn.SEEKBAR) frac = ((px - 40f) / 1120f).coerceIn(0f, 1f)
                }
            }
        }
        if (hit != hovered) {
            hovered = hit
            hoverStart = now
        }
        hoverSeekFrac = frac
        val dwell = if (hit == Btn.SEEKBAR) 1400L else 1100L
        reticleProgress = if (hit != null) ((now - hoverStart).toFloat() / dwell).coerceIn(0f, 1f) else 0f
        if (hit != null && now - hoverStart >= dwell && now - lastClick > 600) {
            click(hit, frac)
            // seek buttons repeat while you keep looking; others need you to look away first
            hoverStart = if (hit in repeating) now - dwell / 3 else Long.MAX_VALUE / 2
        }
    }

    private fun click(b: Btn, frac: Float) {
        lastClick = SystemClock.uptimeMillis()
        when (b) {
            Btn.BACK60 -> seek(-60_000)
            Btn.BACK10 -> seek(-10_000)
            Btn.FWD10 -> seek(10_000)
            Btn.FWD60 -> seek(60_000)
            Btn.PLAY -> { showOsd(if (playing) "⏸  Paused" else "▶  Playing"); main.post { actions.togglePlay() } }
            Btn.SEEKBAR -> {
                val target = (frac * lenMs).toLong()
                showOsd("⇥  ${fmt(target)}")
                main.post { actions.seekTo(target) }
            }
            Btn.SUBS -> { showOsd(if (subtitlesOn) "Subtitles off" else "Subtitles on"); main.post { actions.toggleSubtitles() } }
            Btn.EXIT -> main.post { actions.exit() }
            Btn.SETTINGS -> { page = Page.SCREEN; hovered = null }
            Btn.DONE -> { page = Page.MAIN; hovered = null }
            Btn.RECENTER -> { recenter(); showOsd("◎  Recentred") }
            Btn.SIZE_MINUS -> config.copy(screenScale = (config.screenScale - 0.1f).coerceAtLeast(0.4f)).let { update(it, "Screen ${(it.screenScale * 100).toInt()}%") }
            Btn.SIZE_PLUS -> config.copy(screenScale = (config.screenScale + 0.1f).coerceAtMost(2.2f)).let { update(it, "Screen ${(it.screenScale * 100).toInt()}%") }
            Btn.UP -> config.copy(screenPitch = (config.screenPitch + 3f).coerceAtMost(40f)).let { update(it, "Screen ▲ ${it.screenPitch.toInt()}°") }
            Btn.DOWN -> config.copy(screenPitch = (config.screenPitch - 3f).coerceAtLeast(-40f)).let { update(it, "Screen ▼ ${it.screenPitch.toInt()}°") }
            Btn.EYE_MINUS -> config.copy(ipdMm = (config.ipdMm - 1f).coerceAtLeast(52f)).let { update(it, "Eye distance ${it.ipdMm.toInt()} mm") }
            Btn.EYE_PLUS -> config.copy(ipdMm = (config.ipdMm + 1f).coerceAtMost(78f)).let { update(it, "Eye distance ${it.ipdMm.toInt()} mm") }
            Btn.LENS -> config.copy(lensCorrection = !config.lensCorrection).let { update(it, if (it.lensCorrection) "Lens correction on" else "Lens correction off") }
            Btn.TILT -> config.copy(tiltSeek = !config.tiltSeek).let { update(it, if (it.tiltSeek) "Tilt to skip on" else "Tilt to skip off") }
            Btn.TYPE -> {
                val all = VrFormat.entries.filter { it != VrFormat.AUTO }
                val next = all[(all.indexOf(config.format) + 1) % all.size]
                update(config.copy(format = next), shortName(next))
            }
            Btn.RESET -> update(config.copy(screenScale = 1f, screenPitch = 0f, ipdMm = 63f, lensCorrection = true), "Reset to default")
        }
    }

    private fun shortName(f: VrFormat) = when (f) {
        VrFormat.FLAT -> "2D cinema"
        VrFormat.SBS -> "3D side-by-side"
        VrFormat.TB -> "3D top-bottom"
        VrFormat.SPHERE360 -> "360°"
        VrFormat.SPHERE360_TB -> "360° 3D"
        VrFormat.HALF180_SBS -> "VR180"
        VrFormat.AUTO -> "Auto"
    }

    private fun seek(delta: Long) {
        val secs = abs(delta) / 1000
        val label = if (secs >= 60) "${secs / 60} min" else "$secs s"
        showOsd(if (delta > 0) "⏩  +$label" else "⏪  −$label")
        main.post { actions.seekBy(delta) }
    }

    /** Headset button / screen tap: press the button you're looking at, otherwise play/pause. */
    fun onTap() {
        val h = hovered
        if (h != null) click(h, hoverSeekFrac)
        else { showOsd(if (playing) "⏸  Paused" else "▶  Playing"); main.post { actions.togglePlay() } }
    }

    // =============================================================================================
    // Head tilt: tilt right = forward, tilt left = rewind (hold the tilt to keep going, faster)
    // =============================================================================================

    private fun updateTilt(now: Long) {
        if (!hasRotation) return
        // Right vector of the head in scene coordinates is the first row of the view matrix.
        val rightY = headView[4]
        val rollDeg = Math.toDegrees(asin((-rightY).coerceIn(-1f, 1f).toDouble())).toFloat()
        val dir = when {
            rollDeg > TILT_DEG -> 1
            rollDeg < -TILT_DEG -> -1
            abs(rollDeg) < TILT_DEG - 8 -> 0
            else -> tiltDir
        }
        if (dir != tiltDir) {
            tiltDir = dir; tiltSince = now; tiltRepeats = 0
            return
        }
        if (dir == 0) return
        val firstDelay = 350L
        val repeatDelay = 750L
        val due = if (tiltRepeats == 0) now - tiltSince >= firstDelay else now - lastTiltSeek >= repeatDelay
        if (due) {
            val step = if (tiltRepeats >= 3) 30_000L else 10_000L
            seek(dir * step)
            tiltRepeats++
            lastTiltSeek = now
        }
    }

    // =============================================================================================
    // Panels
    // =============================================================================================

    private fun refreshPanels(now: Long) {
        val sub = subtitle
        if (sub != lastSubtitle || subtitlePanel.tex == 0) {
            lastSubtitle = sub
            subtitlePanel.draw { c, w, h ->
                if (sub.isBlank()) return@draw
                val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE; textSize = 64f; typeface = Typeface.DEFAULT_BOLD
                }
                val layout = StaticLayout.Builder.obtain(sub, 0, sub.length, tp, w - 80)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(2).build()
                val top = (h - layout.height) / 2f
                val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 0, 0, 0) }
                var minL = w.toFloat(); var maxR = 0f
                for (i in 0 until layout.lineCount) { minL = min(minL, layout.getLineLeft(i)); maxR = max(maxR, layout.getLineRight(i)) }
                c.drawRoundRect(RectF(40 + minL - 24, top - 12, 40 + maxR + 24, top + layout.height + 12), 18f, 18f, bg)
                c.save(); c.translate(40f, top); layout.draw(c); c.restore()
            }
            subtitlePanel.upload()
        }

        val key = "${posMs / 1000}|$lenMs|$playing|$hovered|$subtitlesOn|${(hoverSeekFrac * 200).toInt()}|$page|$config"
        if (key != lastControlKey || controlPanel.tex == 0) {
            lastControlKey = key
            drawControls()
            controlPanel.upload()
        }

        if (now < osdUntil && osdPanel.label != osdText) {
            osdPanel.label = osdText
            osdPanel.draw { c, w, h ->
                val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 20, 16, 34) }
                c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 40f, 40f, bg)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE; textSize = 64f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
                }
                c.drawText(osdText, w / 2f, h / 2f + 22f, p)
            }
            osdPanel.upload()
        }
    }

    private fun drawControls() {
        if (page == Page.SCREEN) { drawScreenPage(); return }
        controlPanel.draw { c, w, h ->
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(215, 18, 15, 30) }
            c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 36f, 36f, bg)
            val btnBg = Paint(Paint.ANTI_ALIAS_FLAG)
            val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = 46f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
            }
            val labels = mapOf(
                Btn.BACK60 to "⏪ 1 min", Btn.BACK10 to "⏪ 10 s",
                Btn.PLAY to if (playing) "⏸ Pause" else "▶ Play",
                Btn.FWD10 to "10 s ⏩", Btn.FWD60 to "1 min ⏩",
                Btn.SETTINGS to "⚙ Screen", Btn.SUBS to if (subtitlesOn) "CC on" else "CC off", Btn.EXIT to "✕ Exit VR",
            )
            for ((b, r) in buttons) {
                if (b == Btn.SEEKBAR) continue
                btnBg.color = if (b == hovered) Color.rgb(139, 92, 246) else Color.argb(60, 255, 255, 255)
                c.drawRoundRect(r, 26f, 26f, btnBg)
                txt.textSize = if (r.height() > 140) 46f else 40f
                c.drawText(labels[b] ?: "", r.centerX(), r.centerY() + txt.textSize / 3, txt)
            }
            // seek bar
            val bar = RectF(40f, 215f, 1160f, 235f)
            btnBg.color = Color.argb(70, 255, 255, 255)
            c.drawRoundRect(bar, 10f, 10f, btnBg)
            val frac = if (lenMs > 0) (posMs.toFloat() / lenMs).coerceIn(0f, 1f) else 0f
            btnBg.color = Color.rgb(139, 92, 246)
            c.drawRoundRect(RectF(bar.left, bar.top, bar.left + bar.width() * frac, bar.bottom), 10f, 10f, btnBg)
            btnBg.color = Color.WHITE
            c.drawCircle(bar.left + bar.width() * frac, bar.centerY(), 18f, btnBg)
            if (hovered == Btn.SEEKBAR && lenMs > 0) {
                val x = bar.left + bar.width() * hoverSeekFrac
                btnBg.color = Color.rgb(255, 224, 102)
                c.drawRect(x - 3f, bar.top - 22f, x + 3f, bar.bottom + 22f, btnBg)
                val t = Paint(txt).apply { textSize = 36f; color = Color.rgb(255, 224, 102) }
                c.drawText(fmt((hoverSeekFrac * lenMs).toLong()), x.coerceIn(90f, 1110f), bar.top - 30f, t)
            }
            val time = Paint(txt).apply { textAlign = Paint.Align.LEFT; textSize = 44f; typeface = Typeface.DEFAULT }
            c.drawText("${fmt(posMs)} / ${fmt(lenMs)}", 40f, 355f, time)
        }
    }

    private fun drawScreenPage() {
        controlPanel.draw { c, w, h ->
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(215, 18, 15, 30) }
            c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 36f, 36f, bg)
            val btnBg = Paint(Paint.ANTI_ALIAS_FLAG)
            val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = 40f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
            }
            val cfg = config
            val labels = mapOf(
                Btn.SIZE_MINUS to "Size −", Btn.SIZE_PLUS to "Size +",
                Btn.UP to "Move ▲", Btn.DOWN to "Move ▼", Btn.TYPE to shortName(cfg.format),
                Btn.EYE_MINUS to "Eyes −", Btn.EYE_PLUS to "Eyes +",
                Btn.LENS to if (cfg.lensCorrection) "Lens ✓" else "Lens ✗",
                Btn.TILT to if (cfg.tiltSeek) "Tilt ✓" else "Tilt ✗",
                Btn.RECENTER to "◎ Centre", Btn.RESET to "↺ Reset", Btn.DONE to "✓ Done",
            )
            for ((b, r) in screenButtons) {
                btnBg.color = if (b == hovered) Color.rgb(139, 92, 246) else Color.argb(60, 255, 255, 255)
                c.drawRoundRect(r, 26f, 26f, btnBg)
                txt.textSize = if ((labels[b] ?: "").length > 9) 34f else 40f
                c.drawText(labels[b] ?: "", r.centerX(), r.centerY() + txt.textSize / 3, txt)
            }
            val info = Paint(txt).apply { textAlign = Paint.Align.LEFT; textSize = 36f; typeface = Typeface.DEFAULT }
            c.drawText("Size ${(cfg.screenScale * 100).toInt()}%  ·  Height ${cfg.screenPitch.toInt()}°", 30f, 340f, info)
            c.drawText("Eyes ${cfg.ipdMm.toInt()} mm  ·  ${shortName(cfg.format)}", 30f, 385f, info)
        }
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    fun release() {
        stop()
        surfaceTexture?.release()
        surfaceTexture = null
    }

    // =============================================================================================
    // Helpers
    // =============================================================================================

    private inner class TextPanel(val w: Int, val h: Int) {
        val bitmap: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        var tex = 0
        var label = ""
        fun draw(block: (Canvas, Int, Int) -> Unit) {
            bitmap.eraseColor(Color.TRANSPARENT)
            block(Canvas(bitmap), w, h)
        }
        fun upload() {
            if (tex == 0) {
                val t = IntArray(1)
                GLES20.glGenTextures(1, t, 0)
                tex = t[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            } else {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
                GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
            }
        }
    }

    private class Mesh(val vertices: FloatBuffer, val indices: ShortBuffer?, val count: Int) {
        fun draw(prog: Int) {
            val aPos = GLES20.glGetAttribLocation(prog, "aPos")
            val aUV = GLES20.glGetAttribLocation(prog, "aUV")
            vertices.position(0)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 20, vertices)
            vertices.position(3)
            GLES20.glEnableVertexAttribArray(aUV)
            GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 20, vertices)
            if (indices != null) {
                indices.position(0)
                GLES20.glDrawElements(GLES20.GL_TRIANGLES, count, GLES20.GL_UNSIGNED_SHORT, indices)
            } else {
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, count)
            }
            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aUV)
        }

        companion object {
            /** Unit quad centred at origin, facing +z. Top edge has v = 1. */
            fun quad() = Mesh(
                floatBuffer(floatArrayOf(
                    -0.5f, -0.5f, 0f, 0f, 0f,
                    0.5f, -0.5f, 0f, 1f, 0f,
                    -0.5f, 0.5f, 0f, 0f, 1f,
                    0.5f, 0.5f, 0f, 1f, 1f,
                )), null, 4,
            )

            /** Inside of a sphere (equirectangular mapping) covering longitudes [lonFrom, lonTo]. */
            fun sphere(lonFrom: Float, lonTo: Float, radius: Float): Mesh {
                val rings = 48
                val segs = 96
                val v = FloatArray((rings + 1) * (segs + 1) * 5)
                var k = 0
                for (r in 0..rings) {
                    val lat = (-90f + 180f * r / rings) * PI.toFloat() / 180f
                    for (s in 0..segs) {
                        val lonDeg = lonFrom + (lonTo - lonFrom) * s / segs
                        val lon = lonDeg * PI.toFloat() / 180f
                        v[k++] = radius * sin(lon) * cos(lat)
                        v[k++] = radius * sin(lat)
                        v[k++] = -radius * cos(lon) * cos(lat)
                        v[k++] = s.toFloat() / segs
                        v[k++] = r.toFloat() / rings
                    }
                }
                val idx = ShortArray(rings * segs * 6)
                var i = 0
                for (r in 0 until rings) for (s in 0 until segs) {
                    val a = (r * (segs + 1) + s).toShort()
                    val b = (r * (segs + 1) + s + 1).toShort()
                    val c = ((r + 1) * (segs + 1) + s).toShort()
                    val d = ((r + 1) * (segs + 1) + s + 1).toShort()
                    idx[i++] = a; idx[i++] = c; idx[i++] = b
                    idx[i++] = b; idx[i++] = c; idx[i++] = d
                }
                val ib = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
                ib.put(idx).position(0)
                return Mesh(floatBuffer(v), ib, idx.size)
            }
        }
    }

    companion object {
        private const val SCREEN_DIST = 3.0f
        private const val CTRL_PITCH = -48f
        private const val CTRL_DIST = 1.7f
        private const val CTRL_W = 1.5f
        private const val TILT_DEG = 22f
        /** Controls fade in when you look further down than this. */
        private const val SHOW_BELOW_DEG = -22f

        /** Column-major matrix taking GL scene coords (y up, -z forward) to Android world (z up). */
        private val GL_TO_ANDROID = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, -1f, 0f, 0f,
            0f, 0f, 0f, 1f,
        )

        fun floatBuffer(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

        private fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) throw IllegalStateException("Shader error: " + GLES20.glGetShaderInfoLog(s))
            return s
        }

        private fun program(vs: String, fs: String): Int {
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p)
            return p
        }

        private const val VS_TEX = """
            uniform mat4 uMVP;
            uniform mat4 uST;
            uniform vec2 uUvOff;
            uniform vec2 uUvScale;
            attribute vec3 aPos;
            attribute vec2 aUV;
            varying vec2 vUV;
            varying vec2 vRaw;
            void main() {
                gl_Position = uMVP * vec4(aPos, 1.0);
                vec2 uv = uUvOff + aUV * uUvScale;
                vUV = (uST * vec4(uv, 0.0, 1.0)).xy;
                vRaw = aUV;
            }
        """

        private const val FS_OES = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTex;
            uniform float uAlpha;
            varying vec2 vUV;
            void main() {
                vec4 c = texture2D(uTex, vUV);
                gl_FragColor = vec4(c.rgb, uAlpha);
            }
        """

        private const val FS_2D = """
            precision mediump float;
            uniform sampler2D uTex;
            uniform float uAlpha;
            varying vec2 vUV;
            void main() {
                vec4 c = texture2D(uTex, vUV);
                gl_FragColor = vec4(c.rgb, c.a * uAlpha);
            }
        """

        private const val FS_RETICLE = """
            precision mediump float;
            uniform float uProgress;
            uniform float uActive;
            uniform float uAlpha;
            varying vec2 vRaw;
            void main() {
                vec2 p = vRaw * 2.0 - 1.0;
                float r = length(p);
                float dotA = 1.0 - smoothstep(0.22, 0.30, r);
                float ring = 1.0 - smoothstep(0.0, 0.10, abs(r - 0.78));
                float ang = atan(p.x, p.y) / 6.28318 + 0.5;
                float prog = ring * step(ang, uProgress);
                vec3 col = mix(vec3(1.0), vec3(0.55, 0.36, 0.96), prog);
                float a = max(dotA * (0.6 + 0.4 * uActive), max(prog, ring * 0.35 * uActive));
                gl_FragColor = vec4(col, a * uAlpha);
            }
        """

        private const val VS_DISTORT = """
            attribute vec2 aPos;
            varying vec2 vPos;
            void main() {
                vPos = aPos;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """

        /** Barrel pre-distortion so the headset lenses show a straight, undistorted picture. */
        private const val FS_DISTORT = """
            precision mediump float;
            uniform sampler2D uTex;
            uniform float uK1;
            uniform float uK2;
            uniform float uAspect;
            varying vec2 vPos;
            void main() {
                vec2 q = vec2(vPos.x * uAspect, vPos.y);
                float r2 = dot(q, q) / max(uAspect * uAspect, 1.0);
                vec2 p = vPos * (1.0 + uK1 * r2 + uK2 * r2 * r2);
                vec2 uv = p * 0.5 + 0.5;
                if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                } else {
                    gl_FragColor = texture2D(uTex, uv);
                }
            }
        """
    }
}
