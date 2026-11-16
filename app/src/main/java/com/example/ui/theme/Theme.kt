package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val ZynoDarkColorScheme = darkColorScheme(
    primary = ZynoViolet,
    onPrimary = ZynoMidnightDark,
    primaryContainer = ZynoMidnightElevated,
    onPrimaryContainer = ZynoVioletLight,
    secondary = ZynoCyan,
    onSecondary = ZynoMidnightDark,
    secondaryContainer = Color(0xFF0D2E3B),
    onSecondaryContainer = Color(0xFFCFFAFE),
    tertiary = ZynoSuccess,
    onTertiary = ZynoMidnightDark,
    background = ZynoMidnightDark,
    onBackground = ZynoSnow,
    surface = ZynoMidnightSurface,
    onSurface = ZynoSnow,
    surfaceVariant = ZynoMidnightElevated,
    onSurfaceVariant = ZynoMutedText,
    outline = ZynoMidnightOutline
)

private val ZynoLightColorScheme = lightColorScheme(
    primary = ZynoVioletDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF3E8FF),
    onPrimaryContainer = Color(0xFF3B0764),
    secondary = ZynoCyanDeep,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFFAFE),
    onSecondaryContainer = Color(0xFF083344),
    tertiary = ZynoSuccessDark,
    onTertiary = Color.White,
    background = ZynoLightBg,
    onBackground = ZynoLightInk,
    surface = ZynoLightSurface,
    onSurface = ZynoLightInk,
    surfaceVariant = ZynoLightElevated,
    onSurfaceVariant = ZynoLightInkSecondary,
    outline = Color(0xFFC9BCEE)
)

@Composable
fun HearthVoiceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> ZynoDarkColorScheme
        else -> ZynoLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = HearthTypography,
        content = content
    )
}
