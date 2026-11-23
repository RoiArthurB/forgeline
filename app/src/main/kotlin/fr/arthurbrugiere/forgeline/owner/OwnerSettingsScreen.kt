package fr.arthurbrugiere.forgeline.owner

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.data.user.MemberChange
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeType
import fr.arthurbrugiere.forgeline.core.model.OrgMember
import fr.arthurbrugiere.forgeline.core.model.OrgMembers
import fr.arthurbrugiere.forgeline.core.model.Owner
import fr.arthurbrugiere.forgeline.core.model.OwnerChoice
import fr.arthurbrugiere.forgeline.core.model.OwnerSettingsChange
import fr.arthurbrugiere.forgeline.core.model.OwnerStatus
import fr.arthurbrugiere.forgeline.core.model.OwnerSwitch
import fr.arthurbrugiere.forgeline.core.model.OwnerText
import fr.arthurbrugiere.forgeline.core.model.RepoDeletion
import fr.arthurbrugiere.forgeline.core.model.StatusDuration
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftCheckRow
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftChipTabs
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftChoicePill
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftChoiceRow
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftChoiceSheet
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftConfirmSheet
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftDangerButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftIconButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSheet
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSnackbarHost
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftStatusBarScrim
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTextField
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTonalButton
import fr.arthurbrugiere.forgeline.navigation.OwnerSettingsRoute
import fr.arthurbrugiere.forgeline.repo.Loadable
import fr.arthurbrugiere.forgeline.repo.settings.EditRow
import fr.arthurbrugiere.forgeline.repo.settings.LeaveRow
import fr.arthurbrugiere.forgeline.repo.settings.TextSheet
import fr.arthurbrugiere.forgeline.settings.SettingRow
import fr.arthurbrugiere.forgeline.settings.SwitchItem
import fr.arthurbrugiere.forgeline.ui.listBottomPadding
import fr.arthurbrugiere.forgeline.ui.message
import fr.arthurbrugiere.forgeline.ui.overBottomBarOrKeyboard
import fr.arthurbrugiere.forgeline.ui.rememberCustomTabOpener
import fr.arthurbrugiere.forgeline.ui.sideSafeArea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun OwnerSettingsRoute(
    route: OwnerSettingsRoute,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    /** The organisation has another login: its page is to be opened there instead. */
    onRenamed: (String) -> Unit = {},
    /** The organisation is deleted, and that was read: nothing of it is left to show. */
    onDeleted: () -> Unit = {},
) {
    val forge = ForgeInstance.of(route.host)
    val viewModel = hiltViewModel<OwnerSettingsViewModel, OwnerSettingsViewModel.Factory>(key = "owner-settings:${route.host}/${route.login}/${route.organization}") {
        it.create(forge, route.login, route.organization)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The system's photo picker: nothing to allow, and only the picture picked is ever read.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val image = withContext(Dispatchers.IO) { ProfilePicture.read(context, uri) }
                if (image == null) viewModel.pictureUnreadable() else viewModel.setAvatar(image)
            }
        }
    }
    OwnerSettingsScreen(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onChange = { viewModel.change(it) },
        onUndo = viewModel::undo,
        onAppliedShown = viewModel::appliedShown,
        onOpenUrl = rememberCustomTabOpener(),
        onSignIn = onSignIn,
        onRefusalShown = viewModel::refusalShown,
        onErrorShown = viewModel::errorShown,
        onLoadBlocked = viewModel::loadBlocked,
        onSetBlocked = viewModel::setBlocked,
        onLoadMembers = { viewModel.loadMembers() },
        onChangeMembers = viewModel::changeMembers,
        onPickPicture = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onRemovePicture = { viewModel.setAvatar(null) },
        onRename = viewModel::rename,
        onDelete = viewModel::delete,
        onRenamed = onRenamed,
        onDeleted = onDeleted,
    )
}

/** The groups the settings are read in, one at a time under the header; one with nothing in it isn't offered. */
internal enum class OwnerTab { ABOUT, OPTIONS, MEMBERS, BLOCKED, DANGER }

/** What is being written in a sheet. */
private sealed interface Editing {
    data class Text(val field: OwnerText) : Editing

    data object Links : Editing

    data class Choice(val field: OwnerChoice) : Editing

    data object Status : Editing

    data object Block : Editing

    data object Invite : Editing

    data object Picture : Editing

    data object Rename : Editing

    data object Delete : Editing

    data class Member(val member: OrgMember) : Editing
}

