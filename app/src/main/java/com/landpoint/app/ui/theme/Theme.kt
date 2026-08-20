package com.landpoint.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.landpoint.app.data.SettingsRepository

// Every role is named. Six were named before, and the rest came from
// lightColorScheme()'s purple defaults — which is why cards, outlines and
// containers never looked like they belonged to the same app as the buttons.

private val LightColors = lightColorScheme(
    primary = Green40,
    onPrimary = Color.White,
    primaryContainer = Green90,
    onPrimaryContainer = Green10,
    secondary = Brown40,
    onSecondary = Color.White,
    secondaryContainer = Brown90,
    onSecondaryContainer = Brown10,
    tertiary = Sand40,
    onTertiary = Color.White,
    tertiaryContainer = Sand90,
    onTertiaryContainer = Sand10,
    error = ErrorRed,
    onError = Color.White,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer,
    background = Neutral98,
    onBackground = Neutral10,
    surface = Neutral98,
    onSurface = Neutral10,
    surfaceVariant = Neutral92,
    onSurfaceVariant = Neutral30,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Neutral96,
    surfaceContainer = Neutral94,
    surfaceContainerHigh = Neutral92,
    surfaceContainerHighest = Neutral90,
    surfaceTint = Green40,
    inverseSurface = Neutral20,
    inverseOnSurface = Neutral96,
    inversePrimary = Green80,
    outline = Outline,
    outlineVariant = OutlineVariant,
    scrim = Color.Black
)

private val DarkColors = darkColorScheme(
    primary = Green80,
    onPrimary = Green20,
    primaryContainer = Green30,
    onPrimaryContainer = Green90,
    secondary = Brown80,
    onSecondary = Brown20,
    secondaryContainer = Brown30,
    onSecondaryContainer = Brown90,
    tertiary = Sand80,
    onTertiary = Sand20,
    tertiaryContainer = Sand30,
    onTertiaryContainer = Sand90,
    error = ErrorRedDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    background = Neutral6,
    onBackground = Neutral90,
    surface = Neutral6,
    onSurface = Neutral90,
    surfaceVariant = Neutral30,
    onSurfaceVariant = Neutral80,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Neutral10,
    surfaceContainer = Neutral12,
    surfaceContainerHigh = Neutral17,
    surfaceContainerHighest = Neutral22,
    surfaceTint = Green80,
    inverseSurface = Neutral90,
    inverseOnSurface = Neutral20,
    inversePrimary = Green40,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    scrim = Color.Black
)

@Composable
fun LandPointTheme(
    darkMode: SettingsRepository.DarkMode = SettingsRepository.DarkMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val darkTheme = when (darkMode) {
        SettingsRepository.DarkMode.SYSTEM -> isSystemInDarkTheme()
        SettingsRepository.DarkMode.LIGHT -> false
        SettingsRepository.DarkMode.DARK -> true
    }

    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            // The bottom bar puts content directly behind the system navigation
            // bar, so its icons have to follow the theme too or they vanish.
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = LandPointTypography,
        shapes = LandPointShapes,
        content = content
    )
}
