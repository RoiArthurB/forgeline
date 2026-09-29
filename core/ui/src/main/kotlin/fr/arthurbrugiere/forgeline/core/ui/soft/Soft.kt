package fr.arthurbrugiere.forgeline.core.ui.soft

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import fr.arthurbrugiere.forgeline.core.ui.R

/*
 * Forgeline's own visual world: a warm, rounded display face and very readable text on soft surfaces, with one
 * vivid ember accent. Nothing is boxed: hierarchy comes from type, space and tone, never from divider lines.
 */

@Immutable
data class SoftColors(
    val ground: Color,
    val ink: Color,
    val inkMuted: Color,
    /** Ember as text and marks; tuned per theme to stay readable on the ground. */
    val accent: Color,
    /** The pill switch's thumb, and filled buttons. */
    val thumb: Color,
    val onThumb: Color,
    /** The pill switch's track, laid over a field. */
    val track: Color,
    /** Soft surface behind a pressed row, loading shapes and snackbars. */
    val surface: Color,
    /** Floating things: the navigation bar, menus and sheets, lifted off the ground by a soft shadow. */
    val raised: Color,
    /** Header field tints: warm, cool and fresh, one per section of a surface (Trending's periods). */
    val fields: List<Color>,
    val isDark: Boolean,
)

val SoftLight = SoftColors(
    ground = Color(0xFFFAFAFB),
    ink = Color(0xFF1E1B2E),
    // Dark enough to stay readable on the switch track laid over every field tint, not only on the ground.
    inkMuted = Color(0xFF5A566B),
    // Deep enough to stay readable on the pressed/focused surface as well as the ground.
    accent = Color(0xFFB83A17),
    thumb = Color(0xFFFF6B3D),
    onThumb = Color(0xFF1E1B2E),
    track = Color(0x0F1E1B2E),
    surface = Color(0xFFF0EFF3),
    raised = Color(0xFFFFFFFF),
    fields = listOf(Color(0xFFFFE6DC), Color(0xFFECE6FF), Color(0xFFDDF3EA)),
    isDark = false,
)

val SoftDark = SoftColors(
    ground = Color(0xFF15131C),
    ink = Color(0xFFF1EEF6),
    inkMuted = Color(0xFFA7A2B8),
    accent = Color(0xFFFF8A5C),
    thumb = Color(0xFFFF6B3D),
    onThumb = Color(0xFF1A1216),
    track = Color(0x12FFFFFF),
    surface = Color(0xFF221F2C),
    raised = Color(0xFF282533),
    fields = listOf(Color(0xFF2B1E1F), Color(0xFF221F33), Color(0xFF1A2724)),
    isDark = true,
)

/** WCAG contrast ratio between two opaque colors. */
fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return maxOf(la, lb) / minOf(la, lb)
}

/** Black or white, whichever reads better on [background]; for colors the forge picks (labels). */
fun readableOn(background: Color): Color =
    if (contrastRatio(Color.Black, background) >= contrastRatio(Color.White, background)) Color.Black else Color.White

/** The dark palette on a true-black ground, for OLED screens (the AMOLED black setting). */
fun SoftColors.amoled(): SoftColors = copy(ground = Color.Black, surface = Color(0xFF16141D), raised = Color(0xFF1C1925))

/** Gabarito: display, names and figures (two weights only). Lexend: every line of reading text. */
val Gabarito = FontFamily(
    Font(R.font.gabarito_medium, FontWeight.Medium),
    Font(R.font.gabarito_bold, FontWeight.Bold),
)
val Lexend = FontFamily(
    Font(R.font.lexend_regular, FontWeight.Normal),
    Font(R.font.lexend_medium, FontWeight.Medium),
)

@Immutable
data class SoftType(
    val title: TextStyle = TextStyle(fontFamily = Gabarito, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.01).em),
    val name: TextStyle = TextStyle(fontFamily = Gabarito, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 25.sp),
    /** Ranks and counts: tabular, so they line up down a list. */
    val figure: TextStyle = TextStyle(fontFamily = Gabarito, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 20.sp, fontFeatureSettings = "tnum"),
    val control: TextStyle = TextStyle(fontFamily = Gabarito, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    val body: TextStyle = TextStyle(fontFamily = Lexend, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 24.sp),
    val secondary: TextStyle = TextStyle(fontFamily = Lexend, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    val meta: TextStyle = TextStyle(fontFamily = Lexend, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, fontFeatureSettings = "tnum"),
    val label: TextStyle = TextStyle(fontFamily = Lexend, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
)

val LocalSoftColors = staticCompositionLocalOf { SoftLight }
val LocalSoftType = staticCompositionLocalOf { SoftType() }

/**
 * Enters the Soft world: its colors, plus ink as the content color so ripples and untinted icons from the
 * remaining Material pieces take the palette instead of the old (or dynamic) Material scheme.
 */
@Composable
fun SoftTheme(colors: SoftColors, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSoftColors provides colors, LocalContentColor provides colors.ink, content = content)
}

object Soft {
    val colors: SoftColors
        @Composable @ReadOnlyComposable get() = LocalSoftColors.current
    val type: SoftType
        @Composable @ReadOnlyComposable get() = LocalSoftType.current
}
