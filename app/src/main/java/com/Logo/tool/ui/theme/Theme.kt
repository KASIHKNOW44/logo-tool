package com.Logo.tool.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Immutable
data class LogoToolExtendedColors(
    val bg: Color,
    val surface: Color,
    val soft: Color,
    val ink: Color,
    val mute: Color,
    val line: Color,
    val chip: Color,
    val accent: Color,
    val accent2: Color,
    val onAccent: Color,
    val ok: Color,
    val warn: Color,
    val err: Color
)

private val LightExtendedColors = LogoToolExtendedColors(
    bg = LightBg,
    surface = LightSurface,
    soft = LightSoft,
    ink = LightInk,
    mute = LightMute,
    line = LightLine,
    chip = LightChip,
    accent = LightAccent,
    accent2 = LightAccent2,
    onAccent = LightOnAccent,
    ok = LightOk,
    warn = LightWarn,
    err = LightErr
)

private val DarkExtendedColors = LogoToolExtendedColors(
    bg = DarkBg,
    surface = DarkSurface,
    soft = DarkSoft,
    ink = DarkInk,
    mute = DarkMute,
    line = DarkLine,
    chip = DarkChip,
    accent = DarkAccent,
    accent2 = DarkAccent2,
    onAccent = DarkOnAccent,
    ok = DarkOk,
    warn = DarkWarn,
    err = DarkErr
)

val LocalLogoColors = staticCompositionLocalOf { LightExtendedColors }

private val LightColorScheme = lightColorScheme(
    primary = LightAccent,
    onPrimary = LightOnAccent,
    primaryContainer = LightAccent.copy(alpha = 0.14f),
    onPrimaryContainer = LightAccent,
    secondary = LightAccent2,
    onSecondary = Color.White,
    secondaryContainer = LightChip,
    onSecondaryContainer = LightInk,
    background = LightBg,
    onBackground = LightInk,
    surface = LightSurface,
    onSurface = LightInk,
    surfaceVariant = LightSoft,
    onSurfaceVariant = LightMute,
    outline = LightLine,
    outlineVariant = LightLine,
    error = LightErr,
    onError = Color.White
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkAccent,
    onPrimary = DarkOnAccent,
    primaryContainer = DarkAccent.copy(alpha = 0.16f),
    onPrimaryContainer = DarkAccent,
    secondary = DarkAccent2,
    onSecondary = DarkOnAccent,
    secondaryContainer = DarkChip,
    onSecondaryContainer = DarkInk,
    background = DarkBg,
    onBackground = DarkInk,
    surface = DarkSurface,
    onSurface = DarkInk,
    surfaceVariant = DarkSoft,
    onSurfaceVariant = DarkMute,
    outline = DarkLine,
    outlineVariant = DarkLine,
    error = DarkErr,
    onError = DarkOnAccent
)

@Composable
fun LogoToolTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalLogoColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = LogoToolTypography,
            content = content
        )
    }
}
