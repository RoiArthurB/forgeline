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
import androidx.compose.foundation.LocalIndication
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.foundation.border
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Check

/** Shapes, widths and motion shared by every Soft screen. */
object SoftTokens {
    /** A control's corner: buttons, fields, the choice menu, a snackbar. No pills in this world. */
    val Pill = RoundedCornerShape(6.dp)
    /** A tag's corner: labels, states, reactions, counts. */
    val TagCorner = RoundedCornerShape(5.dp)
    /** An icon tile's corner (the big glyph that opens a row). */
    val TileCorner = RoundedCornerShape(8.dp)
    /** Rows run edge to edge between hairlines: a pressed row fills its whole width. */
    val RowCorner = RectangleShape
    /** The size of a row's icon tile. */
    val Tile = 32.dp
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

/** A clickable row: the tile surface fills it edge to edge while pressed or focused, not a ripple. */
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
    background(if (pressed || focused) Soft.colors.surface else Color.Transparent)
        .combinedClickable(
            interactionSource = interaction, indication = null, role = role,
            onLongClick = onLongClick, onLongClickLabel = onLongClickLabel, onClick = onClick,
        )
}

/**
 * The rule under a row, one device pixel, starting [start] in from the row's leading edge (where its text begins, past
 * a tile) so the rules line up with what they separate.
 */
fun Modifier.rowHairline(start: Dp = 0.dp): Modifier = composed {
    val color = Soft.colors.hairline
    drawBehind {
        val inset = start.toPx()
        val y = size.height - 0.5f
        val (from, to) = if (layoutDirection == LayoutDirection.Ltr) inset to size.width else 0f to size.width - inset
        drawLine(color, Offset(from, y), Offset(to, y), strokeWidth = 1f)
    }
}

/** The one-device-pixel rule between rows, and under a band that would otherwise melt into the page. */
@Composable
fun SoftHairline(modifier: Modifier = Modifier, color: Color = Soft.colors.hairline) {
    Spacer(modifier.fillMaxWidth().height(Dp.Hairline).background(color))
}

/**
 * The band that heads every screen, under the status bar. A top-level screen passes its [title] and puts its one axis
 * (the filter, the period) in [content]; a detail screen passes [onBack] and puts its own header in [content].
 * Everything inside is drawn with the band's own palette, so it reads on ink, black or white alike.
 */
@Composable
fun SoftHeader(
    modifier: Modifier = Modifier,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    backDescription: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: (@Composable () -> Unit)? = null,
) {
    val page = Soft.colors
    val band = remember(page) { page.onBand() }
    SoftTheme(band) {
        Column(modifier.fillMaxWidth().background(band.ground), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                Modifier
                    .widthIn(max = SoftTokens.MaxReadingWidth)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(
                        start = if (onBack != null) 4.dp else 18.dp,
                        end = 8.dp,
                        top = 4.dp,
                        // Tabs end the band on their own underline; anything else gets room to breathe.
                        bottom = if (onBack == null && content != null) 0.dp else 14.dp,
                    ),
            ) {
                // A top-level screen: its title and actions on one row. A detail screen: back and actions on a row, then
                // its title (if any) and its own header under it.
                Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onBack != null) {
                        SoftIconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = backDescription, tint = band.ink)
                        }
                        Spacer(Modifier.weight(1f))
                    } else if (title != null) {
                        Text(
                            title,
                            style = Soft.type.title,
                            color = band.ink,
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
                        color = band.ink,
                        modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 4.dp).semantics { heading() },
                    )
                }
                if (content != null) {
                    Box(Modifier.padding(start = if (onBack != null) 16.dp else 0.dp, end = if (onBack != null) 12.dp else 0.dp, top = if (onBack != null) 6.dp else 0.dp)) {
                        content()
                    }
                }
            }
            if (page.bandNeedsRule) SoftHairline(color = page.hairline)
        }
    }
}

