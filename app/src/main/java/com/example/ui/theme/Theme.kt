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

private val DarkColorScheme = darkColorScheme(
    primary = PolishPurpleLight,
    onPrimary = PolishOnPurpleContainer,
    primaryContainer = PolishPurpleDark,
    onPrimaryContainer = PolishPurpleContainer,
    secondary = PolishPurpleLight,
    onSecondary = Color.Black,
    secondaryContainer = PolishSecondary,
    onSecondaryContainer = PolishSecondaryContainer,
    tertiary = PolishTertiaryContainer,
    onTertiary = PolishOnTertiaryContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = PolishError,
    errorContainer = PolishErrorContainer,
    onErrorContainer = PolishOnErrorContainer
)

private val LightColorScheme = lightColorScheme(
    primary = PolishPurplePrimary,
    onPrimary = Color.White,
    primaryContainer = PolishPurpleContainer,
    onPrimaryContainer = PolishOnPurpleContainer,
    secondary = PolishSecondary,
    onSecondary = Color.White,
    secondaryContainer = PolishSecondaryContainer,
    onSecondaryContainer = PolishOnSecondaryContainer,
    tertiary = PolishTertiary,
    onTertiary = Color.White,
    tertiaryContainer = PolishTertiaryContainer,
    onTertiaryContainer = PolishOnTertiaryContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = PolishError,
    errorContainer = PolishErrorContainer,
    onErrorContainer = PolishOnErrorContainer
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

