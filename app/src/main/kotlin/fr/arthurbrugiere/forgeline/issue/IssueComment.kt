package fr.arthurbrugiere.forgeline.issue

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import fr.arthurbrugiere.forgeline.ui.Markdown
import fr.arthurbrugiere.forgeline.ui.emoji
import fr.arthurbrugiere.forgeline.ui.label
import fr.arthurbrugiere.forgeline.core.ui.soft.rowHairline
import fr.arthurbrugiere.forgeline.ui.LocalUserSettings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.round
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Icon
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.arthurbrugiere.forgeline.R
import androidx.compose.ui.semantics.Role
import fr.arthurbrugiere.forgeline.core.markdown.ReadmeContext
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.ui.Avatar
import fr.arthurbrugiere.forgeline.ui.relative
import java.time.Instant
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTag
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens

/** What one comment's menu offers; a null entry is not offered. [onReact] also makes the reactions shown tappable. */
internal class CommentMenu(val onQuote: (() -> Unit)?, val onEdit: (() -> Unit)?, val onDelete: (() -> Unit)?, val onReact: ((Reaction) -> Unit)? = null) {
    val isEmpty: Boolean get() = onQuote == null && onEdit == null && onDelete == null && onReact == null
}

/** One comment as a conversation turn: the author and when, then their words; unboxed, the reading is the point. */
@Composable
internal fun Comment(
    author: ForgeUser?,
    body: String,
    createdAt: Instant?,
    reactions: Map<Reaction, Int>,
    context: ReadmeContext,
    nowMillis: Long,
    onOpenUser: (String) -> Unit,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    menu: CommentMenu? = null,
    /** What stands in for the comment's words while it is rewritten. */
    editor: (@Composable () -> Unit)? = null,
) {
    val colors = Soft.colors
    val haptics = LocalHapticFeedback.current
    val offered = rememberUpdatedState(menu?.takeIf { !it.isEmpty && editor == null })
    // Where the menu opens: under the finger after a long press, at its button otherwise.
    var pressedAt by remember { mutableStateOf<IntOffset?>(null) }
    // Where a double tap landed, while its thumbs up shows.
    var thumbAt by remember { mutableStateOf<IntOffset?>(null) }
    val doubleTap = LocalUserSettings.current.doubleTapReaction
    val swipeToReply = LocalUserSettings.current.swipeToReply
    val gestures = if (offered.value == null) Modifier else Modifier.pointerInput(doubleTap) {
        detectTapGestures(
            onLongPress = { at ->
                if (offered.value != null) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    pressedAt = at.round()
                }
            },
            // Without the double tap, a single tap isn't held back waiting for a second one.
            onDoubleTap = if (!doubleTap) null else { at ->
                offered.value?.onReact?.let { react ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    thumbAt = at.round()
                    react(Reaction.THUMBS_UP)
                }
            },
        )
    }
    SwipeToReply(offered.value?.onQuote?.takeIf { swipeToReply }, modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth()) {
        // Comments are parted by hairlines, like the rows of a list; one being rewritten stands on its own ground instead.
        Box(Modifier.fillMaxWidth().testTag(COMMENT_TAG).then(if (editor == null) Modifier.rowHairline(start = 20.dp) else Modifier).then(gestures)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    // A comment being rewritten stands out from those around it, on a ground of its own.
                    .then(if (editor != null) Modifier.padding(horizontal = 8.dp, vertical = 4.dp).clip(RoundedCornerShape(8.dp)).background(colors.surface).padding(horizontal = 12.dp, vertical = 6.dp) else Modifier.padding(horizontal = 20.dp, vertical = 12.dp)),
            ) {
                // The author takes the room their name needs; the menu sits at the end of the line whatever that is.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .clip(SoftTokens.Pill)
                            .clickable(enabled = author != null) { author?.let { onOpenUser(it.login) } }
                            .padding(end = 8.dp),
                    ) {
                        Avatar(author?.avatarUrl, author?.login ?: "?", size = 28.dp, shape = SoftTokens.TileCorner, placeholderColor = colors.raised.takeIf { editor != null } ?: colors.surface, placeholderContentColor = colors.inkMuted)
                        Text(author?.login ?: stringResource(R.string.deleted_account), style = Soft.type.control, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        createdAt?.let { Text(relative(it, nowMillis), style = Soft.type.meta, color = colors.inkMuted, maxLines = 1) }
                    }
                    offered.value?.let { CommentMenuButton(it) }
                }
                if (editor != null) {
                    Box(Modifier.padding(top = 10.dp, bottom = 6.dp)) { editor() }
                } else {
                    Column(Modifier.padding(start = TimelineGutter, top = 6.dp).widthIn(max = SoftTokens.MaxMeasure)) {
                        Markdown(body, context, onLinkClick)
                        if (reactions.isNotEmpty()) {
                            // Tappable, each brings its own room: no more is added above.
                            Reactions(reactions, Modifier.padding(top = if (menu?.onReact == null) 8.dp else 0.dp), onReact = menu?.onReact)
                        }
                    }
                }
            }
            pressedAt?.let { at ->
                Box(Modifier.offset { at }) { offered.value?.let { CommentDropdown(it, expanded = true) { pressedAt = null } } }
            }
            thumbAt?.let { at -> ThumbsUpGiven(at) { thumbAt = null } }
        }
    }
}

/** How far a comment is pulled before letting go quotes it, and how far it follows the finger at most. */
private val ReplyPull = 64.dp
private val ReplyPullLimit = 96.dp

