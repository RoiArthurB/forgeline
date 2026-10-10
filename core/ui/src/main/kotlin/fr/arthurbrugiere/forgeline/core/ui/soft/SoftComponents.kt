package fr.arthurbrugiere.forgeline.core.ui.soft

import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.PaddingValues
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily

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

/**
 * A clickable row with the soft pressed surface: a palette tint behind it while pressed or focused, not a ripple,
 * and a slight squish (2%) that springs back on release.
 */
fun Modifier.softPressable(
    role: Role? = null,
    /** What a long press does and how a screen reader names it, where the row has one (selecting it, say). */
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val squish by animateFloatAsState(
        if (pressed && animationsEnabled()) PressedScale else 1f,
        spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium),
        label = "squish",
    )
    graphicsLayer { scaleX = squish; scaleY = squish }
        .clip(SoftTokens.RowCorner)
        .background(if (pressed || focused) Soft.colors.surface else Color.Transparent)
        .combinedClickable(
            interactionSource = interaction, indication = null, role = role,
            onLongClick = onLongClick, onLongClickLabel = onLongClickLabel, onClick = onClick,
        )
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
            // A top-level screen: its title and actions on one row. A detail screen: back and actions on a row, then
            // its title (if any) set large in the field like every other hero.
            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = backDescription, tint = colors.ink)
                    }
                    Spacer(Modifier.weight(1f))
                } else if (title != null) {
                    Text(
                        title,
                        style = Soft.type.title,
                        color = colors.ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                actions()
            }
            if (onBack != null && title != null) {
                Text(
                    title,
                    style = Soft.type.detailTitle,
                    color = colors.ink,
                    modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp).semantics { heading() },
                )
            }
            if (content != null) {
                Box(Modifier.padding(start = if (onBack != null) 16.dp else 0.dp, end = 12.dp, top = 12.dp)) {
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
fun SoftSwitch(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Drawn before an option's label, in its color (a forge's logo). */
    leading: (@Composable (index: Int, color: Color) -> Unit)? = null,
) {
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
                    val color = if (isSelected) colors.onThumb else colors.inkMuted
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                        leading?.invoke(index, color)
                        // Shrinks a word that can't wrap (at very large font scales) instead of cutting it off, down to 12dp on screen.
                        Text(
                            option,
                            style = Soft.type.control,
                            color = color,
                            textAlign = TextAlign.Center,
                            // A single word must shrink to fit: allowed to break, it would "fit" by wrapping mid-word.
                            softWrap = ' ' in option,
                            maxLines = if (' ' in option) Int.MAX_VALUE else 1,
                            autoSize = TextAutoSize.StepBased(minFontSize = (12f / LocalDensity.current.fontScale).sp, maxFontSize = Soft.type.control.fontSize),
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
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
 * The pill switch for more options than fit the width: it scrolls sideways, on the same track, and the ember thumb
 * springs to the chosen option like SoftSwitch's. Used for a page's sections (a repository's README, Code, Issues...).
 */
@Composable
fun SoftChipTabs(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Drawn before an option's label, in its color (a forge's logo). */
    leading: (@Composable (index: Int, color: Color) -> Unit)? = null,
    /** The strip behind the chips: the ground by default, transparent inside a header. */
    background: Color = Soft.colors.ground,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
) {
    val colors = Soft.colors
    val animations = animationsEnabled()
    val density = LocalDensity.current
    // Where each option sits, so the thumb can travel between options of different widths.
    val bounds = remember(options) { mutableStateListOf<Pair<Dp, Dp>>().apply { repeat(options.size) { add(0.dp to 0.dp) } } }
    val target = bounds.getOrNull(selected) ?: (0.dp to 0.dp)
    val scroll = rememberScrollState()
    var viewport by remember { mutableIntStateOf(0) }
    // Bring the chosen option into view: a strip wider than the screen can open on an option that is cut off.
    LaunchedEffect(selected, target, viewport) {
        if (target.second <= 0.dp || viewport <= 0) return@LaunchedEffect
        val start = with(density) { (contentPadding.calculateLeftPadding(LayoutDirection.Ltr) + 4.dp + target.first).roundToPx() }
        val end = start + with(density) { target.second.roundToPx() }
        val edge = with(density) { 8.dp.roundToPx() }
        val to = when {
            start - edge < scroll.value -> start - edge
            end + edge > scroll.value + viewport -> end + edge - viewport
            else -> return@LaunchedEffect
        }.coerceIn(0, scroll.maxValue)
        if (animations) scroll.animateScrollTo(to) else scroll.scrollTo(to)
    }
    val motion: androidx.compose.animation.core.AnimationSpec<Dp> =
        if (animations) spring(dampingRatio = 0.8f, stiffness = SoftTokens.SpringStiffness) else tween(0)
    val thumbX by animateDpAsState(target.first, motion, label = "thumbX")
    val thumbWidth by animateDpAsState(target.second, motion, label = "thumbWidth")
    Box(
        modifier
            .widthIn(max = SoftTokens.MaxReadingWidth)
            .fillMaxWidth()
            .background(background)
            .onSizeChanged { viewport = it.width }
            .horizontalScroll(scroll)
            .padding(contentPadding),
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(SoftTokens.FieldCorner))
                .background(colors.track)
                .padding(4.dp),
        ) {
            if (thumbWidth > 0.dp) {
                Box(
                    Modifier
                        .offset(x = thumbX)
                        .width(thumbWidth)
                        .height(48.dp)
                        .shadow(6.dp, SoftTokens.ThumbCorner, ambientColor = colors.thumb, spotColor = colors.thumb)
                        .background(colors.thumb, SoftTokens.ThumbCorner),
                )
            }
            Row(Modifier.selectableGroup()) {
                options.forEachIndexed { index, option ->
                    val isSelected = index == selected
                    Box(
                        Modifier
                            .onPlaced { with(density) { bounds[index] = it.positionInParent().x.toDp() to it.size.width.toDp() } }
                            .clip(SoftTokens.ThumbCorner)
                            .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(index) })
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val color = if (isSelected) colors.onThumb else colors.inkMuted
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            leading?.invoke(index, color)
                            // A logo alone needs no label.
                            if (option.isNotEmpty()) Text(option, style = Soft.type.control, color = color, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The one text field: a soft filled pill with its label as the placeholder, an optional leading glyph and trailing
 * action, and an error line under it. No outline, like the rest of the world. With [singleLine] off it grows with
 * what is written, from [minLines] to [maxLines], on a rounded field instead of a pill.
 */
@Composable
fun SoftTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    background: Color = Soft.colors.surface,
    error: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    readOnly: Boolean = false,
) {
    val colors = Soft.colors
    Column(modifier) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            readOnly = readOnly,
            textStyle = Soft.type.body.copy(fontSize = 16.sp, color = colors.ink),
            cursorBrush = SolidColor(colors.accent),
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { field -> SoftFieldBox(value.isEmpty(), placeholder, background, singleLine, leading, trailing, field) },
        )
        if (error != null) {
            Text(error, style = Soft.type.secondary, color = colors.accent, modifier = Modifier.padding(start = 20.dp, top = 6.dp))
        }
    }
}

/**
 * The same field for whoever places the cursor too: text put there by something other than typing (a quote, a comment
 * to rewrite) can leave it after what was put.
 */
@Composable
fun SoftTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    background: Color = Soft.colors.surface,
    error: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    readOnly: Boolean = false,
) {
    val colors = Soft.colors
    Column(modifier) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            readOnly = readOnly,
            textStyle = Soft.type.body.copy(fontSize = 16.sp, color = colors.ink),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = keyboardOptions,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { field -> SoftFieldBox(value.text.isEmpty(), placeholder, background, singleLine, null, null, field) },
        )
        if (error != null) {
            Text(error, style = Soft.type.secondary, color = colors.accent, modifier = Modifier.padding(start = 20.dp, top = 6.dp))
        }
    }
}