/**
 * A screen's one axis in the open (Trending's period, the Inbox's filter, a search's scope): text tabs, the chosen one
 * bold over a marigold underline that slides to it. Tabs keep their natural width and scroll sideways when they don't
 * fit, so a large font scale never cuts a word.
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
    // Widened by a tab's inset on each side, so the first label sits on the same edge as the text above it.
    SoftTabRow(options, selected, onSelect, modifier.outset(TabInset), leading, background = Color.Transparent, contentPadding = PaddingValues(0.dp), edge = 0.dp)
}

/** Lays this out [by] wider on each side than its room, drawn that far out: for a row whose items carry their own inset. */
private fun Modifier.outset(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = by.roundToPx()
    val wide = if (constraints.hasBoundedWidth) constraints.copy(maxWidth = constraints.maxWidth + extra * 2, minWidth = (constraints.minWidth + extra * 2).coerceAtMost(constraints.maxWidth + extra * 2)) else constraints
    val placeable = measurable.measure(wide)
    val width = (placeable.width - extra * 2).coerceAtLeast(0)
    layout(width, placeable.height) { placeable.placeRelative(-extra, 0) }
}

/**
 * A page's sections (a repository's README, Code, Issues...; a profile's lists): the same tabs on the band, so the band
 * runs on under the header and pins at the top while the page scrolls.
 */
@Composable
fun SoftChipTabs(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Drawn before an option's label, in its color (a forge's logo). */
    leading: (@Composable (index: Int, color: Color) -> Unit)? = null,
    /** The strip behind the tabs: the band by default, transparent inside a header. */
    background: Color = Soft.colors.band,
    contentPadding: PaddingValues = PaddingValues(start = 8.dp, end = 8.dp),
    // The first label lands on the band's 18dp text edge: 8dp here plus the tab's own 10dp inset.
) {
    val page = Soft.colors
    val band = remember(page) { page.onBand() }
    SoftTheme(band) {
        Column(modifier.fillMaxWidth().background(background), horizontalAlignment = Alignment.CenterHorizontally) {
            SoftTabRow(options, selected, onSelect, Modifier.widthIn(max = SoftTokens.MaxReadingWidth), leading, background = Color.Transparent, contentPadding = contentPadding, edge = 8.dp)
            if (page.bandNeedsRule) SoftHairline(color = page.hairline)
        }
    }
}

/** How far a tab's label sits in from its own edges: half the gap between two labels, and part of its touch target. */
private val TabInset = 10.dp

