package com.kike.musicplayer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Red = Color(0xFFEF4444)

/** Dark mode: black background, white text, red where the light theme is blue. */
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

/** Light mode: Material You colours (Android 12+) with an amber fallback; dark mode: black and red. */
@Composable
fun MusicPlayerTheme(dark: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = when {
        dark -> BlackRed
        android.os.Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        else -> lightColorScheme(primary = Color(0xFF8A5A00))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