@Composable
private fun SoftFieldBox(
    isEmpty: Boolean,
    placeholder: String,
    background: Color,
    singleLine: Boolean,
    leading: (@Composable () -> Unit)?,
    trailing: (@Composable () -> Unit)?,
    field: @Composable () -> Unit,
) {
    val colors = Soft.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(if (singleLine) SoftTokens.Pill else RoundedCornerShape(24.dp))
            .background(background)
            .heightIn(min = 52.dp)
            .padding(start = if (leading != null) 16.dp else 20.dp, end = if (trailing != null) 4.dp else 20.dp)
            .padding(vertical = if (singleLine) 0.dp else 14.dp),
        verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(10.dp))
        }
        Box(Modifier.weight(1f)) {
            if (isEmpty) {
                Text(placeholder, style = Soft.type.body.copy(fontSize = 16.sp), color = colors.inkMuted, maxLines = 1)
            }
            field()
        }
        trailing?.invoke()
    }
}

/** An empty or error state: a title, one line of cause or reassurance, and optionally one filled pill action. */
@Composable
fun SoftNotice(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
    /** A quieter way out beside the main action (a signed-out wall's "Browse Trending"). */
    secondaryAction: String? = null,
    onSecondaryAction: () -> Unit = {},
) {
    val colors = Soft.colors
    Column(modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(horizontal = 20.dp, vertical = 32.dp)) {
        Text(title, style = Soft.type.name, color = colors.ink, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(6.dp))
        Text(body, style = Soft.type.body, color = colors.inkMuted, modifier = Modifier.widthIn(max = SoftTokens.MaxMeasure))
        if (action != null || secondaryAction != null) {
            Spacer(Modifier.height(20.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                action?.let { SoftButton(it, onAction) }
                secondaryAction?.let { SoftTonalButton(it, onSecondaryAction) }
            }
        }
    }
}

/** The filled ember pill: one primary action. Not [enabled], it goes quiet (a tinted track, muted ink) and can't be pressed. */
@Composable
fun SoftButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = Soft.colors
    Box(
        modifier
            .clip(SoftTokens.Pill)
            .background(if (enabled) colors.thumb else colors.surface)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Soft.type.control, color = if (enabled) colors.onThumb else colors.inkMuted)
    }
}

/** A quiet pill: a secondary action on a tinted track. Not [enabled], its ink mutes and it can't be pressed. */
@Composable
fun SoftTonalButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = Soft.colors
    Box(
        modifier
            .clip(SoftTokens.Pill)
            .background(colors.surface)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Soft.type.control, color = if (enabled) colors.ink else colors.inkMuted)
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
        style = Soft.type.section,
        color = Soft.colors.ink,
        modifier = modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp).semantics { heading() },
    )
}

/** How far a pressed row shrinks. */
const val PressedScale = 0.98f

/** A state or kind as a tinted pill led by its glyph: Open, Merged, a release tag, a branch. */
@Composable
fun SoftPill(label: String, icon: ImageVector, tint: Color, modifier: Modifier = Modifier, monospace: Boolean = false) {
    val colors = Soft.colors
    Row(
        modifier.clip(SoftTokens.Pill).background(tint).padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, contentDescription = null, tint = colors.ink, modifier = Modifier.size(15.dp))
        Text(
            label,
            style = if (monospace) Soft.type.label.copy(fontFamily = FontFamily.Monospace) else Soft.type.label,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
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