@Composable
private fun SoftTabRow(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
    leading: (@Composable (index: Int, color: Color) -> Unit)?,
    background: Color,
    contentPadding: PaddingValues,
    /** How much room is kept beside the chosen tab when it is scrolled into view. */
    edge: Dp,
) {
    val colors = Soft.colors
    val animations = animationsEnabled()
    val density = LocalDensity.current
    // Where each tab sits, so the underline can travel between tabs of different widths.
    val bounds = remember(options) { mutableStateListOf<Pair<Dp, Dp>>().apply { repeat(options.size) { add(0.dp to 0.dp) } } }
    val target = bounds.getOrNull(selected) ?: (0.dp to 0.dp)
    val scroll = rememberScrollState()
    var viewport by remember { mutableIntStateOf(0) }
    // Bring the chosen tab into view: a row wider than the screen can open on a tab that is cut off.
    LaunchedEffect(selected, target, viewport) {
        if (target.second <= 0.dp || viewport <= 0) return@LaunchedEffect
        val start = with(density) { (contentPadding.calculateLeftPadding(LayoutDirection.Ltr) + target.first).roundToPx() }
        val end = start + with(density) { target.second.roundToPx() }
        val margin = with(density) { edge.roundToPx() }
        val to = when {
            start - margin < scroll.value -> start - margin
            end + margin > scroll.value + viewport -> end + margin - viewport
            else -> return@LaunchedEffect
        }.coerceIn(0, scroll.maxValue)
        if (animations) scroll.animateScrollTo(to) else scroll.scrollTo(to)
    }
    val motion: androidx.compose.animation.core.AnimationSpec<Dp> =
        if (animations) spring(dampingRatio = 0.8f, stiffness = SoftTokens.SpringStiffness) else tween(0)
    val lineX by animateDpAsState(target.first + TabInset, motion, label = "underlineX")
    val lineWidth by animateDpAsState((target.second - TabInset * 2).coerceAtLeast(0.dp), motion, label = "underlineWidth")
    Box(
        modifier
            .fillMaxWidth()
            .background(background)
            .onSizeChanged { viewport = it.width }
            // Scrolls only when the tabs overflow (a large font): otherwise it isn't a scrolling list to anyone.
            .horizontalScroll(scroll, enabled = scroll.maxValue > 0)
            .padding(contentPadding),
    ) {
        Row(Modifier.selectableGroup()) {
            options.forEachIndexed { index, option ->
                val isSelected = index == selected
                Box(
                    Modifier
                        .onPlaced { with(density) { bounds[index] = it.positionInParent().x.toDp() to it.size.width.toDp() } }
                        .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(index) })
                        .heightIn(min = 48.dp)
                        .padding(horizontal = TabInset, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val color = if (isSelected) colors.ink else colors.inkMuted
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        leading?.invoke(index, color)
                        // A logo alone needs no label.
                        if (option.isNotEmpty()) {
                            Text(
                                option,
                                style = Soft.type.control.copy(fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal),
                                color = color,
                                // One line: the row never narrows a label, it scrolls. The row's width is unbounded, so
                                // the label gets a finite one of its own, or it is laid out infinitely wide and "cut".
                                maxLines = 1,
                                modifier = Modifier.widthIn(max = SoftTokens.MaxReadingWidth),
                            )
                        }
                    }
                }
            }
        }
        if (lineWidth > 0.dp) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = lineX)
                    .width(lineWidth)
                    .height(3.dp)
                    .background(colors.thumb),
            )
        }
    }
}

/**
 * The one text field: a raised fill in a hairline frame with small corners, an optional leading glyph and trailing
 * action, and an error line under it. With [singleLine] off it grows with what is written, from [minLines] to
 * [maxLines]. A [label] names it above, and stays once something is written,
 * which the [placeholder] doesn't: with a label, the placeholder is an example of what to write.
 */
@Composable
fun SoftTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    background: Color = Soft.colors.raised,
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
    label: String? = null,
) {
    val colors = Soft.colors
    Column(modifier) {
        if (label != null) {
            Text(label, style = Soft.type.label, color = colors.ink, modifier = Modifier.padding(bottom = 6.dp).clearAndSetSemantics {})
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            readOnly = readOnly,
            textStyle = Soft.type.lead.copy(color = colors.ink),
            cursorBrush = SolidColor(colors.accent),
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            // The label is the field's name for a screen reader, said with what is written.
            modifier = Modifier.fillMaxWidth().then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier),
            decorationBox = { field -> SoftFieldBox(value.isEmpty(), placeholder, background, singleLine, leading, trailing, field) },
        )
        if (error != null) {
            Text(error, style = Soft.type.secondary, color = colors.accent, modifier = Modifier.padding(start = 14.dp, top = 6.dp))
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
    background: Color = Soft.colors.raised,
    error: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    readOnly: Boolean = false,
    label: String? = null,
) {
    val colors = Soft.colors
    Column(modifier) {
        if (label != null) {
            Text(label, style = Soft.type.label, color = colors.ink, modifier = Modifier.padding(bottom = 6.dp).clearAndSetSemantics {})
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            readOnly = readOnly,
            textStyle = Soft.type.lead.copy(color = colors.ink),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = keyboardOptions,
            modifier = Modifier.fillMaxWidth().then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier),
            decorationBox = { field -> SoftFieldBox(value.text.isEmpty(), placeholder, background, singleLine, null, null, field) },
        )
        if (error != null) {
            Text(error, style = Soft.type.secondary, color = colors.accent, modifier = Modifier.padding(start = 14.dp, top = 6.dp))
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
            .clip(SoftTokens.Pill)
            .background(background)
            .border(1.dp, colors.hairline, SoftTokens.Pill)
            .heightIn(min = 48.dp)
            .padding(start = if (leading != null) 12.dp else 14.dp, end = if (trailing != null) 4.dp else 14.dp)
            .padding(vertical = if (singleLine) 0.dp else 12.dp),
        verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(10.dp))
        }
        Box(Modifier.weight(1f)) {
            if (isEmpty) {
                Text(placeholder, style = Soft.type.lead, color = colors.inkMuted, maxLines = 1)
            }
            field()
        }
        trailing?.invoke()
    }
}

