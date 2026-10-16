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
import fr.arthurbrugiere.forgeline.core.model.HeaderStyle
import fr.arthurbrugiere.forgeline.core.model.ThemeMode
import fr.arthurbrugiere.forgeline.core.ui.soft.Geist
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftColors
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftDark
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLight
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTheme
import fr.arthurbrugiere.forgeline.core.ui.soft.amoled
import fr.arthurbrugiere.forgeline.core.ui.soft.withHeader

fun ThemeMode.isDark(systemInDarkTheme: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemInDarkTheme
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

fun softColors(darkTheme: Boolean, amoledBlack: Boolean, headerStyle: HeaderStyle = HeaderStyle.INK): SoftColors = when {
    !darkTheme -> SoftLight
    amoledBlack -> SoftDark.amoled()
    else -> SoftDark
}.withHeader(headerStyle)

/**
 * Material's roles filled from the Soft palette, so the Material pieces the app still uses (dialogs, switches,
 * fields, menus, snackbars) look like the rest of it instead of stock Material.
 */
fun SoftColors.toMaterial(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = ground,
        primaryContainer = accentSoft,
        onPrimaryContainer = ink,
        // A snackbar's action, on the inverse (ink) bar: marigold reads on dark ink, deep amber on the pale ink of a dark theme.
        inversePrimary = if (isDark) SoftLight.accent else thumb,
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
        outlineVariant = hairline,
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

private fun geist(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = Geist, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)

/** Geist for every role: SemiBold for display, headline and title, Regular for reading, SemiBold for labels. */
val SoftTypography = Typography(
    displayLarge = geist(44, 50, FontWeight.SemiBold),
    displayMedium = geist(36, 42, FontWeight.SemiBold),
    displaySmall = geist(30, 36, FontWeight.SemiBold),
    headlineLarge = geist(26, 32, FontWeight.SemiBold),
    headlineMedium = geist(22, 28, FontWeight.SemiBold),
    headlineSmall = geist(20, 26, FontWeight.SemiBold),
    titleLarge = geist(18, 24, FontWeight.SemiBold),
    titleMedium = geist(16, 22, FontWeight.SemiBold),
    titleSmall = geist(14, 20, FontWeight.SemiBold),
    bodyLarge = geist(15, 22),
    bodyMedium = geist(14, 20),
    bodySmall = geist(12, 16),
    labelLarge = geist(14, 20, FontWeight.SemiBold),
    labelMedium = geist(12, 16, FontWeight.SemiBold),
    labelSmall = geist(11, 16, FontWeight.SemiBold),
)

/** Small, quiet corners: 6dp controls and fields, up to 12dp for a sheet. */
val SoftShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

@Composable
fun ForgelineTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    amoledBlack: Boolean = false,
    headerStyle: HeaderStyle = HeaderStyle.INK,
    content: @Composable () -> Unit,
) {
    val soft = softColors(darkTheme, amoledBlack, headerStyle)
    val scheme = remember(soft) { soft.toMaterial() }
    MaterialTheme(colorScheme = scheme, typography = SoftTypography, shapes = SoftShapes) {
        SoftTheme(soft, content)
    }
}
