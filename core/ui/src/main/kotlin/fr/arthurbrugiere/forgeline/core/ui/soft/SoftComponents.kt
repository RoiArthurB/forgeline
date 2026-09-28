package fr.arthurbrugiere.forgeline.core.ui.soft

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue

/** Shapes, widths and motion shared by every Soft screen. */
object SoftTokens {
    val Pill = RoundedCornerShape(percent = 50)
    /** A pressed or focused row's soft surface. */
    val RowCorner = RoundedCornerShape(20.dp)
    /** Bottom corners of a header field. */
    val FieldCorner = 28.dp
    val ThumbCorner = RoundedCornerShape(24.dp)
    /** The reading column on wide screens. */
    val MaxReadingWidth = 720.dp
    /** Longest comfortable line for running text (about 65-75 characters). */
    val MaxMeasure = 580.dp
    /** The one motion language: a soft spring, a little lively, never bouncy. */
    val SpringDamping = 0.85f
    val SpringStiffness = Spring.StiffnessMediumLow
    fun <T> spring() = spring<T>(dampingRatio = SpringDamping, stiffness = SpringStiffness)
}

/** False when the system "Remove animations" setting is on: every Soft motion then cuts instantly. */
@Composable
fun animationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
}

/** A clickable row with the soft pressed surface: a palette tint behind it while pressed or focused, not a ripple. */
fun Modifier.softPressable(role: Role? = null, onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    clip(SoftTokens.RowCorner)
        .background(if (pressed || focused) Soft.colors.surface else Color.Transparent)
        .clickable(interactionSource = interaction, indication = null, role = role, onClick = onClick)
}

/**
 * The tinted field that heads a screen, under the status bar with rounded bottom corners. A top-level screen passes
 * a large [title]; a detail screen passes [onBack] and puts its own header in [content].
 */
@Composable
fun SoftHeader(
    tint: Color,
    modifier: Modifier = Modifier,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    backDescription: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: (@Composable () -> Unit)? = null,
) {
    val colors = Soft.colors
    val animations = animationsEnabled()
    val field by animateColorAsState(tint, tween(if (animations) 400 else 0), label = "field")
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = SoftTokens.FieldCorner, bottomEnd = SoftTokens.FieldCorner))
            .background(field),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = SoftTokens.MaxReadingWidth)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = if (onBack != null) 4.dp else 20.dp, end = 8.dp, top = if (onBack != null) 4.dp else 16.dp, bottom = 20.dp),
        ) {
            if (title != null || onBack != null) {
                Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = backDescription, tint = colors.ink)
                        }
                    }
                    if (title != null) {
                        Text(
                            title,
                            style = if (onBack == null) Soft.type.title else Soft.type.name,
                            color = colors.ink,
                            maxLines = if (onBack == null) 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = if (onBack != null) 4.dp else 0.dp).semantics { heading() },
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    actions()
                }
            }
            if (content != null) {
                Box(Modifier.padding(start = if (onBack != null) 16.dp else 0.dp, end = 12.dp, top = if (title != null || onBack != null) 12.dp else 0.dp)) {
                    content()
                }
            }
        }
    }
}

/**
 * A pill track with an ember thumb that springs to the chosen option. As tall as its tallest label (48dp minimum),
 * so large font scales grow it instead of cutting words off; every label stays centred.
 */
@Composable
fun SoftSwitch(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = Soft.colors
    val animations = animationsEnabled()
    val thumbIndex by animateFloatAsState(
        selected.toFloat(),
        if (animations) spring(dampingRatio = 0.8f, stiffness = SoftTokens.SpringStiffness) else tween(0),
        label = "thumb",
    )
    val minHeight = with(LocalDensity.current) { 48.dp.roundToPx() }
    Layout(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SoftTokens.FieldCorner))
            .background(colors.track)
            .padding(4.dp)
            .selectableGroup(),
        content = {
            Box(
                Modifier
                    .shadow(6.dp, SoftTokens.ThumbCorner, ambientColor = colors.thumb, spotColor = colors.thumb)
                    .background(colors.thumb, SoftTokens.ThumbCorner),
            )
            options.forEachIndexed { index, option ->
                val isSelected = index == selected
                Box(
                    Modifier
                        .clip(SoftTokens.ThumbCorner)
                        .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(index) })
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // Shrinks a word that can't wrap (at very large font scales) instead of cutting it off.
                    Text(
                        option,
                        style = Soft.type.control,
                        color = if (isSelected) colors.onThumb else colors.inkMuted,
                        textAlign = TextAlign.Center,
                        autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = Soft.type.control.fontSize),
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val segment = constraints.maxWidth / options.size
        // Measure each label at its real width first; the tallest one sets the height for all.
        val labels = measurables.drop(1).map { it.measure(Constraints(minWidth = segment, maxWidth = segment, minHeight = minHeight)) }
        val height = labels.maxOf { it.height }
        val thumb = measurables.first().measure(Constraints.fixed(segment, height))
        layout(constraints.maxWidth, height) {
            thumb.placeRelative((thumbIndex * segment).toInt(), 0)
            labels.forEachIndexed { index, label -> label.placeRelative(index * segment, (height - label.height) / 2) }
        }
    }
}

