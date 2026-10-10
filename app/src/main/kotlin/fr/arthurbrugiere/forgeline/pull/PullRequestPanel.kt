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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Commit
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
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
import fr.arthurbrugiere.forgeline.core.model.MergeInfo
import fr.arthurbrugiere.forgeline.core.model.MergeMethod
import fr.arthurbrugiere.forgeline.core.model.ReviewVerdict
import fr.arthurbrugiere.forgeline.core.model.overall
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftColors
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTonalButton
import fr.arthurbrugiere.forgeline.ui.message

/** What a pull request's panel asks of the screen that shows it. */
class PullActions(
    val onOpenChanges: () -> Unit = {},
    val onOpenCommits: () -> Unit = {},
    /** Opens the run a check is part of, in the app. */
    val onOpenRun: (Long) -> Unit = {},
    val onOpenUrl: (String) -> Unit = {},
    val onMerge: (MergeMethod) -> Unit = {},
    val onMergeErrorShown: () -> Unit = {},
    val onReview: (ReviewVerdict, String) -> Unit = { _, _ -> },
    val onReviewErrorShown: () -> Unit = {},
)

/**
 * What stands behind a pull request's numbers, under them in its conversation: the way to its files and its commits,
 * where its checks stand, and, for someone signed in on an open one, reviewing and merging it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PullRequestPanel(issue: IssueDetails, state: PullRequestUiState, actions: PullActions, modifier: Modifier = Modifier) {
    val pr = issue.pullRequest ?: return
    var showChecks by rememberSaveable { mutableStateOf(false) }
    var reviewing by rememberSaveable { mutableStateOf(false) }
    var merging by rememberSaveable { mutableStateOf(false) }
    // Each sheet closes on what it did.
    LaunchedEffect(state.done) {
        reviewing = false
        merging = false
    }
    val checks = state.checks.orEmpty()
    if (showChecks) ChecksSheet(checks, actions.onOpenRun, actions.onOpenUrl, onDismiss = { showChecks = false })
    if (reviewing) {
        ReviewSheet(state.verdicts, remarks = 0, isSending = state.isSending, error = state.reviewError, onSubmit = actions.onReview) {
            reviewing = false
            actions.onReviewErrorShown()
        }
    }
    val mergeInfo = state.mergeInfo
    if (merging && mergeInfo != null) {
        MergeSheet(mergeInfo, pr.isDraft, state.isMerging, state.mergeError, actions.onMerge) {
            merging = false
            actions.onMergeErrorShown()
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PanelRow(Icons.AutoMirrored.Outlined.InsertDriveFile, pluralStringResource(R.plurals.pull_files_changed, pr.changedFiles, pr.changedFiles), onClick = actions.onOpenChanges)
        PanelRow(Icons.Outlined.Commit, pluralStringResource(R.plurals.issue_pr_commits, pr.commits, pr.commits), onClick = actions.onOpenCommits)
        checks.overall()?.let { overall ->
            PanelRow(overall.icon, checksSummary(checks), tint = overall.tint(Soft.colors), onClick = { showChecks = true })
        }
        if (state.signedIn && issue.state == IssueState.OPEN) {
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftTonalButton(stringResource(R.string.review_start), onClick = { reviewing = true })
                if (mergeInfo?.canMerge == true) SoftButton(stringResource(R.string.merge_start), onClick = { merging = true })
            }
        }
    }
}

@Composable
private fun PanelRow(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = Soft.colors.surface) {
    val colors = Soft.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(SoftTokens.RowCorner)
            .background(colors.ground)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).background(tint, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = colors.ink, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(label, style = Soft.type.control, color = colors.ink, modifier = Modifier.weight(1f))
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(18.dp))
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

/** Mint once passed, ember once failed, lilac while running: the tints runs wear elsewhere. */
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

/** Each check with where it stands; one opens its run in the app when it has one, its own page otherwise. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChecksSheet(checks: List<Check>, onOpenRun: (Long) -> Unit, onOpenUrl: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = Soft.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.ground,
        contentColor = colors.ink,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(checksSummary(checks), style = Soft.type.name, color = colors.ink, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp).semantics { heading() })
            // What failed first: it is what someone opens this for.
            checks.sortedBy { it.state.ordinal.let { order -> if (it.state == CheckState.FAILURE) -1 else order } }.forEach { check ->
                val open: (() -> Unit)? = check.runId?.let { id -> { onDismiss(); onOpenRun(id) } } ?: check.url?.let { url -> { onOpenUrl(url) } }
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
                    Box(Modifier.size(28.dp).background(check.state.tint(colors), CircleShape), contentAlignment = Alignment.Center) {
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
        }
    }
}

/** How to merge, among the ways the repository allows, and what stands in the way when the forge says something does. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MergeSheet(info: MergeInfo, isDraft: Boolean, isMerging: Boolean, error: ForgeError?, onMerge: (MergeMethod) -> Unit, onDismiss: () -> Unit) {
    val colors = Soft.colors
    var method by rememberSaveable { mutableStateOf(info.methods.firstOrNull() ?: MergeMethod.MERGE) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.ground,
        contentColor = colors.ink,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.merge_title), style = Soft.type.name, color = colors.ink, modifier = Modifier.semantics { heading() })
            val blocked = when {
                isDraft -> R.string.merge_blocked_draft
                info.mergeable == false -> R.string.merge_blocked
                info.mergeable == null -> R.string.merge_checking
                else -> null
            }
            if (blocked != null) {
                Text(
                    stringResource(blocked),
                    style = Soft.type.secondary,
                    color = colors.ink,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.fields[0]).padding(14.dp),
                )
            }
            Column {
                info.methods.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(SoftTokens.RowCorner)
                            .selectable(selected = method == option, role = Role.RadioButton, onClick = { method = option })
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (method == option) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (method == option) colors.accent else colors.inkMuted,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(option.label), style = Soft.type.control, color = colors.ink)
                            Text(stringResource(option.summary), style = Soft.type.meta, color = colors.inkMuted)
                        }
                    }
                }
            }
            if (error != null) {
                Text(
                    stringResource(R.string.merge_failed, (error as? ForgeError.Http)?.message ?: stringResource(error.message)),
                    style = Soft.type.secondary,
                    color = colors.accent,
                )
            }
            // What the forge is unsure of can still be tried: it answers no if it is no. What it refuses is not offered.
            SoftButton(
                stringResource(if (isMerging) R.string.merge_merging else R.string.merge_confirm),
                onClick = { onMerge(method) },
                enabled = !isMerging && !isDraft && info.mergeable != false && info.methods.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
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