/** An empty or error state: a title, one line of cause or reassurance, and optionally one filled action. */
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
    Column(modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(horizontal = 18.dp, vertical = 28.dp)) {
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

/**
 * The filled marigold button: one primary action, 40dp tall in a 48dp target. Not [enabled], it goes quiet (the tile
 * surface, muted ink) and can't be pressed.
 */
@Composable
fun SoftButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = Soft.colors
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .clip(SoftTokens.Pill)
            .background(if (enabled) colors.thumb else colors.surface)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Soft.type.control, color = if (enabled) colors.onThumb else colors.inkMuted)
    }
}

/** A quiet button: a secondary action on the tile surface. Not [enabled], its ink mutes and it can't be pressed. */
@Composable
fun SoftTonalButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = Soft.colors
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .clip(SoftTokens.Pill)
            .background(colors.surface)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Soft.type.control, color = if (enabled) colors.ink else colors.inkMuted)
    }
}

/**
 * An icon to press: a 40dp tile in a 48dp target that takes the soft veil while pressed or focused, as everything
 * else pressable does. Material's icon buttons draw their own ripple whatever the theme says. Not [enabled], it fades
 * and can't be pressed.
 */
@Composable
fun SoftIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    Box(
        modifier
            .softIconTarget(enabled)
            .clickable(enabled = enabled, role = Role.Button, interactionSource = null, indication = LocalIndication.current, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** A [SoftIconButton] that is on or off, like a star: announced as checked or not. */
@Composable
fun SoftIconToggleButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .softIconTarget(enabled)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                interactionSource = null,
                indication = LocalIndication.current,
                onValueChange = onCheckedChange,
            ),
        contentAlignment = Alignment.Center,
    ) { content() }
}

private fun Modifier.softIconTarget(enabled: Boolean) =
    minimumInteractiveComponentSize().size(40.dp).clip(SoftTokens.TileCorner).alpha(if (enabled) 1f else 0.38f)

/**
 * On or off, as a small square: marigold with a check when on, a muted frame when off. It shows the state of a row that
 * toggles as a whole, so it takes no touches itself. Not a pill switch: this world has none.
 */
@Composable
fun SoftCheck(checked: Boolean, modifier: Modifier = Modifier) {
    val colors = Soft.colors
    Box(
        modifier
            .size(22.dp)
            .clip(SoftTokens.Pill)
            .background(if (checked) colors.thumb else Color.Transparent)
            .then(if (checked) Modifier else Modifier.border(1.5.dp, colors.inkMuted, SoftTokens.Pill)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Icons.Outlined.Check, contentDescription = null, tint = colors.onThumb, modifier = Modifier.size(16.dp))
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
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp)) {
                if (leadingDot) {
                    Box(Modifier.size(SoftTokens.Tile).background(colors.surface, SoftTokens.TileCorner))
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.fillMaxWidth(0.3f).height(10.dp).background(colors.surface, SoftTokens.TagCorner))
                    Box(Modifier.fillMaxWidth(if (index % 2 == 0) 0.6f else 0.45f).height(15.dp).background(colors.surface, SoftTokens.TagCorner))
                    Box(Modifier.fillMaxWidth(0.95f).height(10.dp).background(colors.surface, SoftTokens.TagCorner))
                }
            }
            SoftHairline()
        }
    }
}