/**
 * What a person sets on their own profile, or an organisation's owners on it, as far as its forge's API reaches: what
 * they say about themselves first, then what they switch on and pick. Words are written in a sheet and saved from it;
 * a switch or a choice is the forge's as soon as it is made, and can be taken back a few seconds. The rest is one tap
 * away on the forge's website.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OwnerSettingsScreen(
    state: OwnerSettingsUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onChange: (OwnerSettingsChange) -> Unit,
    onOpenUrl: (String) -> Unit,
    onRefusalShown: () -> Unit,
    onErrorShown: () -> Unit,
    modifier: Modifier = Modifier,
    onUndo: () -> Unit = {},
    onAppliedShown: () -> Unit = {},
    onSignIn: () -> Unit = {},
    onLoadBlocked: () -> Unit = {},
    onSetBlocked: (login: String, blocked: Boolean) -> Unit = { _, _ -> },
    onLoadMembers: () -> Unit = {},
    onChangeMembers: (MemberChange) -> Unit = {},
    onPickPicture: () -> Unit = {},
    onRemovePicture: () -> Unit = {},
    onRename: (String) -> Unit = {},
    onDelete: () -> Unit = {},
    onRenamed: (String) -> Unit = {},
    onDeleted: () -> Unit = {},
) {
    val colors = Soft.colors
    val settings = state.settings
    val organization = state.owner is Owner.Organization
    val forgeName = state.forge.displayName
    val webUrl = ownerSettingsUrl(state.forge, state.owner)
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<Editing?>(null) }
    // A sheet closes on the change it asked for, not on one a switch asked for before it opened.
    var closing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.saved) {
        if (closing) editing = null
        closing = false
    }
    val save: (OwnerSettingsChange) -> Unit = {
        closing = true
        onChange(it)
    }
    val refreshFailed = stringResource(R.string.trending_refresh_failed)
    LaunchedEffect(state.error, settings != null) {
        if (state.error != null && settings != null) {
            snackbar.showSnackbar(refreshFailed)
            onErrorShown()
        }
    }
    val refusal = state.refused?.let { error ->
        if (error == ForgeError.Unauthorized) {
            stringResource(R.string.owner_settings_sign_in_again, forgeName)
        } else {
            stringResource(R.string.repo_settings_refused, (error as? ForgeError.Http)?.message ?: stringResource(error.message))
        }
    }
    LaunchedEffect(refusal, editing) {
        if (refusal != null && editing == null) {
            snackbar.showSnackbar(refusal)
            onRefusalShown()
        }
    }
    LaunchedEffect(state.renamedTo) { state.renamedTo?.let(onRenamed) }
    val pictureText = stringResource(R.string.owner_settings_picture_changed)
    LaunchedEffect(state.pictureChanged) { if (state.pictureChanged > 0) snackbar.showSnackbar(pictureText) }
    val savedText = stringResource(R.string.owner_settings_saved)
    val undoLabel = stringResource(R.string.inbox_undo)
    LaunchedEffect(state.applied?.id) {
        val applied = state.applied ?: return@LaunchedEffect
        if (applied.undo != null) {
            val result = withTimeoutOrNull(UNDO_MILLIS) { snackbar.showSnackbar(savedText, actionLabel = undoLabel, duration = SnackbarDuration.Indefinite) }
            if (result == SnackbarResult.ActionPerformed) onUndo() else onAppliedShown()
        } else {
            snackbar.showSnackbar(savedText)
            onAppliedShown()
        }
    }

    if (settings != null) {
        val dismiss = {
            editing = null
            onRefusalShown()
        }
        when (val sheet = editing) {
            is Editing.Text -> TextSheet(
                stringResource(sheet.field.label(organization)), settings.texts[sheet.field].orEmpty(), singleLine = !sheet.field.multiline,
                saving = state.saving != null, refusal = refusal, onSave = { save(OwnerSettingsChange.Text(sheet.field, it)) }, onDismiss = dismiss,
            )
            Editing.Links -> LinksSheet(
                settings.links.orEmpty(), settings.maxLinks, saving = state.saving != null, refusal = refusal,
                onSave = { save(OwnerSettingsChange.Links(it)) }, onDismiss = dismiss,
            )
            Editing.Rename -> TextSheet(
                stringResource(R.string.owner_settings_rename), state.login.substringAfterLast('/'), singleLine = true,
                saving = state.working, refusal = refusal, onSave = onRename, onDismiss = dismiss,
            )
            Editing.Delete -> {
                var typed by rememberSaveable { mutableStateOf("") }
                SoftConfirmSheet(
                    title = stringResource(R.string.owner_settings_delete),
                    body = stringResource(R.string.owner_settings_delete_body, state.login),
                    confirm = stringResource(if (state.working) R.string.manage_deleting else R.string.owner_settings_delete_confirm),
                    cancel = stringResource(R.string.cancel),
                    danger = true,
                    confirmEnabled = !state.working && typed.trim() == state.login,
                    onConfirm = onDelete,
                    onDismiss = dismiss,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // The name to write stays in sight above the field, which starts empty: a placeholder would look typed.
                        val fieldName = stringResource(R.string.repo_settings_delete_field, state.login)
                        Text(fieldName, style = Soft.type.label, color = colors.ink)
                        SoftTextField(typed, { typed = it }, placeholder = "", error = refusal, modifier = Modifier.semantics { contentDescription = fieldName })
                    }
                }
            }
            Editing.Picture -> SoftSheet(stringResource(R.string.owner_settings_picture), dismiss) {
                Text(stringResource(R.string.owner_settings_picture_summary), style = Soft.type.secondary, color = colors.inkMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.avatarRemovable) {
                        SoftTonalButton(
                            stringResource(R.string.owner_settings_picture_remove),
                            onClick = {
                                editing = null
                                onRemovePicture()
                            },
                            enabled = !state.pictureWorking,
                        )
                    }
                    SoftButton(
                        stringResource(R.string.owner_settings_picture_choose),
                        onClick = {
                            editing = null
                            onPickPicture()
                        },
                        enabled = !state.pictureWorking,
                    )
                }
            }
            Editing.Invite -> (state.members as? Loadable.Loaded)?.value?.let { members ->
                InviteSheet(members, state.forge.type, onInvite = { invitee, role ->
                    editing = null
                    onChangeMembers(MemberChange.Invite(invitee, role))
                }, onDismiss = dismiss)
            }
            is Editing.Member -> (state.members as? Loadable.Loaded)?.value?.let { members ->
                MemberSheet(
                    sheet.member, members, state.forge.type,
                    onRole = {
                        editing = null
                        onChangeMembers(MemberChange.Role(sheet.member.id, it))
                    },
                    onRemove = {
                        editing = null
                        onChangeMembers(MemberChange.Remove(sheet.member.id))
                    },
                    onDismiss = dismiss,
                )
            }
            Editing.Block -> TextSheet(
                stringResource(R.string.owner_settings_block), "", singleLine = true, saving = false, refusal = null,
                onSave = {
                    editing = null
                    onSetBlocked(it, true)
                },
                onDismiss = dismiss,
            )
            Editing.Status -> StatusSheet(settings.status ?: OwnerStatus(), saving = state.saving != null, refusal = refusal, onSave = save, onDismiss = dismiss)
            is Editing.Choice -> settings.choices[sheet.field]?.let { choice ->
                SoftChoiceSheet(
                    stringResource(sheet.field.label), choice.options.map { choiceLabel(sheet.field, it) }, choice.options.indexOf(choice.value),
                    onSelect = { onChange(OwnerSettingsChange.Choice(sheet.field, choice.options[it])) }, onDismiss = dismiss,
                )
            }
            null -> Unit
        }
    }

    val tabs = settings?.let {
        buildList {
            if (it.texts.isNotEmpty() || it.links != null || it.status != null || state.avatarSupported) add(OwnerTab.ABOUT)
            if (it.switches.isNotEmpty() || it.choices.isNotEmpty()) add(OwnerTab.OPTIONS)
            if (state.membersSupported) add(OwnerTab.MEMBERS)
            if (state.blocksSupported) add(OwnerTab.BLOCKED)
            if (state.renames || state.deletes) add(OwnerTab.DANGER)
        }
    }.orEmpty()
    var picked by rememberSaveable { mutableStateOf(OwnerTab.ABOUT) }
    val tab = picked.takeIf { it in tabs } ?: tabs.firstOrNull() ?: OwnerTab.ABOUT
    val listStates = remember { mutableMapOf<OwnerTab, LazyListState>() }
    val listState = listStates.getOrPut(tab) { LazyListState() }
    val none = stringResource(R.string.manage_none)
    LaunchedEffect(tab) {
        if (tab == OwnerTab.BLOCKED) onLoadBlocked()
        if (tab == OwnerTab.MEMBERS) onLoadMembers()
    }

    // The danger zone is read on the warm tint, as a repository's is.
    val page by animateColorAsState(if (tab == OwnerTab.DANGER) colors.fields[0] else colors.ground, label = "settings page")
    Box(modifier.fillMaxSize().background(page)) {
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = state.isRefreshing && settings != null,
            onRefresh = onRefresh,
            state = pullState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = state.isRefreshing && settings != null,
                    modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars),
                    containerColor = colors.raised,
                    color = colors.accent,
                )
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            key(tab) {
                LazyColumn(
                    state = listState,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    contentPadding = PaddingValues(bottom = listBottomPadding()),
                    modifier = Modifier.fillMaxSize().sideSafeArea(),
                ) {
                    item(key = "header") {
                        SoftHeader(
                            onBack = onBack,
                            backDescription = stringResource(R.string.navigate_up),
                            actions = {
                                SoftIconButton(onClick = { onOpenUrl(webUrl) }) {
                                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.repo_open_on_forge, forgeName), tint = Soft.colors.ink)
                                }
                            },
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(state.login, style = Soft.type.secondary, color = Soft.colors.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    stringResource(if (organization) R.string.owner_settings_organization_title else R.string.owner_settings_profile_title),
                                    style = Soft.type.detailTitle, color = Soft.colors.ink, modifier = Modifier.semantics { heading() },
                                )
                            }
                        }
                    }
                    when {
                        state.deleted != null -> item(key = "deleted") {
                            val scheduled = state.deleted == RepoDeletion.SCHEDULED
                            SoftNotice(
                                stringResource(if (scheduled) R.string.repo_settings_deleted_scheduled_title else R.string.repo_settings_deleted_title, state.login),
                                stringResource(if (scheduled) R.string.repo_settings_deleted_scheduled_body else R.string.owner_settings_deleted_body, forgeName),
                                action = stringResource(R.string.repo_settings_deleted_done),
                                onAction = onDeleted,
                            )
                        }
                        settings == null && state.error == ForgeError.Unauthorized -> item(key = "sign-in") {
                            SoftNotice(
                                stringResource(R.string.owner_settings_sign_in_title),
                                stringResource(R.string.owner_settings_sign_in_again, forgeName),
                                action = stringResource(R.string.sign_in),
                                onAction = onSignIn,
                            )
                        }
                        settings == null && state.error != null -> item(key = "error") {
                            SoftNotice(
                                stringResource(R.string.owner_settings_error_title),
                                stringResource(state.error.message),
                                action = stringResource(R.string.retry),
                                onAction = onRefresh,
                            )
                        }
                        settings == null -> item(key = "loading") { SoftLoadingRows(stringResource(R.string.owner_settings_loading), rows = 6, leadingDot = false) }
                        else -> {
                            if (tabs.size > 1) {
                                item(key = "tabs") {
                                    SoftChipTabs(
                                        options = tabs.map { stringResource(it.label(organization)) },
                                        selected = tabs.indexOf(tab),
                                        onSelect = { picked = tabs[it] },
                                    )
                                }
                            }
                            when (tab) {
                                OwnerTab.ABOUT -> item(key = "about") {
                                    Column(Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth()) {
                                        if (state.avatarSupported) {
                                            EditRow(
                                                stringResource(R.string.owner_settings_picture),
                                                stringResource(if (state.pictureWorking) R.string.owner_settings_picture_sending else R.string.owner_settings_picture_change),
                                            ) { if (!state.pictureWorking) editing = Editing.Picture }
                                        }
                                        settings.status?.let { status -> EditRow(stringResource(R.string.owner_settings_status), statusSummary(status) ?: none) { editing = Editing.Status } }
                                        settings.texts.forEach { (field, value) ->
                                            EditRow(stringResource(field.label(organization)), value.ifBlank { none }) { editing = Editing.Text(field) }
                                        }
                                        settings.links?.let { links ->
                                            val summary = if (links.isEmpty()) none else links.joinToString(", ") { it.removePrefix("https://").removePrefix("http://") }
                                            EditRow(stringResource(R.string.owner_settings_links), summary) { editing = Editing.Links }
                                        }
                                        Spacer(Modifier.size(12.dp))
                                        LeaveRow(stringResource(R.string.repo_settings_more, forgeName), stringResource(R.string.owner_settings_more_summary)) { onOpenUrl(webUrl) }
                                    }
                                }
                                OwnerTab.DANGER -> item(key = "danger") {
                                    Column(Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth()) {
                                        if (state.renames) {
                                            SettingRow(stringResource(R.string.owner_settings_rename), state.login, onClick = { if (!state.working) editing = Editing.Rename })
                                        }
                                        if (state.deletes) {
                                            // Deleting stands apart, below the rest, on the ink button of what can't be taken back.
                                            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                                Text(stringResource(R.string.owner_settings_delete_summary), style = Soft.type.secondary, color = colors.inkMuted)
                                                SoftDangerButton(stringResource(R.string.owner_settings_delete), onClick = { editing = Editing.Delete }, modifier = Modifier.fillMaxWidth())
                                            }
                                        }
                                    }
                                }
                                OwnerTab.MEMBERS -> item(key = "members") {
                                    Column(Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth()) {
                                        when (val members = state.members) {
                                            is Loadable.Loaded -> {
                                                val adds = state.forge.type == ForgeType.FORGEJO || state.forge.type == ForgeType.GITEA
                                                EditRow(
                                                    stringResource(if (adds) R.string.owner_settings_add_member else R.string.owner_settings_invite),
                                                    stringResource(if (members.value.invitesByEmail) R.string.owner_settings_invite_by_email else R.string.owner_settings_invite_by_username),
                                                ) { if (!state.membersWorking) editing = Editing.Invite }
                                                members.value.members.forEach { member ->
                                                    SettingRow(
                                                        fr.arthurbrugiere.forgeline.ui.handleOf(member.login),
                                                        member.role?.let { roleLabel(state.forge.type, it, members.value.roleNames) },
                                                        onClick = { if (!state.membersWorking) editing = Editing.Member(member) },
                                                    )
                                                }
                                                if (members.value.invitations.isNotEmpty()) {
                                                    Text(
                                                        stringResource(R.string.owner_settings_invited), style = Soft.type.section, color = colors.ink,
                                                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp).semantics { heading() },
                                                    )
                                                    members.value.invitations.forEach { invitation ->
                                                        SettingRow(
                                                            invitation.invitee, roleLabel(state.forge.type, invitation.role, members.value.roleNames),
                                                            trailing = {
                                                                SoftTonalButton(
                                                                    stringResource(R.string.owner_settings_cancel_invitation),
                                                                    onClick = { onChangeMembers(MemberChange.Cancel(invitation.id)) }, enabled = !state.membersWorking,
                                                                )
                                                            },
                                                        )
                                                    }
                                                }
                                            }
                                            is Loadable.Failed -> SoftNotice(
                                                stringResource(R.string.owner_settings_members_error), stringResource(members.error.message),
                                                action = stringResource(R.string.retry), onAction = onLoadMembers,
                                            )
                                            else -> SoftLoadingRows(stringResource(R.string.owner_settings_loading), rows = 4, leadingDot = false)
                                        }
                                    }
                                }
                                OwnerTab.BLOCKED -> item(key = "blocked") {
                                    Column(Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth()) {
                                        Text(
                                            stringResource(if (organization) R.string.owner_settings_blocked_organization_summary else R.string.owner_settings_blocked_summary),
                                            style = Soft.type.secondary, color = colors.inkMuted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                                        )
                                        EditRow(stringResource(R.string.owner_settings_block), stringResource(R.string.owner_settings_block_summary)) { editing = Editing.Block }
                                        when (val blocked = state.blocked) {
                                            is Loadable.Loaded -> if (blocked.value.isEmpty()) {
                                                Text(stringResource(R.string.owner_settings_blocked_none), style = Soft.type.secondary, color = colors.inkMuted, modifier = Modifier.padding(20.dp))
                                            } else {
                                                blocked.value.forEach { person ->
                                                    SettingRow(
                                                        fr.arthurbrugiere.forgeline.ui.handleOf(person.login), null,
                                                        trailing = { SoftTonalButton(stringResource(R.string.owner_settings_unblock), onClick = { onSetBlocked(person.login, false) }) },
                                                    )
                                                }
                                            }
                                            is Loadable.Failed -> SoftNotice(
                                                stringResource(R.string.owner_settings_blocked_error), stringResource(blocked.error.message),
                                                action = stringResource(R.string.retry), onAction = onLoadBlocked,
                                            )
                                            else -> SoftLoadingRows(stringResource(R.string.owner_settings_loading), rows = 3, leadingDot = false)
                                        }
                                    }
                                }
                                OwnerTab.OPTIONS -> item(key = "options") {
                                    Column(Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth()) {
                                        settings.choices.forEach { (field, choice) ->
                                            SettingRow(stringResource(field.label), choiceLabel(field, choice.value), onClick = { editing = Editing.Choice(field) })
                                        }
                                        settings.switches.forEach { (field, on) ->
                                            SwitchItem(
                                                title = stringResource(field.label),
                                                summary = field.summary(state.forge.type)?.let { stringResource(it) },
                                                checked = on,
                                                onCheckedChange = { onChange(OwnerSettingsChange.Switch(field, it)) },
                                            )
                                        }
                                        if (settings.texts.isEmpty() && settings.links == null) {
                                            Spacer(Modifier.size(12.dp))
                                            // A GitLab profile's words are its administrators' to change through the API: the website has them.
                                            LeaveRow(stringResource(R.string.repo_settings_more, forgeName), stringResource(R.string.owner_settings_more_summary)) { onOpenUrl(webUrl) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        val scrolled by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
        SoftStatusBarScrim(scrolled)
        SoftSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).overBottomBarOrKeyboard())
    }
}

/** How long a change can be taken back from the snackbar that says it, as long as the Inbox's. */
private const val UNDO_MILLIS = 5_000L

