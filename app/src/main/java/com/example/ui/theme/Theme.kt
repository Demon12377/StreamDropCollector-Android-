package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.core.models.ThemeMode

@Immutable
data class DropCollectorColors(
    val mainBackground: Color,
    val cardBackground: Color,
    val headerBackground: Color,
    val insetBackground: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val secondaryAccent: Color,
    val isDark: Boolean
)

val LocalDropColors = staticCompositionLocalOf {
    DropCollectorColors(
        mainBackground = DarkMainBackground,
        cardBackground = DarkCardBackground,
        headerBackground = DarkHeaderBackground,
        insetBackground = DarkInsetBackground,
        border = DarkBorder,
        textPrimary = DarkTextPrimary,
        textSecondary = DarkTextSecondary,
        textMuted = DarkTextMuted,
        accent = DarkAccent,
        secondaryAccent = DarkSecondaryAccent,
        isDark = true
    )
}

private val DarkM3Colors = darkColorScheme(
    primary = DarkAccent,
    onPrimary = Color.Black,
    primaryContainer = DarkHover,
    onPrimaryContainer = DarkAccent,
    secondary = DarkSecondaryAccent,
    onSecondary = Color.Black,
    tertiary = TwitchPurple,
    onTertiary = Color.White,
    background = DarkMainBackground,
    onBackground = DarkTextPrimary,
    surface = DarkCardBackground,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkInsetBackground,
    onSurfaceVariant = DarkTextMuted,
    outline = DarkBorder,
    error = StatusDanger,
    onError = Color.White
)

private val LightM3Colors = lightColorScheme(
    primary = LightAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCE8DD),
    onPrimaryContainer = Color(0xFF005C36),
    secondary = LightSecondaryAccent,
    onSecondary = Color.White,
    tertiary = TwitchPurple,
    onTertiary = Color.White,
    background = LightMainBackground,
    onBackground = LightTextPrimary,
    surface = LightCardBackground,
    onSurface = LightTextPrimary,
    surfaceVariant = LightInsetBackground,
    onSurfaceVariant = LightTextMuted,
    outline = LightBorder,
    error = StatusDangerBorder,
    onError = Color.White
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp)
)

@Composable
fun DropCollectorTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val customColors = if (useDark) {
        DropCollectorColors(
            mainBackground = DarkMainBackground,
            cardBackground = DarkCardBackground,
            headerBackground = DarkHeaderBackground,
            insetBackground = DarkInsetBackground,
            border = DarkBorder,
            textPrimary = DarkTextPrimary,
            textSecondary = DarkTextSecondary,
            textMuted = DarkTextMuted,
            accent = DarkAccent,
            secondaryAccent = DarkSecondaryAccent,
            isDark = true
        )
    } else {
        DropCollectorColors(
            mainBackground = LightMainBackground,
            cardBackground = LightCardBackground,
            headerBackground = LightHeaderBackground,
            insetBackground = LightInsetBackground,
            border = LightBorder,
            textPrimary = LightTextPrimary,
            textSecondary = LightTextSecondary,
            textMuted = LightTextMuted,
            accent = LightAccent,
            secondaryAccent = LightSecondaryAccent,
            isDark = false
        )
    }

    CompositionLocalProvider(LocalDropColors provides customColors) {
        MaterialTheme(
            colorScheme = if (useDark) DarkM3Colors else LightM3Colors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}
