package com.arin.player.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.arin.player.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionCode: Int,
    val name: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    /** [progress] 0..1, [doneBytes]/[info.sizeBytes] for the "12.3 / 32.4 MB" label. */
    data class Downloading(val info: UpdateInfo, val progress: Float, val doneBytes: Long) : UpdateState
    data class ReadyToInstall(val info: UpdateInfo, val file: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Checks GitHub Releases of this app's repo for a newer build, downloads the APK inside the app
 * with progress, then hands it to Android's installer. Because every build is signed with the
 * same key, the update installs over the current app; no uninstall needed.
 */
object Updater {
    val state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val currentVersion: Int get() = BuildConfig.VERSION_CODE
    val currentName: String get() = BuildConfig.VERSION_NAME

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).build()
    private var job: Job? = null
    /** Build the user tapped "Later" on, so the launch check doesn't nag again this session. */
    private var dismissedVersion = -1

    fun check(silent: Boolean) {
        if (job?.isActive == true) return
        job = scope.launch {
            if (!silent) state.value = UpdateState.Checking
            try {
                val req = Request.Builder()
                    .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .build()
                val info = http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("GitHub returned ${r.code}")
                    parse(r.body!!.string())
                }
                state.value = when {
                    info == null || info.versionCode <= currentVersion ->
                        if (silent) UpdateState.Idle else UpdateState.UpToDate
                    silent && info.versionCode == dismissedVersion -> UpdateState.Idle
                    else -> UpdateState.Available(info)
                }
            } catch (e: Exception) {
                state.value = if (silent) UpdateState.Idle
                else UpdateState.Failed("Couldn't check for updates: ${e.message ?: "no connection"}")
            }
        }
    }

    fun download(context: Context, info: UpdateInfo) {
        if (job?.isActive == true && state.value is UpdateState.Downloading) return
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "ArinPlayer-${info.versionCode}.apk")
        job = scope.launch {
            // Remove older downloaded updates.
            dir.listFiles()?.filter { it != target }?.forEach { it.delete() }
            if (target.exists() && info.sizeBytes > 0 && target.length() == info.sizeBytes) {
                state.value = UpdateState.ReadyToInstall(info, target); return@launch
            }
            val tmp = File(dir, target.name + ".part")
            try {
                state.value = UpdateState.Downloading(info, 0f, 0)
                // browser_download_url redirects straight to the APK file; OkHttp follows it.
                http.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                    val body = r.body!!
                    val total = body.contentLength().takeIf { it > 0 } ?: info.sizeBytes
                    body.byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(128 * 1024)
                            var done = 0L
                            var lastEmit = 0L
                            while (isActive) {
                                val n = input.read(buf); if (n < 0) break
                                out.write(buf, 0, n); done += n
                                if (done - lastEmit > 256 * 1024 || done == total) {
                                    lastEmit = done
                                    val p = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                                    state.value = UpdateState.Downloading(info, p, done)
                                }
                            }
                        }
                    }
                }
                if (!isActive) { tmp.delete(); return@launch }
                target.delete(); tmp.renameTo(target)
                state.value = UpdateState.ReadyToInstall(info, target)
                install(context, target)
            } catch (e: Exception) {
                tmp.delete()
                state.value = if (isActive) UpdateState.Failed("Download failed: ${e.message ?: "connection lost"}")
                else UpdateState.Available(info)
            }
        }
    }

    fun cancel(info: UpdateInfo) {
        job?.cancel()
        state.value = UpdateState.Available(info)
    }

    fun dismiss() {
        (state.value as? UpdateState.Available)?.let { dismissedVersion = it.info.versionCode }
        if (state.value !is UpdateState.Downloading) state.value = UpdateState.Idle
    }

    /** Opens Android's installer. First time, Android asks to allow installs from this app. */
    fun install(context: Context, file: File) {
        val ctx = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return // the dialog stays on "Install"; tap it again after allowing
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun parse(json: String): UpdateInfo? {
        val o = JSONObject(json)
        val tag = o.optString("tag_name")                       // "build-12"
        val code = Regex("""(\d+)$""").find(tag)?.value?.toIntOrNull() ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk") } ?: return null
        return UpdateInfo(
            versionCode = code,
            name = o.optString("name").ifBlank { "Build $code" },
            notes = o.optString("body").trim(),
            apkUrl = apk.getString("browser_download_url"),
            sizeBytes = apk.optLong("size"),
        )
    }
}
