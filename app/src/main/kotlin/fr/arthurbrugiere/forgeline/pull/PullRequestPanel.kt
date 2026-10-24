package fr.arthurbrugiere.forgeline.pull

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Commit
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.Check
import fr.arthurbrugiere.forgeline.core.model.CheckState
import fr.arthurbrugiere.forgeline.core.model.IssueDetails
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.MergeBlocker
import fr.arthurbrugiere.forgeline.core.model.MergeInfo
import fr.arthurbrugiere.forgeline.core.model.MergeMethod
import fr.arthurbrugiere.forgeline.core.model.ReviewThread
import fr.arthurbrugiere.forgeline.core.model.ReviewVerdict
import fr.arthurbrugiere.forgeline.core.model.overall
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftCheckRow
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftChoiceRow
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftColors
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftDangerButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTonalButton
import fr.arthurbrugiere.forgeline.core.ui.soft.softPressable
import fr.arthurbrugiere.forgeline.ui.message

/** What a pull request's panel asks of the screen that shows it. */
class PullActions(
    val onOpenChanges: () -> Unit = {},
    val onOpenCommits: () -> Unit = {},
    /** Opens the run a check is part of, in the app. */
    val onOpenRun: (Long) -> Unit = {},
    /** Opens what a check reported, in the app: its id on the forge, and its name. */
    val onOpenCheck: (Long, String) -> Unit = { _, _ -> },
    val onOpenUrl: (String) -> Unit = {},
    val onMerge: (MergeChoice) -> Unit = {},
    val onMergeErrorShown: () -> Unit = {},
    /** Takes back "merge once its checks pass". */
    val onCancelMergeWhenChecksPass: () -> Unit = {},
    /** Brings the pull request's branch up to date with the one it goes into. */
    val onUpdateBranch: () -> Unit = {},
    val onUpdateErrorShown: () -> Unit = {},
    /** Answers a thread on a line of the change. */
    val onReply: (ReviewThread, String) -> Unit = { _, _ -> },
    val onSetResolved: (ReviewThread, Boolean) -> Unit = { _, _ -> },
    /** Dismisses a review by its id, saying why. */
    val onDismissReview: (Long, String) -> Unit = { _, _ -> },
    val onThreadErrorShown: () -> Unit = {},
    val onReview: (ReviewVerdict, String) -> Unit = { _, _ -> },
    val onReviewErrorShown: () -> Unit = {},
)

/**
 * What stands behind a pull request's numbers, under them at the top of its conversation: the way to its files and its
 * commits. Where its checks stand and what can be decided on it are at the other end, with the reader's turn
 * ([PullRequestChecks], [PullRequestDecisions]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PullRequestPanel(issue: IssueDetails, actions: PullActions, modifier: Modifier = Modifier) {
    val pr = issue.pullRequest ?: return
    // One each: they wrap rather than stack as rows.
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PanelPill(Icons.AutoMirrored.Outlined.InsertDriveFile, pluralStringResource(R.plurals.pull_files_changed, pr.changedFiles, pr.changedFiles), onClick = actions.onOpenChanges)
        PanelPill(Icons.Outlined.Commit, pluralStringResource(R.plurals.issue_pr_commits, pr.commits, pr.commits), onClick = actions.onOpenCommits)
    }
}

/**
 * Reviewing and merging an open pull request, for someone signed in on its forge: buttons for the row the reader's turn
 * ends on, beside closing it and sending a comment, and the sheets they open. A draft can't be merged: whoever may
 * mark it ready ([onReady]) is offered that in Merge's place.
 */
