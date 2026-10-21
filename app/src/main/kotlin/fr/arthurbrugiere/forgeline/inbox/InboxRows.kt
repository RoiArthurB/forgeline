package fr.arthurbrugiere.forgeline.inbox

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.NotificationThread
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.SwipeAction
import fr.arthurbrugiere.forgeline.core.ui.format.iconRes
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftIconButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTag
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTile
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.animationsEnabled
import fr.arthurbrugiere.forgeline.core.ui.soft.rowHairline
import fr.arthurbrugiere.forgeline.core.ui.soft.softPressable
import fr.arthurbrugiere.forgeline.feed.unbreakable
import fr.arthurbrugiere.forgeline.ui.Avatar
import fr.arthurbrugiere.forgeline.ui.LocalUserSettings
import fr.arthurbrugiere.forgeline.ui.relative
import kotlinx.coroutines.launch

/** Threads by owner, then by repository, in list order. By forge too: "acme" on GitHub and on Codeberg are two owners. */
internal fun List<NotificationThread>.byOwnerAndRepo(): List<Pair<Pair<ForgeInstance, String>, Map<RepoId, List<NotificationThread>>>> =
    groupBy { it.repo.forge to it.repo.owner.lowercase() }.map { (forgeOwner, owned) -> forgeOwner to owned.groupBy { it.repo } }

internal fun LazyListScope.threadItems(
    threads: List<NotificationThread>,
    section: InboxSection,
    nowMillis: Long,
    picking: Picking,
    onOpen: (NotificationThread) -> Unit,
    onMarkRead: (NotificationThread) -> Unit,
    onMarkUnread: (NotificationThread) -> Unit,
    onMarkDone: (NotificationThread) -> Unit,
    onUnsubscribe: (NotificationThread) -> Unit,
) {
    // Keyed by account and id: two forges can use the same thread id, and duplicate keys crash the list.
    items(threads, key = { "thread-${it.key}" }, contentType = { "thread" }) { thread ->
        ThreadRow(
            thread, section, nowMillis, picking, onOpen, onMarkRead, onMarkUnread, onMarkDone, onUnsubscribe,
            Modifier.widthIn(max = SoftTokens.MaxReadingWidth).animateItem(),
        )
    }
}

/** An owner (user or organisation) with several repositories under Everything else: its avatar and login. */
@Composable
internal fun OwnerHeading(thread: NotificationThread, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OwnerAvatar(thread, 20.dp)
        Spacer(Modifier.width(8.dp))
        Text(thread.repo.owner, style = Soft.type.label, color = Soft.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        HeadingForge(thread)
    }
}

/**
 * A repository under Everything else. Alone under its owner: the owner's avatar, `owner/` muted and the name. Under an
 * owner heading: just the name, lined up with the threads below it. Swiped away like a thread, it takes every thread
 * listed under it: all done at once.
 */
@Composable
internal fun RepoHeading(thread: NotificationThread, showOwner: Boolean, onMarkAllDone: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Soft.colors
    val markAllDone = stringResource(R.string.inbox_mark_all_done)
    val swipes = LocalUserSettings.current
    // Not saved, like a thread's: a heading brought back by Undo starts settled.
    val threshold = SwipeToDismissBoxDefaults.positionalThreshold
    val swipe = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, threshold) }
    SwipeToDismissBox(
        state = swipe,
        modifier = modifier.padding(top = if (showOwner) 10.dp else 4.dp),
        // A repository is swiped away toward the side that means "done" for a thread, if one does.
        enableDismissFromStartToEnd = swipes.inboxSwipeRight == SwipeAction.DONE,
        enableDismissFromEndToStart = swipes.inboxSwipeLeft == SwipeAction.DONE,
        backgroundContent = { SwipeBackground(swipe.dismissDirection, SwipeAction.DONE) },
        onDismiss = { direction -> if (direction != SwipeToDismissBoxValue.Settled) onMarkAllDone() },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.ground)
                // Under an owner heading, the name lines up with its threads' titles, past their tiles.
                .padding(start = if (showOwner) 16.dp else ThreadTextStart, end = 16.dp, top = 4.dp, bottom = 2.dp)
                .semantics(mergeDescendants = true) {
                    heading()
                    // Swipes are invisible to screen readers: the same action as an accessibility action.
                    customActions = listOf(CustomAccessibilityAction(markAllDone) { onMarkAllDone(); true })
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showOwner) {
                OwnerAvatar(thread, 20.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                buildAnnotatedString {
                    if (showOwner) withStyle(SpanStyle(color = colors.inkMuted)) { append("${thread.repo.owner}/\u2060") }
                    append(thread.repo.name)
                },
                style = Soft.type.label,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            HeadingForge(thread)
        }
    }
}

/**
 * Which forge a heading's repositories live on, once accounts span several forges: said to screen readers only, since
 * every thread under it carries the forge's badge on its tile.
 */
@Composable
private fun HeadingForge(thread: NotificationThread) {
    if (!LocalShowForge.current) return
    val forge = thread.repo.forge.displayName
    Spacer(Modifier.semantics { contentDescription = forge })
}

