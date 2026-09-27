package com.example.ftnnavigation.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Za sada samo svetla tema, u FTN bojama (bez dinamičkih boja sa pozadine telefona).
private val FtnLightColorScheme = lightColorScheme(
    primary = FtnTeal,
    onPrimary = Color.White,
    primaryContainer = FtnTealLight,
    onPrimaryContainer = FtnTealDark,
    inversePrimary = FtnCyan,
    // Beli tekst na cijan podlozi nema dovoljan kontrast, zato tamna tirkizna.
    secondary = FtnCyan,
    onSecondary = FtnTealDark,
    secondaryContainer = FtnCyanLight,
    onSecondaryContainer = FtnTealDark,
    tertiary = FtnTealDark,
    onTertiary = Color.White,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = FtnSurfaceHigh,
    onSurfaceVariant = FtnOnSurfaceVariant,
    surfaceTint = FtnTeal,
    surfaceBright = Color.White,
    surfaceDim = FtnSurfaceHighest,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = FtnSurfaceLow,
    surfaceContainer = FtnSurface,
    surfaceContainerHigh = FtnSurfaceHigh,
    surfaceContainerHighest = FtnSurfaceHighest,
    inverseSurface = FtnTealDark,
    inverseOnSurface = Color.White,
    outline = FtnOutline,
    outlineVariant = FtnOutlineVariant,
)

@Composable
fun FTNNavigationTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = FtnLightColorScheme, content = content)
}
