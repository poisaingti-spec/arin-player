package com.arin.player.vr

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.arin.player.data.Settings
import com.arin.player.data.VrFormat

/** VR options + how-to, shown before entering VR. */
@Composable
fun VrSetupDialog(title: String, onStart: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val s = remember { Settings(ctx) }
    var format by remember { mutableStateOf(s.vrFormat) }
    var screen by remember { mutableFloatStateOf(s.vrScreenScale) }
    var ipd by remember { mutableFloatStateOf(s.vrIpdMm) }
    var lens by remember { mutableStateOf(s.vrLensCorrection) }
    var tilt by remember { mutableStateOf(s.vrTiltSeek) }
    var volKeys by remember { mutableStateOf(s.vrVolumeKeys) }
    var voice by remember { mutableStateOf(s.vrVoice) }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) { s.vrVoice = false }
        onStart()
    }

    fun start() {
        s.vrFormat = format; s.vrScreenScale = screen; s.vrIpdMm = ipd
        s.vrLensCorrection = lens; s.vrTiltSeek = tilt; s.vrVolumeKeys = volKeys; s.vrVoice = voice
        val needMic = voice && ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        if (needMic) micPermission.launch(Manifest.permission.RECORD_AUDIO) else onStart()
    }

    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("VR mode") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text("Hands-free controls in the headset:", fontWeight = FontWeight.SemiBold)
                Text(
                    "• Tilt your head right = forward 10 s, left = back 10 s (keep tilting to go faster)\n" +
                        "• Look down for the control panel; look at a button for a second to press it " +
                        "(look at the time bar to jump anywhere)\n" +
                        "• Headset button / Bluetooth remote / volume keys also work" +
                        (if (voice) "\n• Say \"forward\", \"back 1 minute\", \"pause\", \"play\", \"exit\"" else ""),
                    fontSize = 13.sp, color = dim,
                )
                Spacer(Modifier.height(10.dp))
                Text("Video type", fontWeight = FontWeight.SemiBold)
                VrFormat.entries.forEach { f ->
                    Row(Modifier.fillMaxWidth().clickable { format = f }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = format == f, onClick = { format = f })
                        Text(
                            if (f == VrFormat.AUTO) "Auto-detect (${detectFormat(title).label})" else f.label,
                            fontSize = 14.sp,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("Screen size: ${(screen * 100).toInt()}%", fontSize = 14.sp)
                Slider(value = screen, onValueChange = { screen = it }, valueRange = 0.6f..1.6f)
                Text("Eye distance: ${ipd.toInt()} mm", fontSize = 14.sp)
                Slider(value = ipd, onValueChange = { ipd = it }, valueRange = 55f..75f)
                Toggle("Lens correction (most headsets)", lens) { lens = it }
                Toggle("Tilt head to rewind / forward", tilt) { tilt = it }
                Toggle("Volume buttons rewind / forward", volKeys) { volKeys = it }
                Toggle("Voice commands", voice) { voice = it }
                Text(
                    "Put the phone in the headset after pressing Start. Look straight ahead — the screen " +
                        "appears where you're looking (use \"Centre\" on the panel to move it).",
                    fontSize = 12.sp, color = dim, modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = { Button(onClick = { start() }) { Text("Start VR") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}
