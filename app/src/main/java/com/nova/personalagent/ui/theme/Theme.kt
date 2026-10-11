package com.nova.personalagent.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * NOVA always uses a dark colour scheme so the brand aesthetic is consistent
 * on every device regardless of system settings.
 * Dynamic colour (Android 12+ wallpaper tinting) is intentionally disabled
 * so the electric-cyan palette is always shown.
 */
private val NovaDarkColorScheme = darkColorScheme(
    primary          = NovaCyan,
    onPrimary        = NovaBackground,
    primaryContainer = NovaSurface,
    secondary        = NovaCyanDim,
    background       = NovaBackground,
    surface          = NovaSurface,
    onBackground     = NovaTextPrimary,
    onSurface        = NovaTextPrimary,
)

@Composable
fun NOVATheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = NovaDarkColorScheme,
        typography  = Typography,
        content     = content
    )
}