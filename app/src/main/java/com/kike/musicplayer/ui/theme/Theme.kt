package com.kike.musicplayer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Red = Color(0xFFEF4444)
private val Blue = Color(0xFF3B82F6)

/** Dark mode with Red accent: pure black background, white text, red accents. */
private val BlackRed = darkColorScheme(
    primary = Red,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF5C1518),
    onPrimaryContainer = Color(0xFFFFDAD6),
    inversePrimary = Color(0xFFB3261E),
    secondary = Color(0xFFE8A09A),
    onSecondary = Color(0xFF3B0D0B),
    secondaryContainer = Color(0xFF3A1F1F),
    onSecondaryContainer = Color(0xFFFFDAD6),
    tertiary = Color(0xFFE8A09A),
    onTertiary = Color(0xFF3B0D0B),
    tertiaryContainer = Color(0xFF3A1F1F),
    onTertiaryContainer = Color(0xFFFFDAD6),
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF242424),
    onSurfaceVariant = Color(0xFFB8B8B8),
    surfaceTint = Red,
    inverseSurface = Color(0xFFE6E6E6),
    inverseOnSurface = Color(0xFF1A1A1A),
    outline = Color(0xFF6E6E6E),
    outlineVariant = Color(0xFF333333),
    surfaceBright = Color(0xFF2A2A2A),
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0D0D0D),
    surfaceContainer = Color(0xFF141414),
    surfaceContainerHigh = Color(0xFF1B1B1B),
    surfaceContainerHighest = Color(0xFF222222),
)

/** Dark mode with Blue accent: pure black background, white text, blue accents. */
private val BlackBlue = darkColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFDBEAFE),
    inversePrimary = Color(0xFF1D4ED8),
    secondary = Color(0xFF93C5FD),
    onSecondary = Color(0xFF172554),
    secondaryContainer = Color(0xFF1E293B),
    onSecondaryContainer = Color(0xFFDBEAFE),
    tertiary = Color(0xFF93C5FD),
    onTertiary = Color(0xFF172554),
    tertiaryContainer = Color(0xFF1E293B),
    onTertiaryContainer = Color(0xFFDBEAFE),
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF242424),
    onSurfaceVariant = Color(0xFFB8B8B8),
    surfaceTint = Blue,
    inverseSurface = Color(0xFFE6E6E6),
    inverseOnSurface = Color(0xFF1A1A1A),
    outline = Color(0xFF6E6E6E),
    outlineVariant = Color(0xFF333333),
    surfaceBright = Color(0xFF2A2A2A),
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0D0D0D),
    surfaceContainer = Color(0xFF141414),
    surfaceContainerHigh = Color(0xFF1B1B1B),
    surfaceContainerHighest = Color(0xFF222222),
)

/** Light mode with Red accent. */
private val LightRed = lightColorScheme(
    primary = Color(0xFFDC2626),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410002),
    inversePrimary = Color(0xFFFFB4AB),
    secondary = Color(0xFF775654),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDAD6),
    onSecondaryContainer = Color(0xFF2C1514),
    surfaceTint = Color(0xFFDC2626),
)

/** Light mode with Blue accent. */
private val LightBlue = lightColorScheme(
    primary = Color(0xFF1D4ED8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E3A8A),
    inversePrimary = Color(0xFF93C5FD),
    secondary = Color(0xFF475569),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E8F0),
    onSecondaryContainer = Color(0xFF0F172A),
    surfaceTint = Color(0xFF1D4ED8),
)

/**
 * MusicPlayerTheme supporting both dark & light modes with user-configurable Red or Blue accents.
 */
@Composable
fun MusicPlayerTheme(
    dark: Boolean,
    accentColor: String = "red",
    content: @Composable () -> Unit,
) {
    val isBlue = accentColor.equals("blue", ignoreCase = true)
    val scheme = when {
        dark && isBlue -> BlackBlue
        dark -> BlackRed
        isBlue -> LightBlue
        else -> LightRed
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
