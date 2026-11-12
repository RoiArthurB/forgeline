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
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Commit
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Scale
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.ViewKanban
import androidx.compose.material.icons.outlined.Visibility
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.markdown.ReadmeContext
import fr.arthurbrugiere.forgeline.core.model.ClosingCommit
import fr.arthurbrugiere.forgeline.core.model.ConversationEvent
import fr.arthurbrugiere.forgeline.core.model.HideReason
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
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
    /** The branch a pull request came from, for the events that don't name it. */
    headRef: String? = null,
    /** The conversation's repository: a reference to another names its repository. */
    here: RepoId? = null,
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
                    "duplicate" -> stringResource(R.string.issue_closed_duplicate_event, who, time)
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
            item.commit?.let { CommitLine(it, onLinkClick) }
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
            // GitLab doesn't title the conversation that mentioned it: its reference says which, as GitLab writes it.
            if (item.sourceTitle.isBlank()) {
                stringResource(R.string.issue_referenced_untitled_event, item.actor?.login ?: stringResource(R.string.deleted_account), item.source.reference(here), time)
            } else {
                // Named as the reference reads, another repository's and a merge request's included, and when, as the
                // untitled one is. Regression: always "#42", whatever it was, and no time.
                stringResource(R.string.issue_referenced_event, item.actor?.login ?: stringResource(R.string.deleted_account), item.source.reference(here), item.sourceTitle, time)
            },
            onClick = { onOpenIssue(item.source) },
        )
        is TimelineItem.Event -> {
            val (icon, text) = eventWords(item, time, headRef)
            val target = item.target
            EventLine(icon, text, onClick = target?.let { { onOpenIssue(it) } })
        }
        is TimelineItem.CommitReferenced -> Column {
            EventLine(Icons.Outlined.Commit, stringResource(R.string.issue_commit_referenced_event, item.actor?.login ?: stringResource(R.string.deleted_account), time))
            CommitLine(item.commit, onLinkClick)
        }
        is TimelineItem.Committed -> EventLine(
            Icons.Outlined.Commit,
            "${item.sha.take(7)}  ${item.message.lineSequence().first()}",
            monospace = true,
        )
    }
}

/** A commit a conversation tells of, as a pushed commit reads in a pull request: its page opens. */
@Composable
private fun CommitLine(commit: ClosingCommit, onLinkClick: (String) -> Unit) = EventLine(
    Icons.Outlined.Commit,
    listOfNotNull(commit.sha.take(7), commit.message?.lineSequence()?.first()).joinToString("  "),
    monospace = true,
    onClick = { onLinkClick(commit.webUrl) },
)

/**
 * What an event says, and its icon. [headRef] names the pull request's branch for the events that don't. A subject the
 * forge didn't give reads without it, never as a blank.
 */
