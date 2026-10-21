package fr.arthurbrugiere.forgeline.core.ui.soft

import androidx.compose.foundation.LocalIndication
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
import fr.arthurbrugiere.forgeline.core.model.HeaderStyle
import fr.arthurbrugiere.forgeline.core.ui.R

/*
 * Forgeline's own visual world, Ink Compact: an ink band carries each screen's name and its one axis, and below it
 * a fast, hairline-ruled list where every item opens with a big icon tile. One typeface, one marigold accent.
 */

@Immutable
data class SoftColors(
    val ground: Color,
    val ink: Color,
    val inkMuted: Color,
    /** Marigold as text and marks; tuned per theme to stay readable on the ground (a deep amber in light). */
    val accent: Color,
    /** Marigold as a fill: the selected tab's underline and bar, unread dots, the send tile, filled buttons. */
    val thumb: Color,
    /** Text and icons on [thumb]. */
    val onThumb: Color,
    /** The veil laid over anything pressed or focused. */
    val track: Color,
    /** Icon tiles, a pressed row, tags, loading shapes, code. */
    val surface: Color,
    /** Floating things: menus, sheets and dialogs. */
    val raised: Color,
    /** One device-pixel rule between rows and under a band that would otherwise melt into the page. */
    val hairline: Color,
    /** Marigold's soft tint: a tile that needs you, a "+gained" figure. */
    val accentSoft: Color,
    /** Meaning tints, warm, cool and fresh: closed or failed, merged or running, open or passed. */
    val fields: List<Color>,
    /**
     * The same three meanings as text and marks on the page, where a tint would make a box: closed (red), merged
     * (violet), open (green). Readable on the ground, a pressed row and a floating surface.
     */
    val fieldInks: List<Color>,
    /** The header band every screen starts with. */
    val band: Color,
    val isDark: Boolean,
)

val SoftLight = SoftColors(
    ground = Color(0xFFF5F4F6),
    ink = Color(0xFF16181A),
    // Dark enough to stay readable on a tile, a meaning tint and a pressed row, not only on the ground.
    inkMuted = Color(0xFF606065),
    accent = Color(0xFF8F5600),
    thumb = Color(0xFFF5A524),
    onThumb = Color(0xFF16181A),
    track = Color(0x0F16181A),
    surface = Color(0xFFE8E7EB),
    raised = Color(0xFFFFFFFF),
    hairline = Color(0xFFDCDADF),
    accentSoft = Color(0xFFFCE9C0),
    fields = listOf(Color(0xFFFCE6DE), Color(0xFFE7E4F6), Color(0xFFDDF0E4)),
    fieldInks = listOf(Color(0xFFB42318), Color(0xFF6639BA), Color(0xFF17692E)),
    band = Color(0xFF16181A),
    isDark = false,
)

val SoftDark = SoftColors(
    ground = Color(0xFF111113),
    ink = Color(0xFFEDEDF0),
    inkMuted = Color(0xFF9C9BA3),
    accent = Color(0xFFFFC062),
    thumb = Color(0xFFF5A524),
    onThumb = Color(0xFF16181A),
    track = Color(0x14FFFFFF),
    surface = Color(0xFF1F1F24),
    raised = Color(0xFF1D1D21),
    hairline = Color(0xFF2A2A30),
    accentSoft = Color(0xFF3A2C12),
    fields = listOf(Color(0xFF3A221C), Color(0xFF25233A), Color(0xFF18291F)),
    fieldInks = listOf(Color(0xFFFF8A75), Color(0xFFB79CF0), Color(0xFF5FD38A)),
    // A step lighter than the page, so the band still reads as the top of the screen.
    band = Color(0xFF24242A),
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
fun SoftColors.amoled(): SoftColors =
    copy(ground = Color.Black, surface = Color(0xFF141417), raised = Color(0xFF17171A), hairline = Color(0xFF232327), band = Color(0xFF17171A))

/** The band in the style the person chose: ink (this palette's own), true black, or white. */
fun SoftColors.withHeader(style: HeaderStyle): SoftColors = when (style) {
    HeaderStyle.INK -> this
    HeaderStyle.BLACK -> copy(band = Color.Black)
    HeaderStyle.WHITE -> copy(band = if (isDark) Color(0xFFEDEDF0) else Color.White)
}

/** True when the band sits so close to the page that a hairline has to say where it ends. */
val SoftColors.bandNeedsRule: Boolean get() = contrastRatio(band, ground) < 1.12f

/** True when the band is dark, so what sits on it (and the status bar's icons) is light. */
val SoftColors.bandIsDark: Boolean get() = band.luminance() < 0.3f

/**
 * The palette for whatever sits on the band: its title, tabs, actions and a detail screen's header. A dark band gets
 * light ink whichever theme the page is in; a light one gets dark ink.
 */
fun SoftColors.onBand(): SoftColors {
    val dark = bandIsDark
    val base = if (dark) SoftDark else SoftLight
    return base.copy(
        ground = band,
        // On the band itself muted ink must also read on the band's own surface (a tag, a field).
        inkMuted = if (dark) Color(0xFFA6A5AB) else Color(0xFF5E5D63),
        surface = if (dark) Color(0xFF303037) else Color(0xFFDDDCE1),
        hairline = if (dark) Color(0xFF34343B) else Color(0xFFD3D1D7),
        raised = raised,
        band = band,
    )
}

/** Geist: one family for everything, in two weights (Regular 400 and SemiBold 600). Code stays monospace. */
val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
)