/**
 * Pulling a comment toward the end of the line and letting go starts a reply quoting it, as in a messaging app. A
 * reply arrow comes in behind it, full once letting go will quote. Without [onReply] nothing moves.
 */
@Composable
private fun SwipeToReply(onReply: (() -> Unit)?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (onReply == null) {
        Box(modifier) { content() }
        return
    }
    val colors = Soft.colors
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val reply = rememberUpdatedState(onReply)
    val pulled = remember { Animatable(0f) }
    val toEnd = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val threshold = with(LocalDensity.current) { ReplyPull.toPx() }
    val limit = with(LocalDensity.current) { ReplyPullLimit.toPx() }
    fun release(quote: Boolean) {
        if (quote && pulled.value >= threshold) reply.value()
        scope.launch { pulled.animateTo(0f) }
    }
    Box(
        modifier.pointerInput(toEnd) {
            detectHorizontalDragGestures(
                onDragEnd = { release(quote = true) },
                onDragCancel = { release(quote = false) },
            ) { change, amount ->
                val before = pulled.value
                val after = (before + amount * toEnd).coerceIn(0f, limit)
                if (after != before) {
                    change.consume()
                    if (before < threshold && after >= threshold) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch { pulled.snapTo(after) }
                }
            }
        },
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.Reply,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 20.dp)
                .size(24.dp)
                .graphicsLayer {
                    val progress = (pulled.value / threshold).coerceIn(0f, 1f)
                    alpha = progress
                    scaleX = 0.6f + 0.4f * progress
                    scaleY = 0.6f + 0.4f * progress
                },
        )
        Box(Modifier.graphicsLayer { translationX = pulled.value * toEnd }) { content() }
    }
}

/** The thumbs up a double tap gave, where the finger was: it grows, then fades, on its way to the comment's tags. */
@Composable
private fun ThumbsUpGiven(at: IntOffset, onGone: () -> Unit) {
    val shown = remember(at) { Animatable(0f) }
    LaunchedEffect(at) {
        shown.animateTo(1f, tween(450))
        onGone()
    }
    val half = with(LocalDensity.current) { 24.dp.roundToPx() }
    Box(
        Modifier
            .offset { at - IntOffset(half, half) }
            .size(48.dp)
            .graphicsLayer {
                val grown = 0.6f + 0.8f * shown.value
                scaleX = grown
                scaleY = grown
                alpha = 1f - shown.value * shown.value
            }
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) { Text(Reaction.THUMBS_UP.emoji, fontSize = 28.sp) }
}

/** The quiet "more" at the end of a comment's first line, and what it opens. */
@Composable
private fun CommentMenuButton(menu: CommentMenu) {
    val colors = Soft.colors
    var open by remember { mutableStateOf(false) }
    // Its dots end where the comment's text does: the room around them is for the finger, not the eye.
    Box(Modifier.offset(x = 12.dp)) {
        // The avatar's height, so the comment's first line keeps its own; the touch target stays 48dp around it.
        SoftIconButton(onClick = { open = true }, modifier = Modifier.size(28.dp).minimumInteractiveComponentSize()) {
            Icon(Icons.Outlined.MoreHoriz, contentDescription = stringResource(R.string.issue_comment_more), tint = colors.inkMuted)
        }
        CommentDropdown(menu, open) { open = false }
    }
}

/** What a comment's menu offers, from its button or from a long press on the comment. */
@Composable
private fun CommentDropdown(menu: CommentMenu, expanded: Boolean, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, shape = RoundedCornerShape(8.dp), containerColor = Soft.colors.raised) {
        // Reactions first, as two rows of four: the quickest answer there is.
        menu.onReact?.let { onReact ->
            FlowRow(Modifier.padding(horizontal = 8.dp), maxItemsInEachRow = 4) {
                Reaction.entries.forEach { reaction ->
                    val name = stringResource(reaction.label)
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(SoftTokens.TileCorner)
                            .clickable(role = Role.Button) { onDismiss(); onReact(reaction) }
                            .semantics { contentDescription = name },
                        contentAlignment = Alignment.Center,
                    ) { Text(reaction.emoji, fontSize = 22.sp) }
                }
            }
        }
        @Composable
        fun choice(label: Int, action: (() -> Unit)?) {
            if (action != null) DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { onDismiss(); action() })
        }
        choice(R.string.issue_comment_quote, menu.onQuote)
        choice(R.string.issue_comment_edit, menu.onEdit)
        choice(R.string.issue_comment_delete, menu.onDelete)
    }
}

/**
 * The reactions a text was given, each with its count. With [onReact], tapping one gives it too, or takes back the
 * reader's own: each then has a finger's room around it.
 */
@Composable
private fun Reactions(reactions: Map<Reaction, Int>, modifier: Modifier = Modifier, onReact: ((Reaction) -> Unit)? = null) {
    val gap = if (onReact == null) 6.dp else 0.dp
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
        reactions.forEach { (reaction, count) ->
            if (onReact == null) {
                SoftTag("${reaction.emoji} $count")
            } else {
                val said = stringResource(R.string.issue_reaction_count, stringResource(reaction.label), count)
                Box(
                    Modifier
                        .clip(SoftTokens.Pill)
                        .clickable(role = Role.Button) { onReact(reaction) }
                        .minimumInteractiveComponentSize()
                        .clearAndSetSemantics { contentDescription = said },
                    contentAlignment = Alignment.Center,
                ) { SoftTag("${reaction.emoji} $count") }
            }
        }
    }
}