@Composable
private fun eventWords(item: TimelineItem.Event, time: String, headRef: String?): Pair<ImageVector, String> {
    val who = item.actor?.login ?: stringResource(R.string.deleted_account)
    val named = item.subject?.ifBlank { null }
    val subject = named.orEmpty()

    @Composable
    fun either(with: Int, without: Int) = if (named != null) stringResource(with, who, named, time) else stringResource(without, who, time)
    return when (item.event) {
        ConversationEvent.LOCKED -> Icons.Outlined.Lock to when (val reason = lockReason(named)) {
            null -> stringResource(R.string.issue_locked_event, who, time)
            else -> stringResource(R.string.issue_locked_reason_event, who, stringResource(reason), time)
        }
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
        ConversationEvent.TRANSFERRED -> Icons.Outlined.SwapHoriz to either(R.string.issue_transferred_from_event, R.string.issue_transferred_event)
        ConversationEvent.MOVED_AWAY -> Icons.Outlined.SwapHoriz to either(R.string.issue_moved_away_event, R.string.issue_moved_away_unnamed_event)
        ConversationEvent.CLONED -> Icons.Outlined.ContentCopy to either(R.string.issue_cloned_event, R.string.issue_cloned_unnamed_event)
        // The day is kept as written (ISO); anything else is shown as it came.
        ConversationEvent.DEADLINE_SET -> Icons.Outlined.Event to stringResource(
            R.string.issue_deadline_set_event, who, runCatching { formatDay(LocalDate.parse(subject)) }.getOrDefault(subject), time,
        )
        ConversationEvent.DEADLINE_REMOVED -> Icons.Outlined.Event to stringResource(R.string.issue_deadline_removed_event, who, time)
        ConversationEvent.TRACKING_STARTED -> Icons.Outlined.Timer to stringResource(R.string.issue_tracking_started_event, who, time)
        ConversationEvent.TRACKING_STOPPED -> Icons.Outlined.Timer to stringResource(R.string.issue_tracking_stopped_event, who, time)
        ConversationEvent.TRACKING_CANCELLED -> Icons.Outlined.Timer to stringResource(R.string.issue_tracking_cancelled_event, who, time)
        ConversationEvent.TIME_ADDED -> Icons.Outlined.Timer to either(R.string.issue_time_added_amount_event, R.string.issue_time_added_event)
        ConversationEvent.TIME_REMOVED -> Icons.Outlined.Timer to stringResource(R.string.issue_time_removed_event, who, time)
        ConversationEvent.ESTIMATE_SET -> Icons.Outlined.Timer to stringResource(R.string.issue_estimate_set_event, who, subject, time)
        ConversationEvent.ESTIMATE_REMOVED -> Icons.Outlined.Timer to stringResource(R.string.issue_estimate_removed_event, who, time)
        ConversationEvent.DEPENDENCY_ADDED -> Icons.Outlined.AccountTree to stringResource(R.string.issue_dependency_added_event, who, subject, time)
        ConversationEvent.DEPENDENCY_REMOVED -> Icons.Outlined.AccountTree to stringResource(R.string.issue_dependency_removed_event, who, subject, time)
        ConversationEvent.BLOCKING_ADDED -> Icons.Outlined.AccountTree to stringResource(R.string.issue_blocking_added_event, who, subject, time)
        ConversationEvent.BLOCKING_REMOVED -> Icons.Outlined.AccountTree to stringResource(R.string.issue_blocking_removed_event, who, subject, time)
        ConversationEvent.SUB_ISSUE_ADDED -> Icons.Outlined.AccountTree to stringResource(R.string.issue_sub_issue_added_event, who, subject, time)
        ConversationEvent.SUB_ISSUE_REMOVED -> Icons.Outlined.AccountTree to stringResource(R.string.issue_sub_issue_removed_event, who, subject, time)
        ConversationEvent.PARENT_ADDED -> Icons.Outlined.AccountTree to stringResource(R.string.issue_parent_added_event, who, subject, time)
        ConversationEvent.PARENT_REMOVED -> Icons.Outlined.AccountTree to stringResource(R.string.issue_parent_removed_event, who, subject, time)
        ConversationEvent.RELATED_ADDED -> Icons.Outlined.Link to stringResource(R.string.issue_related_added_event, who, subject, time)
        ConversationEvent.RELATED_REMOVED -> Icons.Outlined.LinkOff to stringResource(R.string.issue_related_removed_event, who, subject, time)
        ConversationEvent.LINKED -> Icons.Outlined.Link to stringResource(R.string.issue_linked_event, who, subject, time)
        ConversationEvent.UNLINKED -> Icons.Outlined.LinkOff to stringResource(R.string.issue_unlinked_event, who, subject, time)
        ConversationEvent.MARKED_DUPLICATE -> Icons.Outlined.ContentCopy to either(R.string.issue_marked_duplicate_event, R.string.issue_marked_duplicate_unnamed_event)
        ConversationEvent.UNMARKED_DUPLICATE -> Icons.Outlined.ContentCopy to either(R.string.issue_unmarked_duplicate_event, R.string.issue_unmarked_duplicate_unnamed_event)
        ConversationEvent.DUPLICATE_ADDED -> Icons.Outlined.ContentCopy to stringResource(R.string.issue_duplicate_added_event, who, subject, time)
        ConversationEvent.DUPLICATE_REMOVED -> Icons.Outlined.ContentCopy to stringResource(R.string.issue_duplicate_removed_event, who, subject, time)
        ConversationEvent.CONVERTED_TO_DISCUSSION -> Icons.Outlined.Forum to stringResource(R.string.issue_converted_event, who, time)
        ConversationEvent.BRANCH_DELETED -> Icons.AutoMirrored.Outlined.CallSplit to stringResource(R.string.issue_branch_deleted_event, who, named ?: headRef.orEmpty(), time)
        ConversationEvent.BRANCH_RESTORED -> Icons.AutoMirrored.Outlined.CallSplit to stringResource(R.string.issue_branch_restored_event, who, named ?: headRef.orEmpty(), time)
        ConversationEvent.BASE_CHANGED -> Icons.AutoMirrored.Outlined.CallSplit to either(R.string.issue_base_changed_event, R.string.issue_base_changed_unnamed_event)
        ConversationEvent.REVIEW_REQUESTED -> Icons.Outlined.RateReview to stringResource(R.string.issue_review_requested_event, who, subject, time)
        ConversationEvent.REVIEW_REQUEST_REMOVED -> Icons.Outlined.RateReview to stringResource(R.string.issue_review_request_removed_event, who, subject, time)
        ConversationEvent.REVIEW_DISMISSED -> Icons.Outlined.RateReview to either(R.string.issue_review_dismissed_said_event, R.string.issue_review_dismissed_event)
        ConversationEvent.APPROVAL_REVOKED -> Icons.Outlined.RateReview to stringResource(R.string.issue_approval_revoked_event, who, time)
        ConversationEvent.THREADS_RESOLVED -> Icons.Outlined.DoneAll to stringResource(R.string.issue_threads_resolved_event, who, time)
        ConversationEvent.PUSHED -> Icons.Outlined.Commit to when (val count = named?.toIntOrNull()) {
            null -> stringResource(R.string.issue_pushed_event, who, time)
            else -> pluralStringResource(R.plurals.issue_pushed_count_event, count, who, count, time)
        }
        ConversationEvent.FORCE_PUSHED -> Icons.Outlined.Commit to either(R.string.issue_force_pushed_event, R.string.issue_force_pushed_unnamed_event)
        ConversationEvent.READY_FOR_REVIEW -> Icons.Outlined.RateReview to stringResource(R.string.issue_ready_for_review_event, who, time)
        ConversationEvent.CONVERTED_TO_DRAFT -> Icons.Outlined.EditNote to stringResource(R.string.issue_converted_to_draft_event, who, time)
        // The board's name is the subject, or its place where GitHub names it apart (to a sign-in that may read it).
        ConversationEvent.ADDED_TO_PROJECT -> Icons.Outlined.ViewKanban to when (val board = named ?: item.place) {
            null -> stringResource(R.string.issue_added_to_a_project_event, who, time)
            else -> stringResource(R.string.issue_added_to_project_event, who, board, time)
        }
        ConversationEvent.REMOVED_FROM_PROJECT -> Icons.Outlined.ViewKanban to when (val board = named ?: item.place) {
            null -> stringResource(R.string.issue_removed_from_a_project_event, who, time)
            else -> stringResource(R.string.issue_removed_from_project_event, who, board, time)
        }
        ConversationEvent.PROJECT_STATUS_CHANGED -> Icons.Outlined.ViewKanban to item.place.let { board ->
            when {
                named != null && board != null -> stringResource(R.string.issue_project_status_on_event, who, named, board, time)
                named != null -> stringResource(R.string.issue_project_status_event, who, named, time)
                board != null -> stringResource(R.string.issue_project_status_unnamed_on_event, who, board, time)
                else -> stringResource(R.string.issue_project_status_unnamed_event, who, time)
            }
        }
        ConversationEvent.STATUS_CHANGED -> Icons.Outlined.ViewKanban to stringResource(R.string.issue_status_event, who, subject, time)
        ConversationEvent.COMMENT_DELETED -> Icons.Outlined.DeleteOutline to stringResource(R.string.issue_comment_deleted_event, who, time)
        ConversationEvent.COMMENT_PINNED -> Icons.Outlined.PushPin to stringResource(R.string.issue_comment_pinned_event, who, time)
        ConversationEvent.COMMENT_UNPINNED -> Icons.Outlined.PushPin to stringResource(R.string.issue_comment_unpinned_event, who, time)
        ConversationEvent.DEPLOYED -> Icons.Outlined.RocketLaunch to either(R.string.issue_deployed_event, R.string.issue_deployed_unnamed_event)
        ConversationEvent.AUTO_MERGE_ENABLED -> Icons.Outlined.Schedule to stringResource(R.string.issue_auto_merge_enabled_event, who, time)
        ConversationEvent.AUTO_MERGE_DISABLED -> Icons.Outlined.Schedule to stringResource(R.string.issue_auto_merge_disabled_event, who, time)
        ConversationEvent.ADDED_TO_MERGE_QUEUE -> Icons.AutoMirrored.Outlined.CallMerge to stringResource(R.string.issue_added_to_merge_queue_event, who, time)
        ConversationEvent.REMOVED_FROM_MERGE_QUEUE -> Icons.AutoMirrored.Outlined.CallMerge to stringResource(R.string.issue_removed_from_merge_queue_event, who, time)
        ConversationEvent.USER_BLOCKED -> Icons.Outlined.Block to stringResource(R.string.issue_user_blocked_event, who, time)
        ConversationEvent.TYPE_SET -> Icons.Outlined.Category to stringResource(R.string.issue_type_set_event, who, subject, time)
        ConversationEvent.TYPE_REMOVED -> Icons.Outlined.Category to either(R.string.issue_type_removed_event, R.string.issue_type_removed_unnamed_event)
        ConversationEvent.DESCRIPTION_CHANGED -> Icons.Outlined.Edit to stringResource(R.string.issue_description_changed_event, who, time)
        ConversationEvent.CONFIDENTIAL -> Icons.Outlined.VisibilityOff to stringResource(R.string.issue_confidential_event, who, time)
        ConversationEvent.NOT_CONFIDENTIAL -> Icons.Outlined.Visibility to stringResource(R.string.issue_not_confidential_event, who, time)
        ConversationEvent.WEIGHT_SET -> Icons.Outlined.Scale to stringResource(R.string.issue_weight_set_event, who, subject, time)
        ConversationEvent.WEIGHT_REMOVED -> Icons.Outlined.Scale to stringResource(R.string.issue_weight_removed_event, who, time)
        ConversationEvent.ITERATION_SET -> Icons.Outlined.Loop to either(R.string.issue_iteration_set_event, R.string.issue_iteration_set_unnamed_event)
        ConversationEvent.ITERATION_REMOVED -> Icons.Outlined.Loop to either(R.string.issue_iteration_removed_event, R.string.issue_iteration_removed_unnamed_event)
    }
}

/**
 * How a conversation is named where its title isn't told: "#12", "!12" for a GitLab merge request, with its repository
 * in front when it isn't [here].
 */
internal fun IssueRef.reference(here: RepoId?): String {
    val sign = if (repo.forge.type.numbersMergeRequestsApart && isPullRequest == true) "!" else "#"
    val elsewhere = if (here == null || repo == here) "" else repo.fullName
    return "$elsewhere$sign$number"
}

/** How GitHub's reasons to lock a conversation read; null for none, or one it may add later. */
private fun lockReason(reason: String?): Int? = when (reason?.lowercase()?.replace('_', '-')?.replace(' ', '-')) {
    "off-topic" -> R.string.lock_reason_off_topic
    "too-heated" -> R.string.lock_reason_too_heated
    "resolved" -> R.string.lock_reason_resolved
    "spam" -> R.string.lock_reason_spam
    else -> null
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