@Composable
fun PullRequestDecisions(issue: IssueDetails, state: PullRequestUiState, actions: PullActions, onReady: (() -> Unit)? = null, isMarkingReady: Boolean = false) {
    val pr = issue.pullRequest ?: return
    if (!state.signedIn || issue.state != IssueState.OPEN) return
    var reviewing by rememberSaveable { mutableStateOf(false) }
    var merging by rememberSaveable { mutableStateOf(false) }
    // Each sheet closes on what it did.
    LaunchedEffect(state.done) {
        reviewing = false
        merging = false
    }
    val checks = state.checks.orEmpty()
    if (reviewing) {
        ReviewSheet(state.verdicts, remarks = 0, isSending = state.isSending, error = state.reviewError, onSubmit = actions.onReview) {
            reviewing = false
            actions.onReviewErrorShown()
        }
    }
    val mergeInfo = state.mergeInfo
    if (merging && mergeInfo != null) {
        MergeSheet(mergeInfo, pr.headRef, pr.isDraft, state.isMerging, state.mergeError, checks, actions.onMerge, actions.onCancelMergeWhenChecksPass) {
            merging = false
            actions.onMergeErrorShown()
        }
    }
    SoftTonalButton(stringResource(R.string.review_start), onClick = { reviewing = true })
    // The branch it goes into moved on: brought up to date before merging, by whoever may merge.
    if (mergeInfo?.behind == true && mergeInfo.canMerge && !mergeInfo.mergesWhenChecksPass) {
        SoftTonalButton(stringResource(if (state.isUpdating) R.string.pull_updating_branch else R.string.pull_update_branch), onClick = actions.onUpdateBranch, enabled = !state.isUpdating)
    }
    if (pr.isDraft && onReady != null) {
        SoftButton(stringResource(R.string.pull_ready_for_review), onClick = onReady, enabled = !isMarkingReady)
    } else if (mergeInfo?.canMerge == true) {
        // Marigold says "this is the way forward": not while a check failed or is still running, nor while the forge
        // names something in the way, nor once it is to merge it by itself.
        if (mergeInfo.mergesWhenChecksPass) {
            SoftTonalButton(stringResource(R.string.merge_start_scheduled), onClick = { merging = true })
        } else if (checks.overall() in UNSETTLED || mergeInfo.blockers.isNotEmpty()) {
            SoftTonalButton(stringResource(R.string.merge_start), onClick = { merging = true })
        } else {
            SoftButton(stringResource(R.string.merge_start), onClick = { merging = true })
        }
    }
}

/**
 * Where a pull request's checks stand, said near its end, above the reader's turn: what failed and what still runs, each
 * on a row that opens its run or its report in the app (its own page otherwise). What passed or was skipped is listed
 * too while there are few checks; past that it waits behind one row, so a long list doesn't push the turn away.
 */
@Composable
fun PullRequestChecks(checks: List<Check>, actions: PullActions, modifier: Modifier = Modifier) {
    val overall = checks.overall() ?: return
    val colors = Soft.colors
    // What failed first: it is what someone looks here for.
    val ordered = checks.sortedBy { if (it.state == CheckState.FAILURE) -1 else it.state.ordinal }
    val (unsettled, settled) = ordered.partition { it.state in UNSETTLED }
    var showSettled by rememberSaveable { mutableStateOf(false) }
    val listed = if (showSettled || checks.size <= SETTLED_SHOWN_UP_TO) ordered else unsettled
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 8.dp).semantics { heading() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(28.dp).background(overall.tint(colors), SoftTokens.TileCorner), contentAlignment = Alignment.Center) {
                Icon(overall.icon, contentDescription = null, tint = colors.ink, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(checksSummary(checks), style = Soft.type.name, color = colors.ink)
        }
        listed.forEach { check -> CheckRow(check, actions) }
        if (listed.size < ordered.size) {
            Text(
                pluralStringResource(R.plurals.checks_show_settled, settled.size, settled.size),
                style = Soft.type.control,
                color = colors.inkMuted,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(SoftTokens.RowCorner)
                    .clickable(role = Role.Button) { showSettled = true }
                    .heightIn(min = 48.dp)
                    .padding(start = 48.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
            )
        }
    }
}

/** How many checks are all listed, passed ones included, before those wait behind a row. */
private const val SETTLED_SHOWN_UP_TO = 5

/** One check with where it stands; it opens its run or its report in the app when it has one, its own page otherwise. */
@Composable
private fun CheckRow(check: Check, actions: PullActions) {
    val colors = Soft.colors
    val open: (() -> Unit)? = check.runId?.let { id -> { actions.onOpenRun(id) } }
        ?: check.checkId?.let { id -> { actions.onOpenCheck(id, check.name) } }
        ?: check.url?.let { url -> { actions.onOpenUrl(url) } }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(SoftTokens.RowCorner)
            .then(if (open != null) Modifier.clickable(role = Role.Button, onClick = open) else Modifier)
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).background(check.state.tint(colors), SoftTokens.TileCorner), contentAlignment = Alignment.Center) {
            Icon(check.state.icon, contentDescription = null, tint = colors.ink, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(check.name, style = Soft.type.control, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(stringResource(check.state.label), check.description).joinToString(" · "),
                style = Soft.type.meta,
                color = colors.inkMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (open != null) Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(18.dp))
    }
}

