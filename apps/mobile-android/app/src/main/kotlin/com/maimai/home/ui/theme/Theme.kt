package com.maimai.home.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Color palette adapted from `apps/design/N.html` design tokens.
 *
 * Source HTML uses Material 3 design tokens (primary, on-primary, surface,
 * surface-container-low/lowest/high, etc.). The hex values below mirror the
 * Tailwind config in `apps/design/1.html` — kept verbatim so the Compose UI
 * matches the design pixel-for-pixel.
 */

// ── Light scheme ─────────────────────────────────────────────────────────
private val LightPrimary = Color(0xFF0050CB)
private val LightOnPrimary = Color(0xFFFFFFFF)
private val LightPrimaryContainer = Color(0xFF0066FF)
private val LightOnPrimaryContainer = Color(0xFFF8F7FF)

private val LightSecondary = Color(0xFF525F73)
private val LightOnSecondary = Color(0xFFFFFFFF)
private val LightSecondaryContainer = Color(0xFFD6E3FB)
private val LightOnSecondaryContainer = Color(0xFF586579)

private val LightTertiary = Color(0xFF006278)
private val LightOnTertiary = Color(0xFFFFFFFF)
private val LightTertiaryContainer = Color(0xFF007C98)
private val LightOnTertiaryContainer = Color(0xFFEFFAFF)

private val LightError = Color(0xFFBA1A1A)
private val LightOnError = Color(0xFFFFFFFF)
private val LightErrorContainer = Color(0xFFFFDAD6)
private val LightOnErrorContainer = Color(0xFF93000A)

private val LightBackground = Color(0xFFF8F9FA)
private val LightOnBackground = Color(0xFF191C1D)
private val LightSurface = Color(0xFFF8F9FA)
private val LightOnSurface = Color(0xFF191C1D)
private val LightSurfaceVariant = Color(0xFFE1E3E4)
private val LightOnSurfaceVariant = Color(0xFF424656)
private val LightOutline = Color(0xFF727687)
private val LightOutlineVariant = Color(0xFFC2C6D8)
private val LightInverseSurface = Color(0xFF2E3132)
private val LightInverseOnSurface = Color(0xFFF0F1F2)
private val LightInversePrimary = Color(0xFFB3C5FF)
private val LightSurfaceTint = Color(0xFF0054D6)
private val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
private val LightSurfaceContainerLow = Color(0xFFF3F4F5)
private val LightSurfaceContainer = Color(0xFFEDEEEF)
private val LightSurfaceContainerHigh = Color(0xFFE7E8E9)
private val LightSurfaceContainerHighest = Color(0xFFE1E3E4)
private val LightSurfaceDim = Color(0xFFD9DADB)
private val LightSurfaceBright = Color(0xFFF8F9FA)

// ── Dark scheme (derived: invert / desaturate the same hue family) ──────
private val DarkPrimary = Color(0xFFB3C5FF)
private val DarkOnPrimary = Color(0xFF00257A)
private val DarkPrimaryContainer = Color(0xFF003FA4)
private val DarkOnPrimaryContainer = Color(0xFFDAE1FF)

private val DarkSecondary = Color(0xFFBAC7DE)
private val DarkOnSecondary = Color(0xFF243042)
private val DarkSecondaryContainer = Color(0xFF3B485A)
private val DarkOnSecondaryContainer = Color(0xFFD6E3FB)

private val DarkTertiary = Color(0xFF4CD6FF)
private val DarkOnTertiary = Color(0xFF001F28)
private val DarkTertiaryContainer = Color(0xFF004E60)
private val DarkOnTertiaryContainer = Color(0xFFB7EAFF)

private val DarkError = Color(0xFFFFB4AB)
private val DarkOnError = Color(0xFF690005)
private val DarkErrorContainer = Color(0xFF93000A)
private val DarkOnErrorContainer = Color(0xFFFFDAD6)

private val DarkBackground = Color(0xFF111315)
private val DarkOnBackground = Color(0xFFE2E2E3)
private val DarkSurface = Color(0xFF111315)
private val DarkOnSurface = Color(0xFFE2E2E3)
private val DarkSurfaceVariant = Color(0xFF424656)
private val DarkOnSurfaceVariant = Color(0xFFC2C6D8)
private val DarkOutline = Color(0xFF8C90A1)
private val DarkOutlineVariant = Color(0xFF424656)
private val DarkInverseSurface = Color(0xFFE2E2E3)
private val DarkInverseOnSurface = Color(0xFF2E3132)
private val DarkInversePrimary = Color(0xFF0050CB)
private val DarkSurfaceTint = Color(0xFFB3C5FF)
private val DarkSurfaceContainerLowest = Color(0xFF0B0D0F)
private val DarkSurfaceContainerLow = Color(0xFF191C1D)
private val DarkSurfaceContainer = Color(0xFF1D2022)
private val DarkSurfaceContainerHigh = Color(0xFF272A2C)
private val DarkSurfaceContainerHighest = Color(0xFF323537)
private val DarkSurfaceDim = Color(0xFF111315)
private val DarkSurfaceBright = Color(0xFF373A3C)

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    inverseSurface = LightInverseSurface,
    inverseOnSurface = LightInverseOnSurface,
    inversePrimary = LightInversePrimary,
    surfaceTint = LightSurfaceTint,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    surfaceDim = LightSurfaceDim,
    surfaceBright = LightSurfaceBright,
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    inverseSurface = DarkInverseSurface,
    inverseOnSurface = DarkInverseOnSurface,
    inversePrimary = DarkInversePrimary,
    surfaceTint = DarkSurfaceTint,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    surfaceDim = DarkSurfaceDim,
    surfaceBright = DarkSurfaceBright,
)

/**
 * Typography matched to design's Inter weights: 500/600 for display/title,
 * 400 for body. We use the system default font (Inter is a web-only choice).
 */
private val MaimaiTypography = Typography(
    displayLarge = TextStyle(fontSize = 57.sp, lineHeight = 64.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
)

private val MaimaiShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun MaimaiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = MaimaiTypography,
        shapes = MaimaiShapes,
        content = content,
    )
}
