package com.example.moqandroid.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val WorkspaceBackground = Color(0xFFF6F8F6)
val SurfaceColor = Color.White
val SurfaceMuted = Color(0xFFEEF4F1)
val BorderColor = Color(0xFFD7E1DD)
val PrimaryColor = Color(0xFF0C7C68)
val TextPrimary = Color(0xFF16201D)
val TextSecondary = Color(0xFF65736E)
val PlayerColor = Color(0xFF101614)

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
