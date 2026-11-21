package com.arin.player.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import com.arin.player.data.EngineMode
import com.arin.player.data.Settings
import com.arin.player.engine.ModelManager

@Composable
fun HomeScreen(onOpen: (Uri) -> Unit, onSettings: () -> Unit, settingsVersion: Int) {
    val ctx = LocalContext.current
    val settings = remember(settingsVersion) { Settings(ctx) }
    val models = remember { ModelManager(ctx) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onOpen)
    }
    val clipboard = LocalClipboardManager.current
    var url by remember { mutableStateOf("") }
    var urlError by remember { mutableStateOf<String?>(null) }
    var recent by remember { mutableStateOf(settings.recentUrls) }
    fun playUrl(raw: String) {
        val u = raw.trim().let { if ("://" !in it && it.isNotEmpty()) "https://$it" else it }
        val parsed = Uri.parse(u)
        if (u.isEmpty() || parsed.host.isNullOrBlank() || !isNetworkUri(parsed)) {
            urlError = "Paste a video link (http, https, rtsp…)"; return
        }
        urlError = null
        settings.addRecentUrl(u); recent = settings.recentUrls
        onOpen(parsed)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Arin player", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") }
            }
            Text(
                "Plays any video and writes English subtitles for whatever language is spoken.",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(32.dp))

            Button(onClick = { picker.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Filled.VideoLibrary, null); Spacer(Modifier.width(8.dp)); Text("Open video")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Filled.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Browse all files (MKV, AVI…)")
            }

            Spacer(Modifier.height(28.dp))
            Text("Play online", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            val err = urlError
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; urlError = null },
                placeholder = { Text("Paste video link (.mp4, .mkv, .m3u8…)") },
                singleLine = true,
                isError = urlError != null,
                supportingText = if (err != null) { { Text(err) } } else null,
                leadingIcon = { Icon(Icons.Filled.Link, null) },
                trailingIcon = {
                    IconButton(onClick = { clipboard.getText()?.text?.let { url = it.trim(); urlError = null } }) {
                        Icon(Icons.Filled.ContentPaste, "Paste")
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { playUrl(url) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { playUrl(url) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Filled.Language, null); Spacer(Modifier.width(8.dp)); Text("Play link")
            }
            if (recent.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Recent links", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                recent.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().clickable { playUrl(r) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.History, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(r, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Subtitles, null, tint = Accent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Subtitle engine: ${settings.mode.label}", fontWeight = FontWeight.SemiBold)
                    }
                    val cloudOk = settings.apiKey().isNotBlank()
                    val localOk = models.isReady(settings.localModel)
                    if (settings.mode != EngineMode.OFFLINE)
                        Text((if (cloudOk) "✓ " else "✗ ") + "Cloud: ${settings.provider.label}" + if (cloudOk) "" else " — no API key")
                    if (settings.mode != EngineMode.CLOUD)
                        Text((if (localOk) "✓ " else "✗ ") + "Offline model: ${settings.localModel.label}" + if (localOk) "" else " — not downloaded")
                    if (!cloudOk && !localOk)
                        Text("Open Settings to add a key or download a model.", color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
}
