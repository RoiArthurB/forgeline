package fr.arthurbrugiere.forgeline.inbox

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.outlined.Adjust
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.AssignmentInd
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.model.NotificationReason
import fr.arthurbrugiere.forgeline.core.model.NotificationThread
import fr.arthurbrugiere.forgeline.core.model.SubjectState
import fr.arthurbrugiere.forgeline.core.model.SubjectType
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftColors

internal val InboxFilter.label: Int
    get() = when (this) {
        InboxFilter.UNREAD -> R.string.inbox_filter_unread
        InboxFilter.PARTICIPATING -> R.string.inbox_filter_participating
        InboxFilter.ALL -> R.string.inbox_filter_all
        InboxFilter.YOURS -> R.string.inbox_filter_yours
    }

/** What a thread is about, and where it stands once known: "Merged pull request", "Closed issue". */
@Composable
internal fun NotificationThread.kindLabel(): String {
    val isPull = type == SubjectType.PULL_REQUEST
    return stringResource(
        when (state) {
            null -> type.label
            SubjectState.OPEN -> if (isPull) R.string.inbox_state_open_pull else R.string.inbox_state_open_issue
            SubjectState.DRAFT -> R.string.inbox_state_draft_pull
            SubjectState.MERGED -> R.string.inbox_state_merged_pull
            SubjectState.CLOSED -> if (isPull) R.string.inbox_state_closed_pull else R.string.inbox_state_closed_issue
            SubjectState.NOT_PLANNED -> R.string.inbox_state_not_planned
        },
    )
}

/** A thread's tile, tinted like the Feed's states: fresh while open, cool once merged, warm once closed. */
internal fun NotificationThread.stateTint(colors: SoftColors): Color = when (state) {
    SubjectState.OPEN -> colors.fields[2]
    SubjectState.MERGED -> colors.fields[1]
    SubjectState.CLOSED -> colors.fields[0]
    null, SubjectState.DRAFT, SubjectState.NOT_PLANNED -> colors.surface
}

/** The tile's glyph: the kind, or how it ended. */
internal val NotificationThread.tileIcon: ImageVector
    get() = when (state) {
        SubjectState.MERGED -> Icons.AutoMirrored.Outlined.CallMerge
        SubjectState.CLOSED -> if (type == SubjectType.PULL_REQUEST) Icons.Outlined.Close else Icons.Outlined.CheckCircleOutline
        SubjectState.NOT_PLANNED -> Icons.Outlined.Block
        else -> if (reasonIsNeedsYou) reason.icon else type.icon
    }

private val NotificationThread.reasonIsNeedsYou: Boolean
    get() = reason in setOf(NotificationReason.MENTION, NotificationReason.TEAM_MENTION, NotificationReason.REVIEW_REQUESTED, NotificationReason.ASSIGN, NotificationReason.SECURITY_ALERT)

/** Why a thread waits on you, as a glyph. */
private val NotificationReason.icon: ImageVector
    get() = when (this) {
        NotificationReason.MENTION, NotificationReason.TEAM_MENTION -> Icons.Outlined.AlternateEmail
        NotificationReason.REVIEW_REQUESTED -> Icons.Outlined.RateReview
        NotificationReason.ASSIGN -> Icons.Outlined.AssignmentInd
        NotificationReason.SECURITY_ALERT -> Icons.Outlined.Shield
        else -> Icons.Outlined.Notifications
    }

private val SubjectType.icon: ImageVector
    get() = when (this) {
        SubjectType.ISSUE -> Icons.Outlined.Adjust
        SubjectType.PULL_REQUEST -> Icons.AutoMirrored.Outlined.CallMerge
        SubjectType.RELEASE -> Icons.Outlined.Sell
        SubjectType.DISCUSSION -> Icons.Outlined.Forum
        SubjectType.CHECK_SUITE -> Icons.Outlined.PlayCircleOutline
        SubjectType.COMMIT, SubjectType.OTHER -> Icons.Outlined.Notifications
    }

internal val SubjectType.label: Int
    get() = when (this) {
        SubjectType.ISSUE -> R.string.inbox_kind_issue
        SubjectType.PULL_REQUEST -> R.string.inbox_kind_pull_request
        SubjectType.RELEASE -> R.string.inbox_kind_release
        SubjectType.DISCUSSION -> R.string.inbox_kind_discussion
        SubjectType.CHECK_SUITE -> R.string.inbox_kind_checks
        SubjectType.COMMIT -> R.string.inbox_kind_commit
        SubjectType.OTHER -> R.string.inbox_kind_other
    }

internal val NotificationReason.label: Int
    get() = when (this) {
        NotificationReason.MENTION -> R.string.reason_mention
        NotificationReason.TEAM_MENTION -> R.string.reason_team_mention
        NotificationReason.REVIEW_REQUESTED -> R.string.reason_review_requested
        NotificationReason.ASSIGN -> R.string.reason_assign
        NotificationReason.AUTHOR -> R.string.reason_author
        NotificationReason.COMMENT -> R.string.reason_comment
        NotificationReason.STATE_CHANGE -> R.string.reason_state_change
        NotificationReason.SUBSCRIBED -> R.string.reason_subscribed
        NotificationReason.MANUAL -> R.string.reason_manual
        NotificationReason.CI_ACTIVITY -> R.string.reason_ci_activity
        NotificationReason.SECURITY_ALERT -> R.string.reason_security_alert
        NotificationReason.OTHER -> R.string.reason_other
    }
