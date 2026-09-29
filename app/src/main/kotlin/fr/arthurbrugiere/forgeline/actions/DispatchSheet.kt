package fr.arthurbrugiere.forgeline.actions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.DispatchInput
import fr.arthurbrugiere.forgeline.core.model.DispatchInputType
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.Workflow
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSwitch
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTag
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTextField
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.softPressable
import fr.arthurbrugiere.forgeline.repo.Loadable
import fr.arthurbrugiere.forgeline.ui.message

/** The sheet for starting one of [repo]'s workflows by hand, with its own state; [onStarted] once the forge accepted. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowDispatchSheet(repo: RepoId, defaultBranch: String, onDismiss: () -> Unit, onStarted: () -> Unit) {
    val viewModel = hiltViewModel<DispatchViewModel, DispatchViewModel.Factory>(key = "${repo.fullName}/dispatch") { it.create(repo, defaultBranch) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.open() }
    LaunchedEffect(state.started) { if (state.started) onStarted() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Soft.colors.ground,
        contentColor = Soft.colors.ink,
    ) {
        DispatchContent(
            state = state,
            onSelect = viewModel::select,
            onRefChange = viewModel::setRef,
            onRefDone = viewModel::loadInputs,
            onValueChange = viewModel::setValue,
            onStart = viewModel::start,
            onRetry = { if (state.selected == null) viewModel.open() else viewModel.loadInputs() },
        )
    }
}

@Composable
fun DispatchContent(
    state: DispatchUiState,
    onSelect: (Workflow?) -> Unit,
    onRefChange: (String) -> Unit,
    onRefDone: () -> Unit,
    onValueChange: (String, String) -> Unit,
    onStart: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Soft.colors
    val selected = state.selected
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(bottom = 24.dp)) {
        if (selected == null) {
            Text(
                stringResource(R.string.dispatch_title),
                style = Soft.type.name,
                color = colors.ink,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            when (val workflows = state.workflows) {
                Loadable.Idle, Loadable.Loading -> SoftLoadingRows(stringResource(R.string.dispatch_loading), rows = 4, leadingDot = false)
                is Loadable.Failed -> SoftNotice(stringResource(R.string.dispatch_failed), stringResource(workflows.error.message), action = stringResource(R.string.retry), onAction = onRetry)
                is Loadable.Loaded -> if (workflows.value.isEmpty()) {
                    SoftNotice(stringResource(R.string.dispatch_none_title), stringResource(R.string.dispatch_none_body))
                } else {
                    state.sortedWorkflows.forEach { workflow ->
                        WorkflowRow(workflow, state.canStartByHand(workflow), onClick = { onSelect(workflow) })
                    }
                }
            }
            return@Column
        }
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onSelect(null) }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.dispatch_back), tint = colors.ink)
            }
            Text(selected.name, style = Soft.type.name, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Field(stringResource(R.string.dispatch_ref), stringResource(R.string.dispatch_ref_hint)) {
                SoftTextField(
                    value = state.ref,
                    onValueChange = onRefChange,
                    placeholder = stringResource(R.string.dispatch_ref),
                    leading = { Icon(Icons.AutoMirrored.Outlined.CallSplit, contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(18.dp)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onRefDone() }),
                )
            }
            when (val inputs = state.inputs) {
                Loadable.Idle, Loadable.Loading -> SoftLoadingRows(stringResource(R.string.dispatch_inputs_loading), rows = 2, leadingDot = false)
                is Loadable.Failed -> SoftNotice(stringResource(R.string.dispatch_inputs_failed), stringResource(inputs.error.message), action = stringResource(R.string.retry), onAction = onRetry)
                is Loadable.Loaded -> {
                    val declared = inputs.value
                    if (declared == null) {
                        SoftNotice(stringResource(R.string.dispatch_manual_title), stringResource(R.string.dispatch_manual_body))
                    } else {
                        declared.forEach { input -> InputField(input, state.values[input.name].orEmpty()) { onValueChange(input.name, it) } }
                        state.sendError?.let { error ->
                            Text(
                                if (error is ForgeError.Http && (error.status == 403 || error.status == 404)) {
                                    stringResource(R.string.run_action_no_access)
                                } else {
                                    stringResource(error.message)
                                },
                                style = Soft.type.secondary,
                                color = colors.accent,
                            )
                        }
                        if (state.canStart) {
                            SoftButton(stringResource(if (state.sending) R.string.run_asking_forge else R.string.dispatch_start), onClick = onStart)
                        } else if (state.missing.isNotEmpty()) {
                            Text(stringResource(R.string.dispatch_missing, state.missing.joinToString(", ")), style = Soft.type.secondary, color = colors.inkMuted)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A workflow to pick. [startable] is null while its file is being read; false greys it out, since it only runs on
 * events, and it can't be picked.
 */
@Composable
private fun WorkflowRow(workflow: Workflow, startable: Boolean?, onClick: () -> Unit) {
    val colors = Soft.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .then(if (startable != false) Modifier.softPressable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (startable) {
            true -> RowIcon(Icons.Outlined.PlayArrow, colors.fields[2])
            false -> RowIcon(Icons.Outlined.Bolt, colors.surface, colors.inkMuted)
            null -> Box(Modifier.size(36.dp).background(colors.surface, CircleShape), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.inkMuted, trackColor = colors.surface)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                workflow.name,
                style = Soft.type.body,
                color = if (startable == false) colors.inkMuted else colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (startable == false) stringResource(R.string.dispatch_events_only, workflow.path.substringAfterLast('/')) else workflow.path.substringAfterLast('/'),
                style = Soft.type.meta,
                color = colors.inkMuted,
            )
        }
    }
}

/** A labelled field: the name, then what the workflow says about it, then the control. */
@Composable
private fun Field(label: String, description: String?, required: Boolean = false, control: @Composable () -> Unit) {
    val colors = Soft.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = Soft.type.control, color = colors.ink)
            if (required) SoftTag(stringResource(R.string.dispatch_required))
        }
        description?.let { Text(it, style = Soft.type.secondary, color = colors.inkMuted) }
        control()
    }
}

@Composable
private fun InputField(input: DispatchInput, value: String, onChange: (String) -> Unit) {
    val colors = Soft.colors
    Field(input.name, input.description, input.required) {
        when (input.type) {
            DispatchInputType.BOOLEAN -> SoftSwitch(
                options = listOf(stringResource(R.string.dispatch_no), stringResource(R.string.dispatch_yes)),
                selected = if (value == "true") 1 else 0,
                onSelect = { onChange(if (it == 1) "true" else "false") },
            )
            DispatchInputType.CHOICE -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                input.options.forEach { option ->
                    val chosen = option == value
                    Text(
                        option,
                        style = Soft.type.control,
                        color = if (chosen) colors.onThumb else colors.ink,
                        modifier = Modifier
                            .clip(SoftTokens.Pill)
                            .background(if (chosen) colors.thumb else colors.surface)
                            .selectable(selected = chosen, role = Role.RadioButton, onClick = { onChange(option) })
                            .heightIn(min = 40.dp)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
            DispatchInputType.STRING, DispatchInputType.NUMBER, DispatchInputType.ENVIRONMENT -> SoftTextField(
                value = value,
                onValueChange = onChange,
                placeholder = input.default ?: input.name,
                keyboardOptions = KeyboardOptions(keyboardType = if (input.type == DispatchInputType.NUMBER) KeyboardType.Number else KeyboardType.Text),
            )
        }
    }
}
