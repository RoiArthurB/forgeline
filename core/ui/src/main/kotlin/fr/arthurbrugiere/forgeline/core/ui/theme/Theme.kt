package fr.arthurbrugiere.forgeline.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.arthurbrugiere.forgeline.core.model.ThemeMode
import fr.arthurbrugiere.forgeline.core.ui.soft.Gabarito
import fr.arthurbrugiere.forgeline.core.ui.soft.Lexend
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftColors
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftDark
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLight
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTheme
import fr.arthurbrugiere.forgeline.core.ui.soft.amoled

fun ThemeMode.isDark(systemInDarkTheme: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemInDarkTheme
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

fun softColors(darkTheme: Boolean, amoledBlack: Boolean): SoftColors = when {
    !darkTheme -> SoftLight
    amoledBlack -> SoftDark.amoled()
    else -> SoftDark
}

/**
 * Material's roles filled from the Soft palette, so the Material pieces the app still uses (dialogs, switches,
 * fields, menus, snackbars) look like the rest of it instead of stock Material.
 */
fun SoftColors.toMaterial(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = ground,
        primaryContainer = fields[0],
        onPrimaryContainer = ink,
        inversePrimary = thumb,
        secondary = inkMuted,
        onSecondary = ground,
        secondaryContainer = surface,
        onSecondaryContainer = ink,
        tertiary = accent,
        onTertiary = ground,
        tertiaryContainer = fields[1],
        onTertiaryContainer = ink,
        background = ground,
        onBackground = ink,
        surface = ground,
        onSurface = ink,
        surfaceVariant = surface,
        onSurfaceVariant = inkMuted,
        surfaceTint = Color.Transparent,
        inverseSurface = ink,
        inverseOnSurface = ground,
        outline = inkMuted,
        outlineVariant = track.copy(alpha = track.alpha * 2),
        surfaceBright = ground,
        surfaceDim = surface,
        surfaceContainerLowest = ground,
        surfaceContainerLow = ground,
        surfaceContainer = raised,
        surfaceContainerHigh = raised,
        surfaceContainerHighest = surface,
        scrim = Color.Black,
    )
}

private fun display(size: Int, line: Int, weight: FontWeight = FontWeight.Bold) =
    TextStyle(fontFamily = Gabarito, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)

private fun text(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = Lexend, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)

/** Gabarito for display, headline and title roles; Lexend for everything read or tapped. */
val SoftTypography = Typography(
    displayLarge = display(52, 58),
    displayMedium = display(44, 50),
    displaySmall = display(36, 42),
    headlineLarge = display(32, 38),
    headlineMedium = display(28, 34),
    headlineSmall = display(24, 30),
    titleLarge = display(21, 26),
    titleMedium = display(17, 22, FontWeight.Medium),
    titleSmall = display(15, 20, FontWeight.Medium),
    bodyLarge = text(16, 24),
    bodyMedium = text(15, 22),
    bodySmall = text(13, 18),
    labelLarge = text(14, 20, FontWeight.Medium),
    labelMedium = text(13, 18, FontWeight.Medium),
    labelSmall = text(12, 16, FontWeight.Medium),
)

val SoftShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun ForgelineTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    amoledBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val soft = softColors(darkTheme, amoledBlack)
    val scheme = remember(soft) { soft.toMaterial() }
    MaterialTheme(colorScheme = scheme, typography = SoftTypography, shapes = SoftShapes) {
        SoftTheme(soft, content)
    }
}
