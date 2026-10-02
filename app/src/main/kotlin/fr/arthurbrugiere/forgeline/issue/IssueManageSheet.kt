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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.ui.text.input.KeyboardType
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.RepoAccess
import fr.arthurbrugiere.forgeline.ui.relative
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
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
    val onSetDueDate: (LocalDate?) -> Unit = {},
    val onLoadTracking: () -> Unit = {},
    val onToggleTimer: () -> Unit = {},
    val onAddTime: (seconds: Long) -> Unit = {},
    val onLoadDependencies: () -> Unit = {},
    val onAddDependency: (String) -> Unit = {},
    val onRemoveDependency: (IssueRef) -> Unit = {},
    /** Opens the conversation on its forge's site, for what only the site can do. */
    val onOpenOnForge: () -> Unit = {},
)

enum class ManagePage { MENU, LABELS, ASSIGNEES, MILESTONE, DUE_DATE, TIME, DEPENDENCIES, TRANSFER, DELETE }

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
fun IssueManageContent(
    state: IssueUiState,
    actions: ManageActions,
    onDismiss: () -> Unit,
    startPage: ManagePage = ManagePage.MENU,
    nowMillis: Long = System.currentTimeMillis(),
) {
    var page by rememberSaveable { mutableStateOf(startPage) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        when (page) {
            ManagePage.MENU -> Menu(state, actions, onOpen = { page = it }, onDismiss = onDismiss)
            ManagePage.LABELS -> LabelsPage(state, actions) { page = ManagePage.MENU }
            ManagePage.ASSIGNEES -> AssigneesPage(state, actions) { page = ManagePage.MENU }
            ManagePage.MILESTONE -> MilestonePage(state, actions) { page = ManagePage.MENU }
            ManagePage.DUE_DATE -> DueDatePage(state, actions) { page = ManagePage.MENU }
            ManagePage.TIME -> TimePage(state, actions, nowMillis) { page = ManagePage.MENU }
            ManagePage.DEPENDENCIES -> DependenciesPage(state, actions) { page = ManagePage.MENU }
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
    if (ConversationAction.DUE_DATE in can) {
        MenuRow(Icons.Outlined.Event, stringResource(R.string.manage_due_date), issue.dueDate?.let { formatDay(it) } ?: none, enabled) { onOpen(ManagePage.DUE_DATE) }
    }
    if (ConversationAction.TIME_TRACKING in can) {
        MenuRow(Icons.Outlined.Timer, stringResource(R.string.manage_time), null, enabled) {
            actions.onLoadTracking()
            onOpen(ManagePage.TIME)
        }
    }
    if (ConversationAction.DEPENDENCIES in can) {
        MenuRow(Icons.Outlined.AccountTree, stringResource(R.string.manage_dependencies), null, enabled) {
            actions.onLoadDependencies()
            onOpen(ManagePage.DEPENDENCIES)
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
    // A forge whose API can't lock still locks on its site: whoever may is sent there.
    if (ConversationAction.LOCK !in state.supported && state.access >= RepoAccess.WRITE) {
        MenuRow(
            if (issue.isLocked) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
            stringResource(if (issue.isLocked) R.string.manage_unlock else R.string.manage_lock),
            stringResource(R.string.manage_on_forge, state.ref.repo.forge.displayName),
            enabled,
        ) {
            actions.onOpenOnForge()
            onDismiss()
        }
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

/** The day it is due, picked on a calendar. Taking it away is its own action: there is no "no day" to pick. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DueDatePage(state: IssueUiState, actions: ManageActions, onBack: () -> Unit) {
    val colors = Soft.colors
    val current = state.issue?.dueDate
    // The calendar counts days in UTC, which is how the day is kept.
    val picker = rememberDatePickerState(initialSelectedDateMillis = current?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
    val picked = picker.selectedDateMillis?.let { Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate() }
    PageTitle(stringResource(R.string.manage_due_date), onBack)
    DatePicker(
        state = picker,
        title = null,
        headline = null,
        showModeToggle = false,
        colors = DatePickerDefaults.colors(
            containerColor = colors.ground,
            weekdayContentColor = colors.inkMuted,
            subheadContentColor = colors.inkMuted,
            navigationContentColor = colors.ink,
            yearContentColor = colors.ink,
            currentYearContentColor = colors.accent,
            selectedYearContentColor = colors.onThumb,
            selectedYearContainerColor = colors.thumb,
            dayContentColor = colors.ink,
            selectedDayContentColor = colors.onThumb,
            selectedDayContainerColor = colors.thumb,
            todayContentColor = colors.accent,
            todayDateBorderColor = colors.accent,
            dividerColor = colors.surface,
        ),
    )
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (current != null) SoftTonalButton(stringResource(R.string.manage_due_remove), { actions.onSetDueDate(null) }, enabled = !state.manage.isWorking)
        SoftButton(
            stringResource(if (state.manage.isWorking) R.string.manage_saving else R.string.manage_save),
            { actions.onSetDueDate(picked) },
            enabled = !state.manage.isWorking && picked != null && picked != current,
        )
    }
}

/** How long, in hours and minutes: seconds don't matter to time spent on an issue. */
@Composable
internal fun duration(seconds: Long): String {
    val minutes = seconds / 60
    return if (minutes >= 60) stringResource(R.string.duration_hours_minutes, minutes / 60, minutes % 60) else stringResource(R.string.duration_minutes, minutes)
}

/** The time spent by everyone, the reader's own timer, and a way to record time after the fact. */
@Composable
private fun TimePage(state: IssueUiState, actions: ManageActions, nowMillis: Long, onBack: () -> Unit) {
    val colors = Soft.colors
    var hours by rememberSaveable { mutableStateOf("") }
    var minutes by rememberSaveable { mutableStateOf("") }
    PageTitle(stringResource(R.string.manage_time), onBack)
    when (val tracking = state.manage.tracking) {
        Loadable.Idle, Loadable.Loading -> SoftLoadingRows(stringResource(R.string.manage_loading), rows = 2, leadingDot = false)
        is Loadable.Failed -> SoftNotice(
            stringResource(R.string.manage_load_failed),
            stringResource(tracking.error.message),
            action = stringResource(R.string.retry),
            onAction = actions.onLoadTracking,
        )
        is Loadable.Loaded -> Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val working = state.manage.isWorking
            Text(
                if (tracking.value.totalSeconds > 0) stringResource(R.string.manage_time_spent, duration(tracking.value.totalSeconds)) else stringResource(R.string.manage_time_none),
                style = Soft.type.body,
                color = colors.ink,
            )
            val since = tracking.value.runningSince
            if (since != null) {
                Text(stringResource(R.string.manage_timer_running, relative(since, nowMillis)), style = Soft.type.body, color = colors.inkMuted)
                SoftButton(stringResource(R.string.manage_timer_stop), actions.onToggleTimer, enabled = !working)
            } else {
                SoftTonalButton(stringResource(R.string.manage_timer_start), actions.onToggleTimer, enabled = !working)
            }
            Text(stringResource(R.string.manage_time_add_title), style = Soft.type.label, color = colors.inkMuted, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftTextField(
                    hours, { hours = it.filter(Char::isDigit).take(3) }, stringResource(R.string.manage_time_hours),
                    Modifier.weight(1f), readOnly = working, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                SoftTextField(
                    minutes, { minutes = it.filter(Char::isDigit).take(3) }, stringResource(R.string.manage_time_minutes),
                    Modifier.weight(1f), readOnly = working, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            val seconds = (hours.toLongOrNull() ?: 0) * 3_600 + (minutes.toLongOrNull() ?: 0) * 60
            SoftButton(stringResource(R.string.manage_time_add), { actions.onAddTime(seconds) }, Modifier.align(Alignment.End), enabled = !working && seconds > 0)
        }
    }
}

/** What this conversation waits on: each can be taken away, and another added by its number. */
@Composable
private fun DependenciesPage(state: IssueUiState, actions: ManageActions, onBack: () -> Unit) {
    val colors = Soft.colors
    var written by rememberSaveable { mutableStateOf("") }
    val working = state.manage.isWorking
    PageTitle(stringResource(R.string.manage_dependencies), onBack)
    when (val dependencies = state.manage.dependencies) {
        Loadable.Idle, Loadable.Loading -> SoftLoadingRows(stringResource(R.string.manage_loading), rows = 2, leadingDot = false)
        is Loadable.Failed -> SoftNotice(
            stringResource(R.string.manage_load_failed),
            stringResource(dependencies.error.message),
            action = stringResource(R.string.retry),
            onAction = actions.onLoadDependencies,
        )
        is Loadable.Loaded -> {
            if (dependencies.value.isEmpty()) {
                Text(stringResource(R.string.manage_dependencies_none), style = Soft.type.body, color = colors.inkMuted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            dependencies.value.forEach { linked ->
                // One in another repository names it.
                val name = (if (linked.ref.repo == state.ref.repo) "" else linked.ref.repo.fullName) + "#${linked.ref.number}"
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(linked.title, style = Soft.type.body, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(if (linked.state == IssueState.OPEN) R.string.manage_dependency_open else R.string.manage_dependency_closed, name),
                            style = Soft.type.meta,
                            color = colors.inkMuted,
                        )
                    }
                    IconButton(onClick = { actions.onRemoveDependency(linked.ref) }, enabled = !working) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.manage_dependency_remove, name), tint = colors.inkMuted)
                    }
                }
            }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SoftTextField(
                    written, { written = it }, stringResource(R.string.manage_dependency_placeholder),
                    Modifier.fillMaxWidth(), readOnly = working,
                )
                SoftButton(
                    stringResource(R.string.manage_dependency_add), { actions.onAddDependency(written) }, Modifier.align(Alignment.End),
                    enabled = !working && dependencyTarget(state.ref, written) != null,
                )
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