/** "☕ Writing · Busy": what someone says they are up to, as their profile shows it; null for no status. */
@Composable
private fun statusSummary(status: OwnerStatus): String? {
    if (status.isEmpty) return null
    val busy = stringResource(R.string.owner_settings_status_busy)
    return listOfNotNull(listOf(status.emoji, status.message).filter { it.isNotBlank() }.joinToString(" ").ifBlank { null }, busy.takeIf { status.busy }).joinToString(" · ")
}

/** A status written in a sheet: an emoji, a message, whether one is busy, and how long it shows. Saved, or cleared. */
@Composable
private fun StatusSheet(current: OwnerStatus, saving: Boolean, refusal: String?, onSave: (OwnerSettingsChange) -> Unit, onDismiss: () -> Unit) {
    var emoji by rememberSaveable { mutableStateOf(current.emoji) }
    var message by rememberSaveable { mutableStateOf(current.message) }
    var busy by rememberSaveable { mutableStateOf(current.busy) }
    // Never, then GitLab's own choices, which GitHub takes as a time.
    val durations = listOf<StatusDuration?>(null) + StatusDuration.entries
    var clearAfter by rememberSaveable { mutableStateOf(0) }
    SoftSheet(stringResource(R.string.owner_settings_status), onDismiss) {
        SoftTextField(emoji, { emoji = it }, placeholder = stringResource(R.string.owner_settings_status_emoji))
        SoftTextField(message, { message = it }, placeholder = stringResource(R.string.owner_settings_status_message), error = refusal)
        SoftCheckRow(stringResource(R.string.owner_settings_status_busy), busy, { busy = it })
        SoftChoicePill(
            name = stringResource(R.string.owner_settings_status_clears),
            options = durations.map { stringResource(it.label) },
            selected = clearAfter,
            onSelect = { clearAfter = it },
        )
        val wanted = OwnerStatus(emoji.trim(), message.trim(), busy)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!current.isEmpty) {
                SoftTonalButton(stringResource(R.string.owner_settings_status_clear), onClick = { onSave(OwnerSettingsChange.Status(OwnerStatus())) }, enabled = !saving)
            }
            SoftButton(
                stringResource(R.string.manage_save),
                onClick = { onSave(OwnerSettingsChange.Status(wanted, durations[clearAfter])) },
                enabled = !saving && !wanted.isEmpty && (wanted != current.copy(expiresAt = null) || clearAfter != 0),
            )
        }
    }
}

