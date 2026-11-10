package com.reveal.editor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PaletteColorScheme = lightColorScheme(
    primary = Color(0xFF2D69FF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE3EDFF),
    onPrimaryContainer = Color(0xFF16325F),
    secondary = Color(0xFF5B8DEF),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF1C2026),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C2026),
    surfaceVariant = Color(0xFFF1F3F6),
    onSurfaceVariant = Color(0xFF6E747C),
    outline = Color(0xFFDCE0E6),
    outlineVariant = Color(0xFFE6E9ED),
    error = Color(0xFFD94A4A),
)

@Composable
fun PaletteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PaletteColorScheme,
        content = content,
    )
}
