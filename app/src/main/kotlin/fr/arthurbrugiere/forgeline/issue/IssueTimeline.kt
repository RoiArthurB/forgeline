package fr.arthurbrugiere.forgeline.issue

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Commit
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.markdown.ReadmeContext
import fr.arthurbrugiere.forgeline.core.model.ConversationEvent
import fr.arthurbrugiere.forgeline.core.model.HideReason
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.ReviewState
import fr.arthurbrugiere.forgeline.core.model.StateChange
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTonalButton
import fr.arthurbrugiere.forgeline.core.ui.soft.softPressable
import fr.arthurbrugiere.forgeline.ui.LabelChip
import fr.arthurbrugiere.forgeline.ui.label
import fr.arthurbrugiere.forgeline.ui.message
import fr.arthurbrugiere.forgeline.ui.relative
import java.time.LocalDate

internal val TimelineGutter = 40.dp

/** The reviews that say yes or no to a pull request. */
private val VERDICTS = setOf(ReviewState.APPROVED, ReviewState.CHANGES_REQUESTED)

@Composable
internal fun TimelineEntry(
    item: TimelineItem,
    context: ReadmeContext,
    nowMillis: Long,
    onOpenUser: (String) -> Unit,
    onOpenIssue: (IssueRef) -> Unit,
    onLinkClick: (String) -> Unit,
    editor: (@Composable () -> Unit)? = null,
    /** Dismisses a review that gave a verdict, by its id; null for whoever may not. */
    onDismissReview: ((Long) -> Unit)? = null,
    menuFor: (TimelineItem.Comment) -> CommentMenu? = { null },
) {
    val colors = Soft.colors
    val time = item.createdAt?.let { relative(it, nowMillis) }.orEmpty()
    when (item) {
        is TimelineItem.Comment -> {
            // A hidden comment folds to a line that says why, and opens when asked; its menu still offers to unhide it.
            var shown by rememberSaveable(item.id) { mutableStateOf(false) }
            val reason = item.hidden
            if (reason != null && !shown) {
                EventLine(
                    Icons.Outlined.VisibilityOff,
                    stringResource(R.string.issue_comment_hidden, item.author?.login ?: stringResource(R.string.deleted_account), stringResource(reason.label).lowercase()),
                    trailing = { SoftTonalButton(stringResource(R.string.issue_comment_show), onClick = { shown = true }) },
                )
            } else {
                Comment(item.author, item.body, item.createdAt, item.reactions, context, nowMillis, onOpenUser, onLinkClick, menu = menuFor(item), editor = editor)
            }
        }
        is TimelineItem.Review -> Column {
            val who = item.author?.login ?: stringResource(R.string.deleted_account)
            val (text, icon) = when (item.state) {
                ReviewState.APPROVED -> stringResource(R.string.issue_review_approved, who) to Icons.Outlined.Check
                ReviewState.CHANGES_REQUESTED -> stringResource(R.string.issue_review_changes, who) to Icons.Outlined.RateReview
                ReviewState.DISMISSED -> stringResource(R.string.issue_review_dismissed, who) to Icons.Outlined.RateReview
                ReviewState.COMMENTED -> stringResource(R.string.issue_review_commented, who) to Icons.Outlined.RateReview
            }
            EventLine(icon, "$text $time", badge = if (item.state == ReviewState.APPROVED) colors.fields[2] else colors.surface)
            item.body?.let { Comment(item.author, it, null, emptyMap(), context, nowMillis, onOpenUser, onLinkClick) }
            // Only a verdict can be dismissed: a review that only commented has none.
            if (onDismissReview != null && item.state in VERDICTS) {
                SoftTonalButton(stringResource(R.string.review_dismiss), onClick = { onDismissReview(item.id) }, modifier = Modifier.padding(start = TimelineGutter, top = 4.dp))
            }
        }
        is TimelineItem.StateChanged -> {
            val who = item.actor?.login ?: stringResource(R.string.deleted_account)
            val text = when (item.change) {
                StateChange.MERGED -> stringResource(R.string.issue_merged_event, who, time)
                StateChange.REOPENED -> stringResource(R.string.issue_reopened_event, who, time)
                StateChange.CLOSED -> when (item.stateReason) {
                    "completed" -> stringResource(R.string.issue_closed_completed_event, who, time)
                    "not_planned" -> stringResource(R.string.issue_closed_not_planned_event, who, time)
                    else -> stringResource(R.string.issue_closed_event, who, time)
                }
            }
            val (icon, badge) = when (item.change) {
                StateChange.MERGED -> Icons.AutoMirrored.Outlined.CallMerge to colors.fields[1]
                StateChange.REOPENED -> Icons.Outlined.Replay to colors.fields[2]
                StateChange.CLOSED -> Icons.Outlined.CheckCircleOutline to colors.fields[0]
            }
            EventLine(icon, text, badge = badge)
            // The commit that closed it, as a pushed commit reads in a pull request.
            item.commit?.let { commit ->
                EventLine(
                    Icons.Outlined.Commit,
                    listOfNotNull(commit.sha.take(7), commit.message?.lineSequence()?.first()).joinToString("  "),
                    monospace = true,
                    onClick = { onLinkClick(commit.webUrl) },
                )
            }
        }
        is TimelineItem.Labeled -> {
            val who = item.actor?.login ?: stringResource(R.string.deleted_account)
            // The chip goes inside the line: next to a full-width EventLine it was squeezed to nothing.
            EventLine(Icons.AutoMirrored.Outlined.Label, stringResource(if (item.added) R.string.issue_labeled_event else R.string.issue_unlabeled_event, who)) {
                LabelChip(item.label)
            }
        }
        is TimelineItem.Renamed -> EventLine(Icons.Outlined.Edit, stringResource(R.string.issue_renamed_event, item.actor?.login ?: stringResource(R.string.deleted_account), item.from, item.to))
        is TimelineItem.CrossReferenced -> EventLine(
            Icons.Outlined.Link,
            stringResource(R.string.issue_referenced_event, item.actor?.login ?: stringResource(R.string.deleted_account), item.source.number, item.sourceTitle),
            onClick = { onOpenIssue(item.source) },
        )
        is TimelineItem.Event -> {
            val who = item.actor?.login ?: stringResource(R.string.deleted_account)
            val subject = item.subject.orEmpty()
            val (icon, text) = when (item.event) {
                ConversationEvent.LOCKED -> Icons.Outlined.Lock to stringResource(R.string.issue_locked_event, who, time)
                ConversationEvent.UNLOCKED -> Icons.Outlined.LockOpen to stringResource(R.string.issue_unlocked_event, who, time)
                ConversationEvent.PINNED -> Icons.Outlined.PushPin to stringResource(R.string.issue_pinned_event, who, time)
                ConversationEvent.UNPINNED -> Icons.Outlined.PushPin to stringResource(R.string.issue_unpinned_event, who, time)
                ConversationEvent.ASSIGNED -> Icons.Outlined.PersonOutline to if (subject.equals(who, ignoreCase = true)) {
                    stringResource(R.string.issue_self_assigned_event, who, time)
                } else {
                    stringResource(R.string.issue_assigned_event, who, subject, time)
                }
                ConversationEvent.UNASSIGNED -> Icons.Outlined.PersonOutline to if (subject.equals(who, ignoreCase = true)) {
                    stringResource(R.string.issue_self_unassigned_event, who, time)
                } else {
                    stringResource(R.string.issue_unassigned_event, who, subject, time)
                }
                ConversationEvent.MILESTONED -> Icons.Outlined.Flag to stringResource(R.string.issue_milestoned_event, who, subject, time)
                ConversationEvent.DEMILESTONED -> Icons.Outlined.Flag to stringResource(R.string.issue_demilestoned_event, who, subject, time)
                ConversationEvent.TRANSFERRED -> Icons.Outlined.SwapHoriz to stringResource(R.string.issue_transferred_event, who, time)
                // The day is kept as written (ISO); anything else is shown as it came.
                ConversationEvent.DEADLINE_SET -> Icons.Outlined.Event to stringResource(
                    R.string.issue_deadline_set_event, who, runCatching { formatDay(LocalDate.parse(subject)) }.getOrDefault(subject), time,
                )
                ConversationEvent.DEADLINE_REMOVED -> Icons.Outlined.Event to stringResource(R.string.issue_deadline_removed_event, who, time)
                ConversationEvent.TRACKING_STARTED -> Icons.Outlined.Timer to stringResource(R.string.issue_tracking_started_event, who, time)
                ConversationEvent.TRACKING_STOPPED -> Icons.Outlined.Timer to stringResource(R.string.issue_tracking_stopped_event, who, time)
                ConversationEvent.TIME_ADDED -> Icons.Outlined.Timer to stringResource(R.string.issue_time_added_event, who, time)
                ConversationEvent.DEPENDENCY_ADDED -> Icons.Outlined.AccountTree to stringResource(R.string.issue_dependency_added_event, who, subject, time)
                ConversationEvent.DEPENDENCY_REMOVED -> Icons.Outlined.AccountTree to stringResource(R.string.issue_dependency_removed_event, who, subject, time)
                ConversationEvent.CONVERTED_TO_DISCUSSION -> Icons.Outlined.Forum to stringResource(R.string.issue_converted_event, who, time)
            }
            EventLine(icon, text)
        }
        is TimelineItem.Committed -> EventLine(
            Icons.Outlined.Commit,
            "${item.sha.take(7)}  ${item.message.lineSequence().first()}",
            monospace = true,
        )
    }
}

