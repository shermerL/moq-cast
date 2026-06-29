package com.example.moqandroid.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val WorkspaceBackground = Color(0xFFF7F6F3)
val SurfaceColor = Color.White
val SurfaceMuted = Color(0xFFF1F1EF)
val BorderColor = Color(0xFFE3E2DF)
val PrimaryColor = Color(0xFF2F3437)
val TextPrimary = Color(0xFF37352F)
val TextSecondary = Color(0xFF787774)

@Composable
fun MoqAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = PrimaryColor,
            onPrimary = Color.White,
            surface = SurfaceColor,
            surfaceVariant = SurfaceMuted,
            background = WorkspaceBackground,
            onSurface = TextPrimary,
            outline = BorderColor,
        ),
        content = content,
    )
}
