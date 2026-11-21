package com.arin.player

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.arin.player.online.BrowserScreen
import com.arin.player.online.OnlineResolver
import com.arin.player.online.PlayRequest
import com.arin.player.ui.ArinTheme
import com.arin.player.ui.HomeScreen
import com.arin.player.ui.PlayerScreen
import com.arin.player.ui.SettingsScreen
import com.arin.player.update.UpdateDialog
import com.arin.player.update.Updater

class MainActivity : ComponentActivity() {
    private var playRequest by mutableStateOf<PlayRequest?>(null)
    /** In-app browser used to find videos on sites yt-dlp doesn't support. */
    private var browserUrl by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uriFrom(intent)?.let { playRequest = PlayRequest(it) }
        Updater.check(silent = true) // quietly look for a newer build on every launch
        OnlineResolver.updateInBackground(this) // keep YouTube & other sites working
        setContent {
            ArinTheme {
                var showSettings by androidx.compose.runtime.remember { mutableStateOf(false) }
                var settingsVersion by androidx.compose.runtime.remember { mutableIntStateOf(0) }

                Box(Modifier.fillMaxSize()) {
                    val req = playRequest
                    val browser = browserUrl
                    when {
                        req != null -> androidx.compose.runtime.key(req) {
                            // key() so opening a different video rebuilds the player from scratch
                            PlayerScreen(
                                request = req,
                                settingsVersion = settingsVersion,
                                settingsOpen = showSettings,
                                onBack = { playRequest = null },
                                onSettings = { showSettings = true },
                                onFindInBrowser = { url -> playRequest = null; browserUrl = url },
                            )
                        }
                        browser != null -> BrowserScreen(
                            startUrl = browser,
                            onPlay = { playRequest = it }, // back from the player returns to the page
                            onClose = { browserUrl = null },
                        )
                        else -> HomeScreen(
                            onOpen = { playRequest = PlayRequest(it) },
                            onBrowse = { browserUrl = it },
                            onSettings = { showSettings = true },
                            settingsVersion = settingsVersion,
                        )
                    }
                    // Drawn on top so the player keeps its state underneath.
                    if (showSettings) {
                        SettingsScreen(onClose = { showSettings = false; settingsVersion++ })
                    }
                    BackHandler(enabled = req != null && !showSettings) { playRequest = null }
                    // Update prompt / download progress, shown over any screen
                    UpdateDialog()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        uriFrom(intent)?.let { browserUrl = null; playRequest = PlayRequest(it) }
    }

    private fun uriFrom(i: Intent?): Uri? = when (i?.action) {
        Intent.ACTION_VIEW -> i.data
        Intent.ACTION_SEND ->
            (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM))
                ?: i.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                    // a link shared as text, e.g. from a browser
                    Regex("""(https?|rtsp|rtmp)://\S+""").find(text)?.value?.let(Uri::parse)
                }
        else -> null
    }
}