/** Keeps the band under the status bar once the header has scrolled away, so the status bar never sits on a row. */
@Composable
fun SoftStatusBarScrim(visible: Boolean, modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .fillMaxWidth()
            .windowInsetsTopHeight(WindowInsets.statusBars)
            .background(if (visible) Soft.colors.band else Color.Transparent),
    )
}

/** A small section heading inside a list. */
@Composable
fun SoftSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = Soft.type.section,
        color = Soft.colors.ink,
        modifier = modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp).semantics { heading() },
    )
}

/** A state or kind as a tinted tag led by its glyph: Open, Merged, a release tag, a branch. */
@Composable
fun SoftPill(label: String, icon: ImageVector, tint: Color, modifier: Modifier = Modifier, monospace: Boolean = false) {
    val colors = Soft.colors
    Row(
        modifier.clip(SoftTokens.TagCorner).background(tint).padding(start = 6.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = colors.ink, modifier = Modifier.size(14.dp))
        Text(
            label,
            style = if (monospace) Soft.type.label.copy(fontFamily = FontFamily.Monospace) else Soft.type.label,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A small tag: a count, a state or a label, on a tint with small corners. */
@Composable
fun SoftTag(text: String, modifier: Modifier = Modifier, background: Color = Soft.colors.surface, content: Color = Soft.colors.ink) {
    Text(
        text,
        style = Soft.type.tag,
        color = content,
        // One line, cut short at its end: a branch's name allowed to wrap broke after its first "/" and lost the rest.
        maxLines = 1,
        softWrap = false,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = modifier.clip(SoftTokens.TagCorner).background(background).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/**
 * The big glyph that opens a row: a kind's icon or a name's initial on a tile, with a marigold dot when it is unread
 * and the forge's logo as a badge on its corner when the person follows more than one forge. A tile that needs you
 * takes marigold's soft tint.
 */
@Composable
fun SoftTile(
    modifier: Modifier = Modifier,
    background: Color = Soft.colors.surface,
    unread: Boolean = false,
    /** The forge's logo, drawn in ink on a cut-out of the page; null when every row shares one forge. */
    badge: Painter? = null,
    /** The badge's forge, said to screen readers since the logo is the only place it is written. */
    badgeDescription: String? = null,
    size: Dp = SoftTokens.Tile,
    shape: Shape = SoftTokens.TileCorner,
    content: @Composable () -> Unit,
) {
    val colors = Soft.colors
    Box(modifier.size(size).then(if (badgeDescription != null) Modifier.semantics { contentDescription = badgeDescription } else Modifier)) {
        Box(Modifier.matchParentSize().clip(shape).background(background), contentAlignment = Alignment.Center) { content() }
        if (unread) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 3.dp, y = (-3).dp)
                    .size(11.dp)
                    .background(colors.ground, CircleShape)
                    .padding(2.dp)
                    .background(colors.thumb, CircleShape),
            )
        }
        if (badge != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 4.dp, y = 4.dp)
                    .size(17.dp)
                    .background(colors.ground, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(badge, contentDescription = null, tint = colors.ink, modifier = Modifier.size(11.dp))
            }
        }
    }
}

/**
 * What sends a comment: a send arrow on a marigold tile, 40dp in a 48dp target. Not [enabled] (nothing written yet),
 * it goes quiet on the tile surface.
 */
@Composable
fun SoftSendButton(
    onClick: () -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** On its way: a small spinner takes the arrow's place. */
    busy: Boolean = false,
) {
    val colors = Soft.colors
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .size(40.dp)
            .clip(SoftTokens.TileCorner)
            .background(if (enabled) colors.thumb else colors.surface)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.inkMuted, trackColor = Color.Transparent)
        } else {
            Icon(
                Icons.AutoMirrored.Outlined.Send,
                contentDescription = null,
                tint = if (enabled) colors.onThumb else colors.inkMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