private val StatusDuration?.label: Int
    get() = when (this) {
        null -> R.string.owner_settings_status_never
        StatusDuration.MIN_30 -> R.string.owner_settings_status_30m
        StatusDuration.HOUR_3 -> R.string.owner_settings_status_3h
        StatusDuration.HOUR_8 -> R.string.owner_settings_status_8h
        StatusDuration.DAY_1 -> R.string.owner_settings_status_1d
        StatusDuration.DAY_3 -> R.string.owner_settings_status_3d
        StatusDuration.DAY_7 -> R.string.owner_settings_status_7d
        StatusDuration.DAY_30 -> R.string.owner_settings_status_30d
    }

/** The other places to find someone, each written out, as many as the forge keeps. */
@Composable
private fun LinksSheet(current: List<String>, max: Int, saving: Boolean, refusal: String?, onSave: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var links by rememberSaveable { mutableStateOf(current.ifEmpty { listOf("") }) }
    SoftSheet(stringResource(R.string.owner_settings_links), onDismiss) {
        Text(stringResource(R.string.owner_settings_links_summary, max), style = Soft.type.secondary, color = Soft.colors.inkMuted)
        links.forEachIndexed { index, link ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                SoftTextField(
                    link, { text -> links = links.toMutableList().also { it[index] = text } }, placeholder = stringResource(R.string.owner_settings_link_placeholder),
                    modifier = Modifier.weight(1f), error = refusal.takeIf { index == links.lastIndex },
                )
                SoftIconButton(onClick = { links = (links - links[index]).ifEmpty { listOf("") } }) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.owner_settings_link_remove), tint = Soft.colors.inkMuted)
                }
            }
        }
        if (links.size < max) SoftTonalButton(stringResource(R.string.owner_settings_link_add), onClick = { links = links + "" })
        val cleaned = links.map { it.trim() }.filter { it.isNotEmpty() }
        SoftButton(stringResource(R.string.manage_save), onClick = { onSave(cleaned) }, enabled = !saving && cleaned != current)
    }
}