private fun geist(size: Float, line: Float, weight: FontWeight = FontWeight.Normal, tracking: Float = 0f, tabular: Boolean = false) =
    TextStyle(
        fontFamily = Geist,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = line.sp,
        letterSpacing = tracking.em,
        fontFeatureSettings = if (tabular) "tnum" else null,
    )

@Immutable
data class SoftType(
    /** A tab's name on its band ("Inbox", "Trending"). */
    val title: TextStyle = geist(28f, 32f, FontWeight.SemiBold, tracking = -0.03f),
    /** A detail screen's title on its band when it is a name (a repository, Settings). */
    val detailTitle: TextStyle = geist(24f, 29f, FontWeight.SemiBold, tracking = -0.02f),
    /** Who or what a detail screen is about when that is a sentence: an issue's title, a person's name, a job. */
    val hero: TextStyle = geist(22f, 27f, FontWeight.SemiBold, tracking = -0.02f),
    /** A release's version in the Feed, the row's headline. */
    val version: TextStyle = geist(20f, 24f, FontWeight.SemiBold, tracking = -0.02f),
    /** A section's title over its rows (Needs you, a settings group, a Feed day). */
    val section: TextStyle = geist(14f, 18f, FontWeight.SemiBold),
    /** A repository's name in a list, an account's name, a Notice's title. */
    val name: TextStyle = geist(16f, 21f, FontWeight.SemiBold, tracking = -0.005f),
    /** Ranks, gains and counts: tabular, so they line up down a list. */
    val figure: TextStyle = geist(13f, 18f, FontWeight.SemiBold, tabular = true),
    /** Tabs, buttons, a comment's author. */
    val control: TextStyle = geist(14f, 20f, FontWeight.SemiBold),
    val body: TextStyle = geist(15f, 22f),
    val secondary: TextStyle = geist(13.5f, 19f),
    val meta: TextStyle = geist(12f, 16f, tabular = true),
    val label: TextStyle = geist(12.5f, 17f, FontWeight.SemiBold),
    /** What a subject says of itself under its name in a header (a description, a bio), and what is written in a field. */
    val lead: TextStyle = geist(15f, 22f),
    /** The title line of a list row read at a glance (a thread in the Inbox, what an event is about in the Feed). */
    val rowTitle: TextStyle = geist(15f, 20f),
    /** Tags, labels and reactions. */
    val tag: TextStyle = geist(11.5f, 16f, FontWeight.SemiBold),
    /** A file's code, and code in Markdown. */
    val code: TextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 20.sp),
    /** Code read many lines at a time: a change's lines, a blame, a job's log. */
    val codeDense: TextStyle = code.copy(fontSize = 12.sp, lineHeight = 18.sp),
    /** Line numbers and hunk headers beside dense code: tabular, muted by whoever sets them. */
    val gutter: TextStyle = code.copy(fontSize = 11.sp, lineHeight = 18.sp, fontFeatureSettings = "tnum"),
)

val LocalSoftColors = staticCompositionLocalOf { SoftLight }
val LocalSoftType = staticCompositionLocalOf { SoftType() }

/**
 * Enters the Soft world: its colors, ink as the content color so untinted icons from the remaining Material pieces
 * take the palette, and the veil as what a press looks like on anything clickable that doesn't say otherwise.
 */
@Composable
fun SoftTheme(colors: SoftColors, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalSoftColors provides colors,
        LocalContentColor provides colors.ink,
        LocalIndication provides SoftIndication(colors.track),
        content = content,
    )
}

object Soft {
    val colors: SoftColors
        @Composable @ReadOnlyComposable
        get() = LocalSoftColors.current
    val type: SoftType
        @Composable @ReadOnlyComposable
        get() = LocalSoftType.current
}