/**
 * Pill tabs that scroll sideways when there are too many for the width: the selected one is the ember thumb.
 * Sits on the ground, under a header field; used for a page's sections (a repository's README, Code, Issues...).
 */
@Composable
fun SoftChipTabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = Soft.colors
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.ground)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = index == selected
            Box(
                Modifier
                    .clip(SoftTokens.Pill)
                    .background(if (isSelected) colors.thumb else colors.surface)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(index) })
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(option, style = Soft.type.control, color = if (isSelected) colors.onThumb else colors.ink, maxLines = 1)
            }
        }
    }
}

/** An empty or error state: a title, one line of cause or reassurance, and optionally one filled pill action. */
@Composable
fun SoftNotice(title: String, body: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    val colors = Soft.colors
    Column(modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(horizontal = 20.dp, vertical = 32.dp)) {
        Text(title, style = Soft.type.name, color = colors.ink)
        Spacer(Modifier.height(6.dp))
        Text(body, style = Soft.type.body, color = colors.inkMuted, modifier = Modifier.widthIn(max = SoftTokens.MaxMeasure))
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            SoftButton(action, onAction)
        }
    }
}

/** The filled ember pill: one primary action. */
@Composable
fun SoftButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Soft.colors
    Box(
        modifier
            .clip(SoftTokens.Pill)
            .background(colors.thumb)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Soft.type.control, color = colors.onThumb)
    }
}

/** A quiet pill: a secondary action on a tinted track. */
@Composable
fun SoftTonalButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Soft.colors
    Box(
        modifier
            .clip(SoftTokens.Pill)
            .background(colors.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Soft.type.control, color = colors.ink)
    }
}

/** Soft placeholder shapes while a list loads for the first time; announced once as [description]. */
@Composable
fun SoftLoadingRows(description: String, modifier: Modifier = Modifier, rows: Int = 5, leadingDot: Boolean = true) {
    val colors = Soft.colors
    Column(
        modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(top = 16.dp).clearAndSetSemantics { contentDescription = description },
    ) {
        repeat(rows) { index ->
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp)) {
                if (leadingDot) {
                    Box(Modifier.size(14.dp).background(colors.surface, CircleShape))
                    Spacer(Modifier.width(16.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.fillMaxWidth(0.25f).height(12.dp).background(colors.surface, SoftTokens.Pill))
                    Box(Modifier.fillMaxWidth(if (index % 2 == 0) 0.6f else 0.45f).height(20.dp).background(colors.surface, SoftTokens.Pill))
                    Box(Modifier.fillMaxWidth(0.95f).height(12.dp).background(colors.surface, SoftTokens.Pill))
                }
            }
        }
    }
}

/** Keeps the status bar readable once a header field has scrolled away. */
@Composable
fun SoftStatusBarScrim(visible: Boolean, modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .fillMaxWidth()
            .windowInsetsTopHeight(WindowInsets.statusBars)
            .background(if (visible) Soft.colors.ground.copy(alpha = 0.94f) else Color.Transparent),
    )
}

/** A small section heading inside a list, set in the display face. */
@Composable
fun SoftSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = Soft.type.control.copy(fontSize = 17.sp, lineHeight = 22.sp),
        color = Soft.colors.ink,
        modifier = modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp).semantics { heading() },
    )
}

/** A soft rounded tag: a count, a state or a label, on a tinted pill. */
@Composable
fun SoftTag(text: String, modifier: Modifier = Modifier, background: Color = Soft.colors.surface, content: Color = Soft.colors.ink) {
    Text(
        text,
        style = Soft.type.label.copy(fontSize = 12.sp, lineHeight = 16.sp),
        color = content,
        maxLines = 1,
        modifier = modifier.clip(SoftTokens.Pill).background(background).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