/** The forge's own page for what is set here, and what isn't. */
internal fun ownerSettingsUrl(forge: ForgeInstance, owner: Owner): String = when (owner) {
    Owner.Me -> when (forge.type) {
        ForgeType.GITHUB -> "${forge.webUrl}/settings/profile"
        ForgeType.GITLAB -> "${forge.webUrl}/-/user_settings/profile"
        ForgeType.TANGLED -> "${forge.webUrl}/settings"
        ForgeType.SOURCEHUT -> "https://meta.sr.ht/profile"
        ForgeType.FORGEJO, ForgeType.GITEA -> "${forge.webUrl}/user/settings"
    }
    is Owner.Organization -> when (forge.type) {
        ForgeType.GITHUB -> "${forge.webUrl}/organizations/${owner.login}/settings/profile"
        ForgeType.GITLAB -> "${forge.webUrl}/groups/${owner.login}/-/edit"
        ForgeType.FORGEJO, ForgeType.GITEA -> "${forge.webUrl}/org/${owner.login}/settings"
        ForgeType.TANGLED, ForgeType.SOURCEHUT -> forge.webUrl
    }
}

internal fun OwnerTab.label(organization: Boolean): Int = when (this) {
    OwnerTab.ABOUT -> R.string.repo_settings_about
    OwnerTab.OPTIONS -> if (organization) R.string.owner_settings_rules else R.string.owner_settings_preferences
    OwnerTab.MEMBERS -> R.string.owner_settings_members
    OwnerTab.BLOCKED -> R.string.owner_settings_blocked
    OwnerTab.DANGER -> R.string.repo_settings_danger
}

