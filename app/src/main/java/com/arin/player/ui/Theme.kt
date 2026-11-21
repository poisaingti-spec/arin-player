package com.arin.player.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF8B5CF6)

@Composable
fun ArinTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            secondary = Color(0xFFC4B5FD),
            background = Color(0xFF0B0A12),
            surface = Color(0xFF14121F),
            surfaceVariant = Color(0xFF1F1B2E),
        ),
        content = content,
    )
}
