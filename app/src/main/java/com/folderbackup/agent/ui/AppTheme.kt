package com.folderbackup.agent.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF81C784),
    onPrimary = Color(0xFF003910),
    primaryContainer = Color(0xFF1B5E20),
    onPrimaryContainer = Color(0xFFC8E6C9),
    secondary = Color(0xFF90CAF9),
    onSecondary = Color(0xFF0D3C61),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE8E8E8),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE8E8E8),
    surfaceVariant = Color(0xFF2C2C2C),
    onSurfaceVariant = Color(0xFFBDBDBD),
    outline = Color(0xFF6B6B6B),
    error = Color(0xFFCF6679),
    onError = Color(0xFF000000),
)

@Composable
fun FolderBackupTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}