/** A role as the app says it: GitHub's and GitLab's by their names, a Forgejo team by its own. */
@Composable
internal fun roleLabel(forge: ForgeType, role: String, names: Map<String, String>): String {
    names[role]?.let { return it }
    val res = when (forge) {
        ForgeType.GITLAB -> when (role) {
            "5" -> R.string.owner_settings_role_minimal
            "10" -> R.string.owner_settings_role_guest
            "15" -> R.string.owner_settings_role_planner
            "20" -> R.string.owner_settings_role_reporter
            "30" -> R.string.owner_settings_role_developer
            "40" -> R.string.owner_settings_role_maintainer
            "50" -> R.string.owner_settings_role_owner
            else -> null
        }
        else -> when (role) {
            "admin" -> R.string.owner_settings_role_owner
            "member", "direct_member" -> R.string.owner_settings_role_member
            "billing_manager" -> R.string.owner_settings_role_billing_manager
            else -> null
        }
    }
    return res?.let { stringResource(it) } ?: role
}

/** Someone asked to join, by login (or email where the forge takes one), as one of the roles they can be given. */
@Composable
private fun InviteSheet(members: OrgMembers, forge: ForgeType, onInvite: (invitee: String, role: String) -> Unit, onDismiss: () -> Unit) {
    var invitee by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf(members.inviteRoles.firstOrNull().orEmpty()) }
    val adds = forge == ForgeType.FORGEJO || forge == ForgeType.GITEA
    SoftSheet(stringResource(if (adds) R.string.owner_settings_add_member else R.string.owner_settings_invite), onDismiss) {
        SoftTextField(
            invitee, { invitee = it },
            placeholder = stringResource(if (members.invitesByEmail) R.string.owner_settings_invite_by_email else R.string.owner_settings_invite_by_username),
        )
        Column(Modifier.selectableGroup()) {
            members.inviteRoles.forEach { option ->
                SoftChoiceRow(roleLabel(forge, option, members.roleNames), selected = option == role, onSelect = { role = option })
            }
        }
        SoftButton(
            stringResource(if (adds) R.string.owner_settings_add else R.string.owner_settings_send_invitation),
            onClick = { onInvite(invitee.trim(), role) }, enabled = invitee.isNotBlank() && role.isNotEmpty(),
        )
    }
}

