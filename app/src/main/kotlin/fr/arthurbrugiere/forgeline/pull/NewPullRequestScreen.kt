package fr.arthurbrugiere.forgeline.pull

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftChoicePill
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTextField
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.navigation.NewPullRequestRoute
import fr.arthurbrugiere.forgeline.repo.Loadable
import fr.arthurbrugiere.forgeline.ui.message
import fr.arthurbrugiere.forgeline.ui.sideSafeArea

@Composable
fun NewPullRequestRoute(route: NewPullRequestRoute, onBack: () -> Unit, onCreated: (IssueRef) -> Unit) {
    val viewModel = hiltViewModel<NewPullRequestViewModel, NewPullRequestViewModel.Factory>(key = "new-pull-${route.repo.key}") { it.create(route.repo) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.created) { state.created?.let(onCreated) }
    NewPullRequestScreen(
        state,
        onBack = onBack,
        onRetry = viewModel::loadBranches,
        onBaseChange = viewModel::baseChanged,
        onHeadChange = viewModel::headChanged,
        onTitleChange = viewModel::titleChanged,
        onBodyChange = viewModel::bodyChanged,
        onDraftChange = viewModel::draftChanged,
        onSend = viewModel::send,
    )
}

/** The form that opens a pull request: where the change goes, where it comes from, what it is called and why. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewPullRequestScreen(
    state: NewPullRequestUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onBaseChange: (String) -> Unit,
    onHeadChange: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onDraftChange: (Boolean) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Soft.colors
    Column(
        modifier.fillMaxSize().background(colors.ground).sideSafeArea().verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SoftHeader(
            tint = colors.fields[2],
            title = stringResource(R.string.new_pull_title),
            onBack = onBack,
            backDescription = stringResource(R.string.navigate_up),
            content = { Text(state.repo.fullName, style = Soft.type.secondary, color = colors.inkMuted) },
        )
        when (val branches = state.branches) {
            Loadable.Idle, Loadable.Loading -> SoftLoadingRows(stringResource(R.string.new_pull_loading), rows = 3, leadingDot = false)
            is Loadable.Failed -> SoftNotice(stringResource(R.string.new_pull_branches_failed), stringResource(branches.error.message), action = stringResource(R.string.retry), onAction = onRetry)
            is Loadable.Loaded -> if (branches.value.size < 2) {
                SoftNotice(stringResource(R.string.new_pull_one_branch_title), stringResource(R.string.new_pull_one_branch_body))
            } else {
                Column(
                    Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    val names = branches.value
                    val pick = stringResource(R.string.new_pull_pick_branch)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.new_pull_into), style = Soft.type.secondary, color = colors.inkMuted)
                        SoftChoicePill(
                            name = stringResource(R.string.new_pull_base),
                            options = names,
                            selected = names.indexOf(state.base).coerceAtLeast(0),
                            onSelect = { onBaseChange(names[it]) },
                            background = colors.surface,
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.new_pull_from), style = Soft.type.secondary, color = colors.inkMuted)
                        // Nothing is picked for the reader: the first option says to pick.
                        val heads = listOf(pick) + names
                        SoftChoicePill(
                            name = stringResource(R.string.new_pull_head),
                            options = heads,
                            selected = (names.indexOf(state.head) + 1).coerceAtLeast(0),
                            onSelect = { if (it > 0) onHeadChange(names[it - 1]) },
                            background = colors.surface,
                        )
                    }
                    if (state.head != null && state.head == state.base) {
                        Text(stringResource(R.string.new_pull_same_branch), style = Soft.type.secondary, color = colors.accent)
                    }
                    SoftTextField(value = state.title, onValueChange = onTitleChange, placeholder = stringResource(R.string.new_issue_title_placeholder))
                    SoftTextField(
                        value = state.body, onValueChange = onBodyChange, placeholder = stringResource(R.string.new_pull_body_hint),
                        singleLine = false, minLines = 5, maxLines = 14,
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(SoftTokens.RowCorner)
                            .toggleable(value = state.draft, role = Role.Checkbox, onValueChange = onDraftChange)
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (state.draft) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                            contentDescription = null,
                            tint = if (state.draft) colors.accent else colors.inkMuted,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.new_pull_draft), style = Soft.type.control, color = colors.ink)
                            Text(stringResource(R.string.new_pull_draft_summary), style = Soft.type.meta, color = colors.inkMuted)
                        }
                    }
                    state.error?.let { error ->
                        Text(
                            stringResource(R.string.new_pull_failed, (error as? ForgeError.Http)?.message ?: stringResource(error.message)),
                            style = Soft.type.secondary,
                            color = colors.accent,
                        )
                    }
                    SoftButton(
                        stringResource(if (state.isSending) R.string.new_pull_sending else R.string.new_pull_send),
                        onClick = onSend,
                        enabled = state.canSend,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
