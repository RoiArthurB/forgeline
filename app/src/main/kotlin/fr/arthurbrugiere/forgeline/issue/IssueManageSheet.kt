package fr.arthurbrugiere.forgeline.issue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.CloseReason
import fr.arthurbrugiere.forgeline.core.model.ConversationAction
import fr.arthurbrugiere.forgeline.core.model.Milestone
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTextField
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTonalButton
import fr.arthurbrugiere.forgeline.core.ui.soft.softPressable
import fr.arthurbrugiere.forgeline.repo.Loadable
import fr.arthurbrugiere.forgeline.ui.Avatar
import fr.arthurbrugiere.forgeline.ui.LabelChip
import fr.arthurbrugiere.forgeline.ui.message

/** Everything the sheet that manages a conversation can ask for. */
class ManageActions(
    val onOpened: () -> Unit = {},
    val onDoneShown: () -> Unit = {},
    val onLoadLabels: () -> Unit = {},
    val onLoadAssignable: () -> Unit = {},
    val onLoadMilestones: () -> Unit = {},
    val onSetLabels: (List<String>) -> Unit = {},
    val onSetAssignees: (List<String>) -> Unit = {},
    val onSetMilestone: (Milestone?) -> Unit = {},
    val onClose: (CloseReason) -> Unit = {},
    val onToggleLocked: () -> Unit = {},
    val onTogglePinned: () -> Unit = {},
    val onDuplicate: () -> Unit = {},
    val onTransfer: (String) -> Unit = {},
    val onDelete: () -> Unit = {},
)

enum class ManagePage { MENU, LABELS, ASSIGNEES, MILESTONE, TRANSFER, DELETE }