/** Where a pull request's checks stand when merging it isn't the plain next step. */
private val UNSETTLED = setOf(CheckState.FAILURE, CheckState.PENDING)

/** What a number stands for: its glyph on a tile (tinted where it has a state), and its count. */
@Composable
private fun PanelPill(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = Soft.colors.surface) {
    val colors = Soft.colors
    Row(
        Modifier
            .clip(SoftTokens.Pill)
            .background(colors.ground)
            .softPressable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).background(tint, SoftTokens.TileCorner), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = colors.ink, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(label, style = Soft.type.control, color = colors.ink)
    }
}

/** "3 checks passed", or what stops them from all having: "1 of 5 checks failed", "2 of 5 checks still running". */
@Composable
internal fun checksSummary(checks: List<Check>): String {
    val failed = checks.count { it.state == CheckState.FAILURE }
    val pending = checks.count { it.state == CheckState.PENDING }
    return when {
        failed > 0 -> pluralStringResource(R.plurals.checks_failed, checks.size, failed, checks.size)
        pending > 0 -> pluralStringResource(R.plurals.checks_running, checks.size, pending, checks.size)
        else -> pluralStringResource(R.plurals.checks_passed, checks.size, checks.size)
    }
}

internal val CheckState.icon: ImageVector
    get() = when (this) {
        CheckState.SUCCESS -> Icons.Outlined.Check
        CheckState.FAILURE -> Icons.Outlined.Close
        CheckState.PENDING -> Icons.Outlined.Schedule
        CheckState.SKIPPED -> Icons.Outlined.Remove
    }

/** Fresh once passed, warm once failed, cool while running: the tints runs wear elsewhere. */
internal fun CheckState.tint(colors: SoftColors): Color = when (this) {
    CheckState.SUCCESS -> colors.fields[2]
    CheckState.FAILURE -> colors.fields[0]
    CheckState.PENDING -> colors.fields[1]
    CheckState.SKIPPED -> colors.surface
}

internal val CheckState.label: Int
    get() = when (this) {
        CheckState.SUCCESS -> R.string.check_passed
        CheckState.FAILURE -> R.string.check_failed
        CheckState.PENDING -> R.string.check_running
        CheckState.SKIPPED -> R.string.check_skipped
    }

