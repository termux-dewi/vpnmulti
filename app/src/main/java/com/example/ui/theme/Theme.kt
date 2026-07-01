package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = HighDensityPrimary,
    onPrimary = HighDensityOnPrimary,
    primaryContainer = HighDensityPrimaryContainer,
    onPrimaryContainer = HighDensityOnPrimaryContainer,
    secondary = HighDensitySecondary,
    onSecondary = HighDensityOnSecondary,
    background = HighDensityBg,
    onBackground = HighDensityTextPrimary,
    surface = HighDensitySurface,
    onSurface = HighDensityTextPrimary,
    onSurfaceVariant = HighDensityTextSecondary,
    outlineVariant = HighDensityOutlineVariant,
    error = HighDensityRed
  )

private val LightColorScheme = DarkColorScheme // Enforce the gorgeous premium high density dark mode in all themes

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Dynamic color is disabled by default to keep the High Density branding intact
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      else -> DarkColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
