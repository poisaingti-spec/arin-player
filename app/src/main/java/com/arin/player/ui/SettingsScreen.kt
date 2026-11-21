package com.arin.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arin.player.data.CloudProvider
import com.arin.player.data.EngineMode
import com.arin.player.data.LocalModel
import com.arin.player.data.Settings
import com.arin.player.engine.ModelManager
import com.arin.player.online.OnlineResolver
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.arin.player.update.UpdateState
import com.arin.player.update.Updater
import androidx.compose.material3.Button

@Composable
fun SettingsScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val s = remember { Settings(ctx) }
    val models = remember { ModelManager(ctx) }
    BackHandler(onBack = onClose)

    var mode by remember { mutableStateOf(s.mode) }
    var provider by remember { mutableStateOf(s.provider) }
    var key by remember(provider) { mutableStateOf(s.apiKey(provider)) }
    var localModel by remember { mutableStateOf(s.localModel) }
    var subSize by remember { mutableFloatStateOf(s.subtitleSizeSp) }
    var langTag by remember { mutableStateOf(s.showLanguageTag) }
    var refresh by remember { mutableIntStateOf(0) } // re-check model files after delete
    val downloading by ModelManager.progress.collectAsState()
    val dlErrors by ModelManager.errors.collectAsState()
    val updateState by Updater.state.collectAsState()
    val engineStatus by OnlineResolver.engineStatus.collectAsState()
    val scope = rememberCoroutineScope()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }

            Section("Translation engine")
            EngineMode.entries.forEach { m ->
                RadioRow(m.label, m.desc, mode == m) { mode = m; s.mode = m }
            }

            if (mode != EngineMode.OFFLINE) {
                Section("Cloud")
                CloudProvider.entries.forEach { p ->
                    RadioRow(p.label, p.model, provider == p) { provider = p; s.provider = p }
                }
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it; s.setApiKey(provider, it) },
                    label = { Text("${provider.label.substringBefore(" (")} API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text("Get one at ${provider.keyHint}", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(top = 4.dp))
            }

            if (mode != EngineMode.CLOUD) {
                Section("Offline model")
                LocalModel.entries.forEach { m ->
                    val ready = remember(refresh, downloading) { models.isReady(m) }
                    val prog = downloading[m]
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = localModel == m, onClick = { localModel = m; s.localModel = m })
                            Column(Modifier.weight(1f)) {
                                Text("${m.label} · ${m.sizeMb} MB")
                                Text(dlErrors[m]?.let { "Failed: $it" } ?: m.note, fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                            when {
                                prog != null -> TextButton(onClick = { models.cancel(m) }) { Text("Cancel") }
                                ready -> TextButton(onClick = { models.delete(m); refresh++ }) { Text("Delete") }
                                else -> TextButton(onClick = { models.download(m) }) { Text("Download") }
                            }
                        }
                        if (prog != null) LinearProgressIndicator(progress = { prog }, modifier = Modifier.fillMaxWidth().padding(start = 48.dp, end = 8.dp))
                    }
                }
            }

            Section("Subtitles")
            Text("Text size: ${subSize.toInt()} sp")
            Slider(value = subSize, onValueChange = { subSize = it; s.subtitleSizeSp = it }, valueRange = 14f..34f)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Show spoken language")
                    Text("e.g. [JA] before lines translated from Japanese", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                Switch(checked = langTag, onCheckedChange = { langTag = it; s.showLanguageTag = it })
            }
            Section("Website support (YouTube & 1000+ sites)")
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Video engine: yt-dlp ${remember(engineStatus) { OnlineResolver.version(ctx) }}")
                    Text(
                        engineStatus.ifBlank { "Updates itself daily. Update now if a site stops working." },
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                TextButton(
                    onClick = { scope.launch { runCatching { OnlineResolver.updateNow(ctx) } } },
                    enabled = engineStatus != "Updating…",
                ) { Text("Update now") }
            }

            Section("App updates")
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Version ${Updater.currentName}")
                    Text(
                        when (updateState) {
                            is UpdateState.Checking -> "Checking…"
                            is UpdateState.Downloading -> "Downloading update…"
                            is UpdateState.Available -> "A new version is available"
                            else -> "Updates download and install inside the app"
                        },
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                Button(
                    onClick = { Updater.check(silent = false) },
                    enabled = updateState !is UpdateState.Checking && updateState !is UpdateState.Downloading,
                ) { Text("Check for updates") }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(20.dp))
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))
    Text(title, color = Accent, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun RadioRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(4.dp))
        Column {
            Text(title)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}
