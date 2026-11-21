package com.arin.player.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** App-wide update dialog: shows whenever an update is available, downloading or ready. */
@Composable
fun UpdateDialog() {
    val ctx = LocalContext.current
    val state by Updater.state.collectAsState()

    when (val s = state) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = { Updater.dismiss() },
            title = { Text("Update available") },
            text = {
                Column {
                    Text("${s.info.name} · ${mb(s.info.sizeBytes)} MB")
                    Text("You have version ${Updater.currentName}", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    if (s.info.notes.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                            Text(s.info.notes, fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = { Updater.download(ctx, s.info) }) { Text("Update now") } },
            dismissButton = { TextButton(onClick = { Updater.dismiss() }) { Text("Later") } },
        )

        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {}, // keep visible while downloading
            title = { Text("Downloading update") },
            text = {
                Column {
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("${(s.progress * 100).toInt()}%  ·  ${mb(s.doneBytes)} / ${mb(s.info.sizeBytes)} MB")
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { Updater.cancel(s.info) }) { Text("Cancel") } },
        )

        is UpdateState.ReadyToInstall -> AlertDialog(
            onDismissRequest = { Updater.dismiss() },
            title = { Text("Update downloaded") },
            text = { Text("Tap Install. If Android asks, allow installs from Arin player, then come back and tap Install again.") },
            confirmButton = { Button(onClick = { Updater.install(ctx, s.file) }) { Text("Install") } },
            dismissButton = { TextButton(onClick = { Updater.dismiss() }) { Text("Later") } },
        )

        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = { Updater.dismiss() },
            title = { Text("Update") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = { Updater.dismiss() }) { Text("OK") } },
        )

        is UpdateState.UpToDate -> AlertDialog(
            onDismissRequest = { Updater.dismiss() },
            title = { Text("You're up to date") },
            text = { Text("Version ${Updater.currentName} is the latest.") },
            confirmButton = { TextButton(onClick = { Updater.dismiss() }) { Text("OK") } },
        )

        else -> Unit
    }
}

private fun mb(bytes: Long) = String.format(Locale.US, "%.1f", bytes / 1_048_576.0)