/** One member: the role they have, to change where the forge does it here, and removing them, asked first. */
@Composable
private fun MemberSheet(member: OrgMember, members: OrgMembers, forge: ForgeType, onRole: (String) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    val name = fr.arthurbrugiere.forgeline.ui.handleOf(member.login)
    if (confirming) {
        SoftConfirmSheet(
            title = stringResource(R.string.owner_settings_remove_title, name),
            body = stringResource(R.string.owner_settings_remove_body),
            confirm = stringResource(R.string.owner_settings_remove),
            cancel = stringResource(R.string.cancel),
            danger = true,
            onConfirm = onRemove,
            onDismiss = { confirming = false },
        )
        return
    }
    SoftSheet(name, onDismiss) {
        if (members.changesRoles) {
            Column(Modifier.selectableGroup()) {
                members.roles.forEach { option ->
                    SoftChoiceRow(roleLabel(forge, option, members.roleNames), selected = option == member.role, onSelect = { if (option != member.role) onRole(option) })
                }
            }
        } else {
            Text(stringResource(R.string.owner_settings_roles_by_team), style = Soft.type.secondary, color = Soft.colors.inkMuted)
        }
        SoftDangerButton(stringResource(R.string.owner_settings_remove), onClick = { confirming = true }, modifier = Modifier.fillMaxWidth())
    }
}

internal fun OwnerText.label(organization: Boolean): Int = when (this) {
    OwnerText.NAME -> if (organization) R.string.owner_settings_display_name else R.string.owner_settings_name
    OwnerText.BIO -> if (organization) R.string.repo_settings_description else R.string.owner_settings_bio
    OwnerText.PRONOUNS -> R.string.owner_settings_pronouns
    OwnerText.COMPANY -> R.string.owner_settings_company
    OwnerText.LOCATION -> R.string.owner_settings_location
    OwnerText.WEBSITE -> R.string.repo_settings_website
    OwnerText.TWITTER -> R.string.owner_settings_twitter
    OwnerText.EMAIL -> if (organization) R.string.owner_settings_email else R.string.owner_settings_public_email
    OwnerText.BILLING_EMAIL -> R.string.owner_settings_billing_email
}

internal val OwnerSwitch.label: Int
    get() = when (this) {
        OwnerSwitch.HIREABLE -> R.string.owner_settings_hireable
        OwnerSwitch.HIDE_EMAIL -> R.string.owner_settings_hide_email
        OwnerSwitch.HIDE_PRONOUNS -> R.string.owner_settings_hide_pronouns
        OwnerSwitch.HIDE_ACTIVITY -> R.string.owner_settings_hide_activity
        OwnerSwitch.MEMBERS_CREATE_PUBLIC_REPOS -> R.string.owner_settings_members_public_repos
        OwnerSwitch.MEMBERS_CREATE_PRIVATE_REPOS -> R.string.owner_settings_members_private_repos
        OwnerSwitch.MEMBERS_CREATE_PAGES -> R.string.owner_settings_members_pages
        OwnerSwitch.MEMBERS_FORK_PRIVATE_REPOS -> R.string.owner_settings_members_fork
        OwnerSwitch.COMMIT_SIGNOFF -> R.string.owner_settings_signoff
        OwnerSwitch.ORGANIZATION_PROJECTS -> R.string.owner_settings_organization_projects
        OwnerSwitch.REPOSITORY_PROJECTS -> R.string.owner_settings_repository_projects
        OwnerSwitch.REPO_ADMINS_CHANGE_TEAM_ACCESS -> R.string.owner_settings_team_access
        OwnerSwitch.REQUEST_ACCESS -> R.string.owner_settings_request_access
        OwnerSwitch.REQUIRE_TWO_FACTOR -> R.string.owner_settings_two_factor
        OwnerSwitch.SHARE_WITH_GROUP_LOCK -> R.string.owner_settings_share_lock
        OwnerSwitch.EMAILS -> R.string.owner_settings_emails
        OwnerSwitch.MENTIONS -> R.string.owner_settings_mentions
        OwnerSwitch.LFS -> R.string.owner_settings_lfs
        OwnerSwitch.DIFFS_FILE_BY_FILE -> R.string.owner_settings_file_by_file
        OwnerSwitch.SHOW_WHITESPACE -> R.string.owner_settings_whitespace
    }