/** Whether there is anything to manage: an action the reader may take, or an issue to start another from. */
fun IssueUiState.canManage(signedIn: Boolean): Boolean = signedIn && issue != null && (actions.isNotEmpty() || issue.pullRequest == null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IssueManageSheet(state: IssueUiState, actions: ManageActions, onDismiss: () -> Unit) {
    val colors = Soft.colors
    LaunchedEffect(Unit) { actions.onOpened() }
    // A change that went through closes the sheet on what it changed.
    LaunchedEffect(state.manage.done) {
        if (state.manage.done) {
            actions.onDoneShown()
            onDismiss()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.ground,
        contentColor = colors.ink,
    ) {
        IssueManageContent(state, actions, onDismiss)
    }
}

/**
 * What can be done to a conversation, one row each, then a page for the ones that take a choice. Only what the forge
 * can do and the reader may do is listed.
 */
@Composable
fun IssueManageContent(state: IssueUiState, actions: ManageActions, onDismiss: () -> Unit, startPage: ManagePage = ManagePage.MENU) {
    var page by rememberSaveable { mutableStateOf(startPage) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        when (page) {
            ManagePage.MENU -> Menu(state, actions, onOpen = { page = it }, onDismiss = onDismiss)
            ManagePage.LABELS -> LabelsPage(state, actions) { page = ManagePage.MENU }
            ManagePage.ASSIGNEES -> AssigneesPage(state, actions) { page = ManagePage.MENU }
            ManagePage.MILESTONE -> MilestonePage(state, actions) { page = ManagePage.MENU }
            ManagePage.TRANSFER -> TransferPage(state, actions) { page = ManagePage.MENU }
            ManagePage.DELETE -> DeletePage(state, actions) { page = ManagePage.MENU }
        }
        state.manage.error?.let { error ->
            Text(
                stringResource(
                    when {
                        error == ForgeError.Unauthorized -> R.string.manage_error_expired
                        error is ForgeError.Http && (error.status == 403 || error.status == 404) -> R.string.manage_error_refused
                        error == ForgeError.Network -> R.string.issue_state_error_offline
                        else -> R.string.issue_state_error
                    },
                ),
                style = Soft.type.secondary,
                color = Soft.colors.accent,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Menu(state: IssueUiState, actions: ManageActions, onOpen: (ManagePage) -> Unit, onDismiss: () -> Unit) {
    val issue = state.issue ?: return
    val can = state.actions
    val enabled = !state.manage.isWorking
    val none = stringResource(R.string.manage_none)
    PageTitle(stringResource(R.string.manage_title), onBack = null)
    if (ConversationAction.LABELS in can) {
        MenuRow(Icons.AutoMirrored.Outlined.Label, stringResource(R.string.manage_labels), issue.labels.joinToString(", ") { it.name }.ifEmpty { none }, enabled) {
            actions.onLoadLabels()
            onOpen(ManagePage.LABELS)
        }
    }
    if (ConversationAction.ASSIGNEES in can) {
        MenuRow(Icons.Outlined.PersonOutline, stringResource(R.string.manage_assignees), issue.assignees.joinToString(", ") { it.login }.ifEmpty { none }, enabled) {
            actions.onLoadAssignable()
            onOpen(ManagePage.ASSIGNEES)
        }
    }
    if (ConversationAction.MILESTONE in can) {
        MenuRow(Icons.Outlined.Flag, stringResource(R.string.manage_milestone), issue.milestone?.title ?: none, enabled) {
            actions.onLoadMilestones()
            onOpen(ManagePage.MILESTONE)
        }
    }
    if (ConversationAction.CLOSE_REASON in can) {
        MenuRow(Icons.Outlined.Block, stringResource(R.string.manage_close_not_planned), null, enabled) { actions.onClose(CloseReason.NOT_PLANNED) }
        MenuRow(Icons.Outlined.Difference, stringResource(R.string.manage_close_duplicate), null, enabled) { actions.onClose(CloseReason.DUPLICATE) }
    }
    if (ConversationAction.LOCK in can) {
        MenuRow(
            if (issue.isLocked) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
            stringResource(if (issue.isLocked) R.string.manage_unlock else R.string.manage_lock),
            null, enabled, onClick = actions.onToggleLocked,
        )
    }
    if (ConversationAction.PIN in can) {
        // Not to be pressed until the forge has said whether it is pinned.
        MenuRow(
            Icons.Outlined.PushPin,
            stringResource(if (state.manage.pinned == true) R.string.manage_unpin else R.string.manage_pin),
            null, enabled && state.manage.pinned != null, onClick = actions.onTogglePinned,
        )
    }
    if (issue.pullRequest == null) {
        MenuRow(Icons.Outlined.ContentCopy, stringResource(R.string.manage_duplicate), null, enabled) {
            actions.onDuplicate()
            onDismiss()
        }
    }
    if (ConversationAction.TRANSFER in can) {
        MenuRow(Icons.Outlined.SwapHoriz, stringResource(R.string.manage_transfer), null, enabled) { onOpen(ManagePage.TRANSFER) }
    }
    if (ConversationAction.DELETE in can) {
        MenuRow(Icons.Outlined.DeleteOutline, stringResource(R.string.manage_delete), null, enabled, tint = Soft.colors.accent) { onOpen(ManagePage.DELETE) }
    }
}

@Composable
private fun PageTitle(title: String, onBack: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = if (onBack != null) 4.dp else 20.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.manage_back), tint = Soft.colors.ink)
            }
        }
        Text(title, style = Soft.type.name, color = Soft.colors.ink, modifier = Modifier.semantics { heading() })
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, value: String?, enabled: Boolean, tint: Color = Soft.colors.ink, onClick: () -> Unit) {
    val colors = Soft.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .then(if (enabled) Modifier.softPressable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 52.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) tint else colors.inkMuted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = Soft.type.body, color = if (enabled) tint else colors.inkMuted)
            if (value != null) Text(value, style = Soft.type.meta, color = colors.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A row that is chosen or not, with its check on the leading side like the branch picker's. */
@Composable
private fun ChoiceRow(chosen: Boolean, enabled: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .toggleable(value = chosen, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .heightIn(min = 52.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (chosen) Icon(Icons.Outlined.Check, contentDescription = null, tint = Soft.colors.accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        content()
    }
}

/** A list the repository offers: placeholder rows while it loads, a way to ask again when it failed. */
@Composable
private fun <T> Choices(loadable: Loadable<List<T>>, empty: String, onRetry: () -> Unit, content: @Composable (List<T>) -> Unit) {
    when (loadable) {
        Loadable.Idle, Loadable.Loading -> SoftLoadingRows(stringResource(R.string.manage_loading), rows = 3, leadingDot = false)
        is Loadable.Failed -> SoftNotice(
            stringResource(R.string.manage_load_failed),
            stringResource(loadable.error.message),
            action = stringResource(R.string.retry),
            onAction = onRetry,
        )
        is Loadable.Loaded -> if (loadable.value.isEmpty()) {
            Text(empty, style = Soft.type.body, color = Soft.colors.inkMuted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
        } else {
            content(loadable.value)
        }
    }
}

@Composable
private fun Save(working: Boolean, onSave: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.CenterEnd) {
        SoftButton(stringResource(if (working) R.string.manage_saving else R.string.manage_save), onSave, enabled = !working)
    }
}

@Composable
private fun LabelsPage(state: IssueUiState, actions: ManageActions, onBack: () -> Unit) {
    val current = state.issue?.labels.orEmpty().map { it.name }
    var chosen by rememberSaveable { mutableStateOf(current) }
    PageTitle(stringResource(R.string.manage_labels), onBack)
    Choices(state.manage.labels, stringResource(R.string.manage_no_labels), actions.onLoadLabels) { labels ->
        labels.forEach { label ->
            ChoiceRow(label.name in chosen, !state.manage.isWorking, { chosen = if (label.name in chosen) chosen - label.name else chosen + label.name }) {
                LabelChip(label)
            }
        }
        Save(state.manage.isWorking) { actions.onSetLabels(chosen) }
    }
}

@Composable
private fun AssigneesPage(state: IssueUiState, actions: ManageActions, onBack: () -> Unit) {
    val colors = Soft.colors
    val current = state.issue?.assignees.orEmpty().map { it.login }
    var chosen by rememberSaveable { mutableStateOf(current) }
    PageTitle(stringResource(R.string.manage_assignees), onBack)
    Choices(state.manage.assignable, stringResource(R.string.manage_no_assignable), actions.onLoadAssignable) { users ->
        users.forEach { user ->
            ChoiceRow(user.login in chosen, !state.manage.isWorking, { chosen = if (user.login in chosen) chosen - user.login else chosen + user.login }) {
                Avatar(user.avatarUrl, user.login, size = 28.dp, placeholderColor = colors.surface, placeholderContentColor = colors.inkMuted)
                Spacer(Modifier.width(12.dp))
                Text(user.login, style = Soft.type.body, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Save(state.manage.isWorking) { actions.onSetAssignees(chosen) }
    }
}

/** One milestone at most, so choosing is saving: there is nothing to confirm. */
@Composable
private fun MilestonePage(state: IssueUiState, actions: ManageActions, onBack: () -> Unit) {
    val colors = Soft.colors
    val current = state.issue?.milestone
    PageTitle(stringResource(R.string.manage_milestone), onBack)
    Choices(state.manage.milestones, stringResource(R.string.manage_no_milestones), actions.onLoadMilestones) { milestones ->
        ChoiceRow(current == null, !state.manage.isWorking, { actions.onSetMilestone(null) }) {
            Text(stringResource(R.string.manage_no_milestone), style = Soft.type.body, color = colors.inkMuted)
        }
        milestones.forEach { milestone ->
            ChoiceRow(current?.id == milestone.id, !state.manage.isWorking, { actions.onSetMilestone(milestone) }) {
                Text(milestone.title, style = Soft.type.body, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun TransferPage(state: IssueUiState, actions: ManageActions, onBack: () -> Unit) {
    val colors = Soft.colors
    var destination by rememberSaveable { mutableStateOf("") }
    PageTitle(stringResource(R.string.manage_transfer), onBack)
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.manage_transfer_body, state.ref.repo.owner), style = Soft.type.body, color = colors.inkMuted)
        SoftTextField(
            value = destination,
            onValueChange = { destination = it },
            placeholder = stringResource(R.string.manage_transfer_placeholder),
            readOnly = state.manage.isWorking,
            modifier = Modifier.fillMaxWidth(),
        )
        SoftButton(
            stringResource(if (state.manage.isWorking) R.string.manage_transferring else R.string.manage_transfer_confirm),
            { actions.onTransfer(destination) },
            Modifier.align(Alignment.End),
            enabled = !state.manage.isWorking && transferTarget(state.ref.repo, destination) != null,
        )
    }
}

/** Deleting can't be undone: it is said, and takes a second press. */
@Composable
private fun DeletePage(state: IssueUiState, actions: ManageActions, onBack: () -> Unit) {
    PageTitle(stringResource(R.string.manage_delete), onBack)
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.manage_delete_body), style = Soft.type.body, color = Soft.colors.inkMuted)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SoftTonalButton(stringResource(R.string.manage_cancel), onBack, enabled = !state.manage.isWorking)
            SoftButton(
                stringResource(if (state.manage.isWorking) R.string.manage_deleting else R.string.manage_delete_confirm),
                actions.onDelete,
                enabled = !state.manage.isWorking,
            )
        }
    }
}