/** How to merge, among the ways the repository allows, and what stands in the way when the forge says something does. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MergeSheet(
    info: MergeInfo,
    branch: String,
    isDraft: Boolean,
    isMerging: Boolean,
    error: ForgeError?,
    checks: List<Check>,
    onMerge: (MergeChoice) -> Unit,
    onCancelWhenChecksPass: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Soft.colors
    var method by rememberSaveable { mutableStateOf(info.methods.firstOrNull() ?: MergeMethod.MERGE) }
    // The repository's own default, where it is a choice at all.
    var deleteBranch by rememberSaveable { mutableStateOf(info.deleteBranch == true) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.ground,
        contentColor = colors.ink,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.merge_title), style = Soft.type.name, color = colors.ink, modifier = Modifier.semantics { heading() })
            // What stands in the way, by name where the forge gives it.
            val blocked = when {
                isDraft -> listOf(R.string.merge_blocked_draft)
                info.blockers.isNotEmpty() -> info.blockers.map { it.label }
                info.mergeable == false -> listOf(R.string.merge_blocked)
                info.mergeable == null -> listOf(R.string.merge_checking)
                else -> emptyList()
            }
            // Where the checks stand is said again here, where the merge is decided: it was a screen away.
            val standing = checks.overall()
            if (standing in UNSETTLED) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(standing!!.tint(colors)).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(standing.icon, contentDescription = null, tint = colors.ink, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(checksSummary(checks), style = Soft.type.secondary, color = colors.ink)
                }
            }
            if (blocked.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.fields[0]).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    blocked.forEach { Text(stringResource(it), style = Soft.type.secondary, color = colors.ink) }
                }
            }
            if (info.mergesWhenChecksPass) {
                Text(
                    stringResource(R.string.merge_scheduled),
                    style = Soft.type.secondary,
                    color = colors.ink,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.fields[1]).padding(14.dp),
                )
            }
            Column(Modifier.selectableGroup()) {
                info.methods.forEach { option ->
                    SoftChoiceRow(stringResource(option.label), method == option, { method = option }, summary = stringResource(option.summary))
                }
            }
            if (info.deleteBranch != null) {
                SoftCheckRow(stringResource(R.string.merge_delete_branch, branch), deleteBranch, { deleteBranch = it }, summary = stringResource(R.string.merge_delete_branch_summary))
            }
            if (error != null) {
                Text(
                    stringResource(R.string.merge_failed, (error as? ForgeError.Http)?.message ?: stringResource(error.message)),
                    style = Soft.type.secondary,
                    color = colors.accent,
                )
            }
            // What the forge is unsure of can still be tried: it answers no if it is no. What it refuses is not offered,
            // and conflicts are never merged over.
            val canTry = !isMerging && !isDraft && info.methods.isNotEmpty()
            val enabled = canTry && info.mergeable != false && MergeBlocker.CONFLICTS !in info.blockers
            // Waiting for the checks is offered when they are all that is missing, even where the forge refuses a merge now.
            val enabledWhenChecksPass = enabled || (canTry && info.blockers.isNotEmpty() && info.blockers.all { it == MergeBlocker.CHECKS })
            val now = MergeChoice(method, deleteBranch)
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SoftTonalButton(stringResource(R.string.cancel), onDismiss)
                when {
                    // Set to merge by itself: that can be taken back.
                    info.mergesWhenChecksPass -> SoftTonalButton(stringResource(R.string.merge_unschedule), onCancelWhenChecksPass, enabled = !isMerging)
                    // Checks still running: waiting for them is the way forward, merging now the other way.
                    standing == CheckState.PENDING && info.canMergeWhenChecksPass && !isDraft -> {
                        SoftTonalButton(stringResource(R.string.merge_now), onClick = { onMerge(now) }, enabled = enabled)
                        SoftButton(stringResource(if (isMerging) R.string.merge_merging else R.string.merge_when_checks_pass), onClick = { onMerge(now.copy(whenChecksPass = true)) }, enabled = enabledWhenChecksPass)
                    }
                    // Against a failed check or a rule the forge names, merging (as whoever may bypass it) is said for
                    // what it is, on the ink button of what can't be taken back.
                    standing == CheckState.FAILURE || info.blockers.isNotEmpty() ->
                        SoftDangerButton(stringResource(if (isMerging) R.string.merge_merging else R.string.merge_anyway), onClick = { onMerge(now) }, enabled = enabled)
                    else -> SoftButton(stringResource(if (isMerging) R.string.merge_merging else R.string.merge_confirm), onClick = { onMerge(now) }, enabled = enabled)
                }
            }
        }
    }
}

private val MergeBlocker.label: Int
    get() = when (this) {
        MergeBlocker.CONFLICTS -> R.string.merge_blocker_conflicts
        MergeBlocker.CHECKS -> R.string.merge_blocker_checks
        MergeBlocker.REVIEWS -> R.string.merge_blocker_reviews
        MergeBlocker.CHANGES_REQUESTED -> R.string.merge_blocker_changes_requested
        MergeBlocker.THREADS -> R.string.merge_blocker_threads
        MergeBlocker.BEHIND -> R.string.merge_blocker_behind
        MergeBlocker.OTHER -> R.string.merge_blocker_other
    }

private val MergeMethod.label: Int
    get() = when (this) {
        MergeMethod.MERGE -> R.string.merge_method_merge
        MergeMethod.SQUASH -> R.string.merge_method_squash
        MergeMethod.REBASE -> R.string.merge_method_rebase
    }

private val MergeMethod.summary: Int
    get() = when (this) {
        MergeMethod.MERGE -> R.string.merge_method_merge_summary
        MergeMethod.SQUASH -> R.string.merge_method_squash_summary
        MergeMethod.REBASE -> R.string.merge_method_rebase_summary
    }
