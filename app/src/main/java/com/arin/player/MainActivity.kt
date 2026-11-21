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
import com.arin.player.ui.ArinTheme
import com.arin.player.ui.HomeScreen
import com.arin.player.ui.PlayerScreen
import com.arin.player.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private var videoUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        videoUri = uriFrom(intent)
        setContent {
            ArinTheme {
                var showSettings by androidx.compose.runtime.remember { mutableStateOf(false) }
                var settingsVersion by androidx.compose.runtime.remember { mutableIntStateOf(0) }

                Box(Modifier.fillMaxSize()) {
                    val uri = videoUri
                    if (uri == null) {
                        HomeScreen(
                            onOpen = { videoUri = it },
                            onSettings = { showSettings = true },
                            settingsVersion = settingsVersion,
                        )
                    } else {
                        // key() so opening a different video rebuilds the player from scratch
                        androidx.compose.runtime.key(uri) {
                            PlayerScreen(
                                uri = uri,
                                settingsVersion = settingsVersion,
                                settingsOpen = showSettings,
                                onBack = { videoUri = null },
                                onSettings = { showSettings = true },
                            )
                        }
                    }
                    // Drawn on top so the player keeps its state underneath.
                    if (showSettings) {
                        SettingsScreen(onClose = { showSettings = false; settingsVersion++ })
                    }
                    BackHandler(enabled = uri != null && !showSettings) { videoUri = null }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        uriFrom(intent)?.let { videoUri = it }
    }

    private fun uriFrom(i: Intent?): Uri? = when (i?.action) {
        Intent.ACTION_VIEW -> i.data
        Intent.ACTION_SEND ->
            if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
        else -> null
    }
}