/** A line under a switch whose name doesn't say it all. */
internal fun OwnerSwitch.summary(forge: ForgeType): Int? = when (this) {
    OwnerSwitch.HIREABLE -> R.string.owner_settings_hireable_summary
    OwnerSwitch.HIDE_ACTIVITY -> R.string.owner_settings_hide_activity_summary
    OwnerSwitch.COMMIT_SIGNOFF -> R.string.owner_settings_signoff_summary
    OwnerSwitch.REPO_ADMINS_CHANGE_TEAM_ACCESS -> R.string.owner_settings_team_access_summary
    OwnerSwitch.SHARE_WITH_GROUP_LOCK -> R.string.owner_settings_share_lock_summary
    OwnerSwitch.MENTIONS -> R.string.owner_settings_mentions_summary
    OwnerSwitch.DIFFS_FILE_BY_FILE, OwnerSwitch.SHOW_WHITESPACE -> if (forge == ForgeType.GITLAB) R.string.owner_settings_website_only else null
    else -> null
}

private val OwnerChoice.label: Int
    get() = when (this) {
        OwnerChoice.VISIBILITY -> R.string.repo_settings_visibility
        OwnerChoice.DEFAULT_REPO_PERMISSION -> R.string.owner_settings_default_permission
        OwnerChoice.PROJECT_CREATION -> R.string.owner_settings_project_creation
        OwnerChoice.SUBGROUP_CREATION -> R.string.owner_settings_subgroup_creation
        OwnerChoice.DIFF_STYLE -> R.string.owner_settings_diff_style
        OwnerChoice.FIRST_STAT -> R.string.owner_settings_first_stat
        OwnerChoice.SECOND_STAT -> R.string.owner_settings_second_stat
        OwnerChoice.INTERACTION_LIMIT -> R.string.owner_settings_interaction_limit
        OwnerChoice.INTERACTION_LIMIT_DURATION -> R.string.owner_settings_interaction_limit_duration
    }

/** An option as the app says it; one a later forge adds is shown as the forge names it. */
@Composable
internal fun choiceLabel(choice: OwnerChoice, value: String): String {
    val res = when (value) {
        "" -> if (choice == OwnerChoice.INTERACTION_LIMIT) R.string.owner_settings_limit_none else R.string.manage_none
        "existing_users" -> R.string.owner_settings_limit_existing_users
        "contributors_only" -> R.string.owner_settings_limit_contributors
        "collaborators_only" -> R.string.owner_settings_limit_collaborators
        "one_day" -> R.string.owner_settings_limit_1d
        "three_days" -> R.string.owner_settings_limit_3d
        "one_week" -> R.string.owner_settings_limit_1w
        "one_month" -> R.string.owner_settings_limit_1m
        "six_months" -> R.string.owner_settings_limit_6m
        "public" -> R.string.repo_visibility_public
        "limited" -> R.string.owner_settings_visibility_limited
        "internal" -> R.string.repo_visibility_internal
        "private" -> R.string.repo_visibility_private
        "none" -> if (choice == OwnerChoice.DEFAULT_REPO_PERMISSION) R.string.owner_settings_permission_none else R.string.manage_none
        "read" -> R.string.owner_settings_permission_read
        "write" -> R.string.owner_settings_permission_write
        "admin" -> R.string.owner_settings_permission_admin
        "developer" -> R.string.owner_settings_role_developers
        "maintainer" -> R.string.owner_settings_role_maintainers
        "owner" -> R.string.owner_settings_role_owners
        "noone" -> R.string.owner_settings_role_no_one
        "unified" -> R.string.owner_settings_diff_unified
        "split" -> R.string.owner_settings_diff_split
        "merged-pull-request-count" -> R.string.owner_settings_stat_merged_pulls
        "closed-pull-request-count" -> R.string.owner_settings_stat_closed_pulls
        "open-pull-request-count" -> R.string.owner_settings_stat_open_pulls
        "open-issue-count" -> R.string.owner_settings_stat_open_issues
        "closed-issue-count" -> R.string.owner_settings_stat_closed_issues
        "repository-count" -> R.string.owner_settings_stat_repositories
        "star-count" -> R.string.owner_settings_stat_stars
        else -> null
    }
    return res?.let { stringResource(it) } ?: value
}
