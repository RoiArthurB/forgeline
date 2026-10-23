package fr.arthurbrugiere.forgeline.issue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.outlined.Adjust
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.model.IssueDetails
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.pull.PullActions
import fr.arthurbrugiere.forgeline.pull.PullRequestPanel
import fr.arthurbrugiere.forgeline.pull.PullRequestUiState
import fr.arthurbrugiere.forgeline.ui.LabelChip
import fr.arthurbrugiere.forgeline.ui.label
import fr.arthurbrugiere.forgeline.ui.relativeInSentence

/** Fresh while open, cool once merged, warm once closed: the header field says where the conversation stands. */
private val IssueDetails.tintIndex: Int
    get() = when (state) {
        IssueState.OPEN -> 2
        IssueState.MERGED -> 1
        else -> 0
    }

@Composable
internal fun Header(
    ref: IssueRef,
    issue: IssueDetails?,
    nowMillis: Long,
    onOpenRepo: (RepoId) -> Unit,
    onOpenUser: (String) -> Unit,
    pull: PullRequestUiState? = null,
    pullActions: PullActions = PullActions(),
) {
    val colors = Soft.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // The repository it belongs to, one tap away.
        Row(
            Modifier
                .clip(SoftTokens.Pill)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.issue_open_repo)) { onOpenRepo(ref.repo) }
                .heightIn(min = 48.dp)
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                ref.repo.fullName,
                style = Soft.type.secondary,
                color = colors.inkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(18.dp))
        }
        if (issue == null) {
            Text("#${ref.number}", style = Soft.type.hero, color = colors.ink, modifier = Modifier.semantics { heading() })
            return@Column
        }
        // The title, then its number (the owner's requested "Title - #123").
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                buildAnnotatedString {
                    append(issue.title)
                    withStyle(SpanStyle(color = colors.inkMuted)) { append(" - #${ref.number}") }
                },
                style = Soft.type.hero,
                color = colors.ink,
                modifier = Modifier.semantics { heading() },
            )
        }
        // Wraps at large text: on one row the sentence was squeezed beside the tags and clipped.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            StateTag(issue)
            if (issue.isLocked) HeaderTag(Icons.Outlined.Lock, stringResource(R.string.issue_locked))
            Text(
                stringResource(R.string.issue_opened_by, issue.author?.login ?: stringResource(R.string.deleted_account), relativeInSentence(issue.createdAt, nowMillis)),
                style = Soft.type.secondary,
                color = colors.inkMuted,
                // Not a pill: over two lines its round ends cut into the first and last letters.
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = issue.author != null) { issue.author?.let { onOpenUser(it.login) } },
            )
        }
        issue.pullRequest?.let { pr ->
            // Where it goes and how much it changes, in one unboxed line; the files and commits it counts are the panel's.
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = colors.ink)) { append(stringResource(R.string.issue_pr_branches, pr.baseRef, pr.headRef)) }
                    append("  ")
                    append(
                        if (pull != null) {
                            stringResource(R.string.issue_pr_changes, pr.additions, pr.deletions)
                        } else {
                            // No panel to count them (the forge can't show its change): said here instead.
                            stringResource(
                                R.string.issue_pr_stats,
                                pr.additions,
                                pr.deletions,
                                pluralStringResource(R.plurals.issue_pr_files, pr.changedFiles, pr.changedFiles),
                                pluralStringResource(R.plurals.issue_pr_commits, pr.commits, pr.commits),
                            )
                        },
                    )
                },
                style = Soft.type.meta,
                color = colors.inkMuted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (pull != null) PullRequestPanel(issue, pullActions)
        }
        if (issue.labels.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                issue.labels.forEach { LabelChip(it) }
            }
        }
        // Who it is on and what it is for: one quiet line each, only when there is something to say.
        if (issue.assignees.isNotEmpty()) {
            Text(
                stringResource(R.string.issue_assigned_to, issue.assignees.joinToString(", ") { it.login }),
                style = Soft.type.secondary,
                color = colors.inkMuted,
            )
        }
        issue.milestone?.let { Text(stringResource(R.string.issue_milestone, it.title), style = Soft.type.secondary, color = colors.inkMuted) }
        issue.dueDate?.let { Text(stringResource(R.string.issue_due, formatDay(it)), style = Soft.type.secondary, color = colors.inkMuted) }
    }
}

@Composable
private fun StateTag(issue: IssueDetails) {
    val colors = Soft.colors
    val (label, icon) = when {
        issue.pullRequest?.isDraft == true && issue.state == IssueState.OPEN -> R.string.issue_state_draft to Icons.AutoMirrored.Outlined.CallMerge
        issue.state == IssueState.OPEN -> R.string.issue_state_open to if (issue.pullRequest != null) Icons.AutoMirrored.Outlined.CallMerge else Icons.Outlined.Adjust
        issue.state == IssueState.MERGED -> R.string.issue_state_merged to Icons.AutoMirrored.Outlined.CallMerge
        else -> R.string.issue_state_closed to Icons.Outlined.CheckCircleOutline
    }
    HeaderTag(icon, stringResource(label))
}

/** A tag on the band: a glyph and a word, for where the conversation stands. */
@Composable
private fun HeaderTag(icon: ImageVector, label: String) {
    val colors = Soft.colors
    Row(
        Modifier.clip(SoftTokens.Pill).background(colors.surface).padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.ink)
        Text(label, style = Soft.type.label, color = colors.ink)
    }
}