/** A small event on the conversation's timeline: a tile in the avatar column, then one muted line. */
@Composable
private fun EventLine(
    icon: ImageVector,
    text: String,
    badge: Color = Soft.colors.surface,
    monospace: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = Soft.colors
    Row(
        Modifier
            .widthIn(max = SoftTokens.MaxReadingWidth)
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .then(if (onClick != null) Modifier.softPressable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(28.dp).background(badge, SoftTokens.TileCorner), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp), tint = colors.ink)
        }
        Text(
            text,
            style = if (monospace) Soft.type.meta.copy(fontFamily = FontFamily.Monospace) else Soft.type.secondary,
            color = colors.inkMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        trailing?.invoke()
    }
}

internal fun TimelineItem.key(index: Int): String = when (this) {
    is TimelineItem.Comment -> "comment-$id"
    is TimelineItem.Review -> "review-$id"
    is TimelineItem.Committed -> "commit-$sha"
    else -> "event-$index"
}

/** How a reason to hide a comment reads. */
internal val HideReason.label: Int
    get() = when (this) {
        HideReason.OFF_TOPIC -> R.string.hide_off_topic
        HideReason.OUTDATED -> R.string.hide_outdated
        HideReason.RESOLVED -> R.string.hide_resolved
        HideReason.DUPLICATE -> R.string.hide_duplicate
        HideReason.SPAM -> R.string.hide_spam
        HideReason.ABUSE -> R.string.hide_abuse
    }