/** The repository owner's avatar (a user or an organisation), its initial on the soft surface until it loads. */
@Composable
private fun OwnerAvatar(thread: NotificationThread, size: Dp) {
    val colors = Soft.colors
    Avatar(
        thread.ownerAvatarUrl,
        thread.repo.owner,
        size = size,
        placeholderColor = colors.surface,
        placeholderContentColor = colors.inkMuted,
        modifier = Modifier.clearAndSetSemantics {},
    )
}

/** A section's title over its threads, pinned while they scroll; the unread count ticks as it changes. */
@Composable
internal fun SectionHeading(section: InboxSection, unread: Int, modifier: Modifier = Modifier) {
    val colors = Soft.colors
    val animations = animationsEnabled()
    Row(
        modifier
            .widthIn(max = SoftTokens.MaxReadingWidth)
            .fillMaxWidth()
            .background(colors.ground)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(if (section == InboxSection.NEEDS_YOU) R.string.inbox_section_needs_you else R.string.inbox_section_others),
            style = Soft.type.section,
            color = colors.ink,
        )
        AnimatedVisibility(unread > 0, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
            Row {
                Spacer(Modifier.width(8.dp))
                AnimatedContent(
                    targetState = unread,
                    transitionSpec = {
                        if (!animations) {
                            EnterTransition.None togetherWith ExitTransition.None
                        } else {
                            val down = targetState < initialState
                            (slideInVertically { if (down) -it else it } + fadeIn()) togetherWith
                                (slideOutVertically { if (down) it else -it } + fadeOut())
                        }
                    },
                    label = "unread",
                ) { count ->
                    SoftTag("$count", background = if (section == InboxSection.NEEDS_YOU) colors.accentSoft else colors.surface)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThreadRow(
    thread: NotificationThread,
    section: InboxSection,
    nowMillis: Long,
    picking: Picking,
    onOpen: (NotificationThread) -> Unit,
    onMarkRead: (NotificationThread) -> Unit,
    onMarkUnread: (NotificationThread) -> Unit,
    onMarkDone: (NotificationThread) -> Unit,
    onUnsubscribe: (NotificationThread) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selecting = picking.selected.isNotEmpty()
    val picked = thread.key in picking.selected
    val selectLabel = stringResource(R.string.inbox_select)
    val haptics = LocalHapticFeedback.current
    val unreadState = stringResource(R.string.inbox_unread_state)
    val colors = Soft.colors
    // Not saved: a thread brought back by Undo keeps its list key, so a saved swipe state would come back dismissed
    // and mark it done again at once. A returning row always starts settled.
    val threshold = SwipeToDismissBoxDefaults.positionalThreshold
    val swipe = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, threshold) }
    val scope = rememberCoroutineScope()
    val markReadLabel = stringResource(R.string.inbox_mark_read)
    val markUnreadLabel = stringResource(R.string.inbox_mark_unread)
    // The same swipe, menu entry and action for both: what it does depends on where the thread stands.
    val toggleRead = { if (thread.unread) onMarkRead(thread) else onMarkUnread(thread) }
    val toggleReadLabel = if (thread.unread) markReadLabel else markUnreadLabel
    val doneLabel = stringResource(R.string.inbox_mark_done)
    val unsubscribeLabel = stringResource(R.string.inbox_unsubscribe)
    val animations = animationsEnabled()
    val toEnd = LocalUserSettings.current.inboxSwipeRight
    val toStart = LocalUserSettings.current.inboxSwipeLeft
    // Whether a read swipe is on its way back into place. Not read by the composition: nothing is drawn from it.
    val returning = remember { mutableStateOf(false) }
    SwipeToDismissBox(
        state = swipe,
        modifier = modifier,
        // Each side does what Settings says. The read swipe always has something to do: read threads go back to unread.
        // While threads are being picked nothing swipes: the bar acts on them all.
        enableDismissFromStartToEnd = toEnd != SwipeAction.NONE && !selecting,
        enableDismissFromEndToStart = toStart != SwipeAction.NONE && !selecting,
        backgroundContent = {
            SwipeBackground(swipe.dismissDirection, if (swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd) toEnd else toStart, thread.unread)
        },
        onDismiss = { direction ->
            when (if (direction == SwipeToDismissBoxValue.StartToEnd) toEnd else toStart) {
                // One swipe, one turn. The row stays, so it is brought back into place, and until it is there the swipe
                // still counts as made: asked again meanwhile (the thread just changed under it), it would mark the
                // thread back, and that one back again, each turn cutting short the return of the one before.
                SwipeAction.MARK_READ -> if (!returning.value) {
                    returning.value = true
                    toggleRead()
                    scope.launch {
                        try {
                            swipe.reset()
                        } finally {
                            returning.value = false
                        }
                    }
                }
                SwipeAction.DONE -> onMarkDone(thread)
                SwipeAction.NONE -> Unit
            }
        },
    ) {
        var menuOpen by rememberSaveable(thread.id) { mutableStateOf(false) }
        // Reading a thread eases its title from ink to muted rather than snapping.
        val titleColor by animateColorAsState(
            if (thread.unread) colors.ink else colors.inkMuted,
            tween(if (animations) 300 else 0),
            label = "title",
        )
        val needsYou = section == InboxSection.NEEDS_YOU
        val forge = thread.repo.forge.takeIf { LocalShowForge.current }
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (picked) colors.surface else colors.ground)
                .softPressable(
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        picking.onToggle(thread)
                    },
                    onLongClickLabel = selectLabel,
                ) { if (selecting) picking.onToggle(thread) else onOpen(thread) }
                // Swipes are invisible to screen readers: the same actions as accessibility actions.
                .semantics {
                    // The dot and the bolder title are visual only: say it.
                    if (thread.unread) stateDescription = unreadState
                    if (selecting) selected = picked
                    customActions = buildList {
                        add(CustomAccessibilityAction(toggleReadLabel) { toggleRead(); true })
                        add(CustomAccessibilityAction(doneLabel) { onMarkDone(thread); true })
                        add(CustomAccessibilityAction(unsubscribeLabel) { onUnsubscribe(thread); true })
                    }
                }
                .rowHairline(start = ThreadTextStart)
                .padding(start = 16.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The tile says what it is at a glance; marigold when it waits on you, its state's tint otherwise. The dot pops
            // in while it is unread, and the forge's logo sits on its corner once more than one forge is signed in.
            SoftTile(
                background = when {
                    picked -> colors.thumb
                    needsYou -> colors.accentSoft
                    else -> thread.stateTint(colors)
                },
                unread = thread.unread && !picked,
                badge = forge?.let { painterResource(it.iconRes) },
                badgeDescription = forge?.displayName,
            ) {
                Icon(
                    if (picked) Icons.Outlined.Check else thread.tileIcon,
                    contentDescription = null,
                    tint = if (needsYou && !picked) colors.accent else colors.ink,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                // Why it waits on you (or what it is), where, then when: on the right where times line up, or at the end
                // of the line at a large font, where a column for it would squeeze the place out of sight.
                val large = LocalDensity.current.fontScale > 1.3f
                val time = relative(thread.updatedAt, nowMillis, abbreviated = true)
                val source = buildAnnotatedString {
                    if (needsYou) {
                        withStyle(SpanStyle(color = colors.accent, fontWeight = FontWeight.SemiBold)) { append(stringResource(thread.reason.label)) }
                        append(" · ")
                        append((thread.repo.fullName + (thread.number?.let { " #$it" } ?: "")).unbreakable())
                    } else {
                        append(thread.kindLabel())
                        thread.number?.let { append(" #$it") }
                        append(" · ")
                        append(stringResource(thread.reason.label))
                    }
                    if (large) append(" · ${time.replace(' ', '\u00A0')}")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        source,
                        style = Soft.type.meta,
                        color = colors.inkMuted,
                        maxLines = if (large) 3 else 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (!large) {
                        Text(time, style = Soft.type.meta, color = colors.inkMuted, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                Text(
                    thread.title,
                    style = Soft.type.rowTitle.copy(fontWeight = if (thread.unread) FontWeight.SemiBold else FontWeight.Normal),
                    color = titleColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            // The menu acts on one thread: it steps aside, keeping its room, while several are being picked.
            if (selecting) Spacer(Modifier.size(48.dp)) else Box {
                SoftIconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.inbox_more), tint = colors.inkMuted, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    shape = RoundedCornerShape(8.dp),
                    containerColor = colors.raised,
                ) {
                    DropdownMenuItem(text = { Text(toggleReadLabel) }, onClick = { menuOpen = false; toggleRead() })
                    DropdownMenuItem(text = { Text(doneLabel) }, onClick = { menuOpen = false; onMarkDone(thread) })
                    DropdownMenuItem(text = { Text(unsubscribeLabel) }, onClick = { menuOpen = false; onUnsubscribe(thread) })
                }
            }
        }
    }
}

/** Where a thread's text starts: past the 16dp gutter, its 32dp tile and a 12dp gap. Headings and hairlines line up on it. */
private val ThreadTextStart = 60.dp

/**
 * What a swipe does, shown on a tint under the row, on the side the row leaves: fresh to mark read (or unread
 * again, for a thread already read), warm for done.
 */
@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue, action: SwipeAction, unread: Boolean = true) {
    val colors = Soft.colors
    val alignment = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
        SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
        SwipeToDismissBoxValue.Settled -> return
    }
    val (icon, tint) = when (action) {
        SwipeAction.MARK_READ -> (if (unread) Icons.Outlined.MarkEmailRead else Icons.Outlined.MarkEmailUnread) to colors.fields[2]
        SwipeAction.DONE -> Icons.Outlined.Done to colors.fields[0]
        SwipeAction.NONE -> return
    }
    Box(
        Modifier.fillMaxSize().clip(SoftTokens.RowCorner).background(tint).padding(horizontal = 24.dp),
        contentAlignment = alignment,
    ) {
        Icon(icon, contentDescription = null, tint = colors.ink)
    }
}
