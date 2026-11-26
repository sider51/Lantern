package com.sider.lantern

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

internal fun lanternColors(dark: Boolean) = if (dark) darkColorScheme(
    primary = Color(0xFFDAB77A), onPrimary = Color(0xFF412D10),
    primaryContainer = Color(0xFF533B1C), onPrimaryContainer = Color(0xFFFFDFB0),
    secondary = Color(0xFFD3BF9E), onSecondary = Color(0xFF382E1D),
    secondaryContainer = Color(0xFF4C402C), onSecondaryContainer = Color(0xFFF1DBB8),
    tertiary = Color(0xFFBECFAD), onTertiary = Color(0xFF29371D),
    tertiaryContainer = Color(0xFF3F4A33), onTertiaryContainer = Color(0xFFDAEAC8),
    background = Color(0xFF1D1C19), onBackground = Color(0xFFECE6DB),
    surface = Color(0xFF1D1C19), onSurface = Color(0xFFECE6DB),
    surfaceDim = Color(0xFF191814), surfaceBright = Color(0xFF433E36),
    surfaceContainerLowest = Color(0xFF151410), surfaceContainerLow = Color(0xFF25221D),
    surfaceContainer = Color(0xFF302C25), surfaceContainerHigh = Color(0xFF37322A),
    surfaceContainerHighest = Color(0xFF403A31), surfaceVariant = Color(0xFF4F4639),
    onSurfaceVariant = Color(0xFFD2C6B4), outline = Color(0xFF9C8E7A), outlineVariant = Color(0xFF51483C),
    inverseSurface = Color(0xFFEAE2D6), inverseOnSurface = Color(0xFF353128), inversePrimary = Color(0xFF79531D),
) else lightColorScheme(
    primary = Color(0xFF79531D), onPrimary = Color.White,
    primaryContainer = Color(0xFFEED6AE), onPrimaryContainer = Color(0xFF3E290C),
    secondary = Color(0xFF6E5C3F), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEFDFC5), onSecondaryContainer = Color(0xFF302515),
    tertiary = Color(0xFF536646), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDCE8D1), onTertiaryContainer = Color(0xFF25341E),
    background = Color(0xFFFAF8F4), onBackground = Color(0xFF28241D),
    surface = Color(0xFFFAF8F4), onSurface = Color(0xFF28241D),
    surfaceDim = Color(0xFFE2D9CD), surfaceBright = Color(0xFFFFFBF6),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF6F0E7),
    surfaceContainer = Color(0xFFF0E9DF), surfaceContainerHigh = Color(0xFFECE3D6),
    surfaceContainerHighest = Color(0xFFE5DBCE), surfaceVariant = Color(0xFFECE1D0),
    onSurfaceVariant = Color(0xFF544A3C), outline = Color(0xFF786D5D), outlineVariant = Color(0xFFD4C7B4),
    inverseSurface = Color(0xFF363027), inverseOnSurface = Color(0xFFF6EEE1), inversePrimary = Color(0xFFDAB77A),
)
