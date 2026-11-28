package com.arin.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arin.player.data.Languages
import com.arin.player.data.SpokenLanguage

/** Searchable list of spoken languages (Indian and North-East Indian languages first). */
@Composable
fun LanguagePickerDialog(
    current: SpokenLanguage,
    worldModelReady: Boolean,
    onPick: (SpokenLanguage) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val list = remember(query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) Languages.all
        else Languages.all.filter { it.name.lowercase().contains(q) || it.region.lowercase().contains(q) || it.code.lowercase() == q }
    }
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Spoken language") },
        text = {
            Column {
                Text(
                    "Choose the language spoken in the video for the best subtitles. " +
                        "Regional languages use the World languages model (download once in Settings).",
                    fontSize = 12.sp, color = dim,
                )
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Search: Khasi, Mizo, Assam, Nagaland…") },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                LazyColumn(Modifier.fillMaxWidth().height(380.dp)) {
                    items(list, key = { it.code }) { l ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(l) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = l.code == current.code, onClick = { onPick(l) })
                            Column(Modifier.weight(1f)) {
                                Text(l.name, fontSize = 15.sp)
                                val note = buildList {
                                    if (l.region.isNotBlank()) add(l.region)
                                    when {
                                        l == Languages.AUTO -> add("detects automatically")
                                        l.experimental -> add("experimental — no speech model knows it yet")
                                        l.usesWorldModel -> add(if (worldModelReady) "world languages model" else "needs World languages model")
                                    }
                                }.joinToString(" · ")
                                if (note.isNotBlank()) Text(
                                    note, fontSize = 11.sp,
                                    color = if (l.experimental) Color(0xFFFFB74D) else dim,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
