package fr.arthurbrugiere.forgeline.repo.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.RepoDeletion
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoVisibility
import fr.arthurbrugiere.forgeline.settings.SettingModifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.model.ForgeType
import fr.arthurbrugiere.forgeline.core.model.MergeMethod
import fr.arthurbrugiere.forgeline.core.model.MergeStyle
import fr.arthurbrugiere.forgeline.core.model.RepoSettings
import fr.arthurbrugiere.forgeline.core.model.RepoSettingsChange
import fr.arthurbrugiere.forgeline.core.model.RepoSwitch
import fr.arthurbrugiere.forgeline.core.model.normalizeTopic
import fr.arthurbrugiere.forgeline.core.model.settingsUrl
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSectionTitle
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftStatusBarScrim
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTextField
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTonalButton
import fr.arthurbrugiere.forgeline.core.ui.soft.softPressable
import fr.arthurbrugiere.forgeline.navigation.RepoSettingsRoute
import fr.arthurbrugiere.forgeline.repo.Loadable
import fr.arthurbrugiere.forgeline.settings.ChoiceItem
import fr.arthurbrugiere.forgeline.settings.SettingRow
import fr.arthurbrugiere.forgeline.settings.SwitchItem
import fr.arthurbrugiere.forgeline.ui.LocalBottomBarSpace
import fr.arthurbrugiere.forgeline.ui.listBottomPadding
import fr.arthurbrugiere.forgeline.ui.message
import fr.arthurbrugiere.forgeline.ui.rememberCustomTabOpener
import fr.arthurbrugiere.forgeline.ui.sideSafeArea

@Composable
fun RepoSettingsRoute(
    route: RepoSettingsRoute,
    onBack: () -> Unit,
    /** The repository has another name: its page is to be opened there instead. */
    onRenamed: (RepoId) -> Unit,
    /** The repository is deleted, and that was read: nothing of it is left to show. */
    onDeleted: () -> Unit,
) {
    val repo = route.repo
    val viewModel = hiltViewModel<RepoSettingsViewModel, RepoSettingsViewModel.Factory>(key = "settings:${repo.key}") { it.create(repo) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    RepoSettingsScreen(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onChange = viewModel::change,
        onRename = viewModel::rename,
        onDelete = viewModel::delete,
        onRenamed = onRenamed,
        onDeleted = onDeleted,
        onLoadBranches = viewModel::loadBranches,
        onOpenUrl = rememberCustomTabOpener(),
        onRefusalShown = viewModel::refusalShown,
        onErrorShown = viewModel::errorShown,
    )
}

/** What is being edited in a sheet. */
private enum class Editing { DESCRIPTION, WEBSITE, TOPICS, BRANCH, RENAME, DELETE }

/** What is asked to be confirmed in a dialog. */
private enum class Confirming { VISIBILITY, ARCHIVE }

/**
 * What a repository's owner sets on it: what it says about itself, what it has switched on, how it merges. A switch
 * or a choice is the forge's as soon as it is made here; words are written in a sheet and saved from it. What the
 * forge's API doesn't reach is one tap away on its website. What can't be taken back lightly (who sees it, archiving,
 * its name, deleting it) stands apart at the end, and each asks before it happens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoSettingsScreen(
    state: RepoSettingsUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onChange: (RepoSettingsChange) -> Unit,
    onLoadBranches: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onRename: (String) -> Unit = {},
    onDelete: () -> Unit = {},
    onRenamed: (RepoId) -> Unit = {},
    onDeleted: () -> Unit = {},
    onRefusalShown: () -> Unit,
    onErrorShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Soft.colors
    val settings = state.settings
    val forgeName = state.repo.forge.displayName
    val snackbar = remember { SnackbarHostState() }
    var editing by rememberSaveable { mutableStateOf<Editing?>(null) }
    // A sheet closes on the change it asked for, not on one a switch asked for before it opened.
    var closing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.saved) {
        if (closing) editing = null
        closing = false
    }
    val save: (RepoSettingsChange) -> Unit = {
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
    // A sheet says itself what the forge refused of it; a switch has only the screen to say so.
    val refusal = state.refused?.let { error ->
        // The forge's own words when it gave some: they say what to put right.
        stringResource(R.string.repo_settings_refused, (error as? ForgeError.Http)?.message ?: stringResource(error.message))
    }
    var confirming by rememberSaveable { mutableStateOf<Confirming?>(null) }
    LaunchedEffect(state.renamedTo) { state.renamedTo?.let(onRenamed) }
    LaunchedEffect(refusal, editing) {
        if (refusal != null && editing == null) {
            snackbar.showSnackbar(refusal)
            onRefusalShown()
        }
    }

    if (settings != null) {
        val dismiss = {
            editing = null
            onRefusalShown()
        }
        when (editing) {
            Editing.DESCRIPTION -> TextSheet(
                stringResource(R.string.repo_settings_description), settings.description, singleLine = false,
                saving = state.saving != null, refusal = refusal, onSave = { save(RepoSettingsChange.Description(it)) }, onDismiss = dismiss,
            )
            Editing.WEBSITE -> TextSheet(
                stringResource(R.string.repo_settings_website), settings.website, singleLine = true,
                saving = state.saving != null, refusal = refusal, onSave = { save(RepoSettingsChange.Website(it)) }, onDismiss = dismiss,
            )
            Editing.TOPICS -> TopicsSheet(settings.topics, state.saving != null, refusal, onSave = { save(RepoSettingsChange.Topics(it)) }, onDismiss = dismiss)
            Editing.BRANCH -> BranchSheet(
                settings.defaultBranch, state.branches, onRetry = onLoadBranches, onDismiss = dismiss,
                onSelect = {
                    editing = null
                    onChange(RepoSettingsChange.DefaultBranch(it))
                },
            )
            Editing.RENAME -> TextSheet(
                stringResource(R.string.repo_settings_rename), state.repo.name, singleLine = true,
                saving = state.working, refusal = refusal, onSave = onRename, onDismiss = dismiss,
            )
            Editing.DELETE -> DeleteSheet(
                state.repo, state.working,
                // GitHub wants a permission of its own to delete, which a sign-in older than it wasn't given.
                refusal = if (state.refused == ForgeError.Unauthorized) stringResource(R.string.repo_settings_delete_sign_in, forgeName) else refusal,
                onDelete = onDelete, onDismiss = dismiss,
            )
            null -> Unit
        }
        when (confirming) {
            Confirming.VISIBILITY -> VisibilityDialog(settings.visibility, settings.visibilities, onDismiss = { confirming = null }) {
                confirming = null
                onChange(RepoSettingsChange.Visibility(it))
            }
            Confirming.ARCHIVE -> ArchiveDialog(settings.archived, onDismiss = { confirming = null }) {
                confirming = null
                onChange(RepoSettingsChange.Archived(!settings.archived))
            }
            null -> Unit
        }
    }

    val none = stringResource(R.string.manage_none)
    val listState = rememberLazyListState()
    Box(modifier.fillMaxSize().background(colors.ground)) {
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
            LazyColumn(
                state = listState,
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(bottom = listBottomPadding()),
                modifier = Modifier.fillMaxSize().sideSafeArea(),
            ) {
                item(key = "header") {
                    SoftHeader(
                        tint = colors.surface,
                        onBack = onBack,
                        backDescription = stringResource(R.string.navigate_up),
                        actions = {
                            IconButton(onClick = { onOpenUrl(state.repo.settingsUrl) }) {
                                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.repo_open_on_forge, forgeName), tint = colors.ink)
                            }
                        },
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(state.repo.fullName, style = Soft.type.secondary, color = colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.repo_settings_title), style = Soft.type.title, color = colors.ink, modifier = Modifier.semantics { heading() })
                        }
                    }
                }
                when {
                    state.deleted != null -> item(key = "deleted") {
                        val scheduled = state.deleted == RepoDeletion.SCHEDULED
                        SoftNotice(
                            stringResource(if (scheduled) R.string.repo_settings_deleted_scheduled_title else R.string.repo_settings_deleted_title, state.repo.fullName),
                            stringResource(if (scheduled) R.string.repo_settings_deleted_scheduled_body else R.string.repo_settings_deleted_body, forgeName),
                            action = stringResource(R.string.repo_settings_deleted_done),
                            onAction = onDeleted,
                        )
                    }
                    settings == null && state.error != null -> item(key = "error") {
                        SoftNotice(
                            stringResource(R.string.repo_settings_error_title),
                            stringResource(state.error.message),
                            action = stringResource(R.string.retry),
                            onAction = onRefresh,
                        )
                    }
                    settings == null -> item(key = "loading") { SoftLoadingRows(stringResource(R.string.repo_settings_loading), rows = 6, leadingDot = false) }
                    else -> {
                        item(key = "about") {
                            Column {
                                SoftSectionTitle(stringResource(R.string.repo_settings_about))
                                SettingRow(stringResource(R.string.repo_settings_description), settings.description.ifBlank { none }, onClick = { editing = Editing.DESCRIPTION })
                                if (settings.hasWebsite) {
                                    SettingRow(stringResource(R.string.repo_settings_website), settings.website.ifBlank { none }, onClick = { editing = Editing.WEBSITE })
                                }
                                SettingRow(stringResource(R.string.repo_settings_topics), settings.topics.joinToString(", ").ifEmpty { none }, onClick = { editing = Editing.TOPICS })
                                SettingRow(
                                    stringResource(R.string.repo_settings_default_branch), settings.defaultBranch.ifEmpty { none },
                                    onClick = {
                                        onLoadBranches()
                                        editing = Editing.BRANCH
                                    },
                                )
                            }
                        }
                        val features = settings.switches.filterKeys { !it.merging }
                        if (features.isNotEmpty()) {
                            item(key = "features") {
                                Column {
                                    SoftSectionTitle(stringResource(R.string.repo_settings_features))
                                    features.forEach { (switch, on) -> SwitchRow(switch, on, settings, state.repo.forge.type, onChange) }
                                }
                            }
                        }
                        val merging = settings.switches.filterKeys { it.merging }
                        if (merging.isNotEmpty() || settings.mergeStyle != null || settings.defaultMerge != null) {
                            item(key = "merging") {
                                Column {
                                    SoftSectionTitle(stringResource(R.string.repo_settings_merging))
                                    settings.mergeStyle?.let { style ->
                                        ChoiceItem(
                                            stringResource(R.string.repo_settings_merge_style),
                                            MergeStyle.entries.map { stringResource(it.label) },
                                            style.ordinal,
                                        ) { onChange(RepoSettingsChange.Style(MergeStyle.entries[it])) }
                                    }
                                    merging.forEach { (switch, on) -> SwitchRow(switch, on, settings, state.repo.forge.type, onChange) }
                                    settings.defaultMerge?.let { method ->
                                        ChoiceItem(
                                            stringResource(R.string.repo_settings_default_merge),
                                            MergeMethod.entries.map { stringResource(it.label) },
                                            method.ordinal,
                                        ) { onChange(RepoSettingsChange.DefaultMerge(MergeMethod.entries[it])) }
                                    }
                                }
                            }
                        }
                        item(key = "danger") {
                            Column(SettingModifier.padding(top = 20.dp).clip(RoundedCornerShape(28.dp)).background(colors.fields[0]).padding(vertical = 8.dp)) {
                                Text(
                                    stringResource(R.string.repo_settings_danger),
                                    style = Soft.type.label, color = colors.ink,
                                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
                                )
                                SettingRow(stringResource(R.string.repo_settings_visibility), stringResource(settings.visibility.label), onClick = { confirming = Confirming.VISIBILITY })
                                SettingRow(
                                    stringResource(if (settings.archived) R.string.repo_settings_unarchive else R.string.repo_settings_archive),
                                    stringResource(if (settings.archived) R.string.repo_settings_archived_summary else R.string.repo_settings_archive_summary),
                                    onClick = { confirming = Confirming.ARCHIVE },
                                )
                                SettingRow(stringResource(R.string.repo_settings_rename), state.repo.name, onClick = { editing = Editing.RENAME })
                                SettingRow(stringResource(R.string.repo_settings_delete), stringResource(R.string.repo_settings_delete_summary), onClick = { editing = Editing.DELETE })
                            }
                        }
                        item(key = "more") {
                            Column {
                                Spacer(Modifier.size(12.dp))
                                SettingRow(
                                    stringResource(R.string.repo_settings_more, forgeName),
                                    stringResource(R.string.repo_settings_more_summary),
                                    onClick = { onOpenUrl(state.repo.settingsUrl) },
                                    trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(18.dp)) },
                                )
                            }
                        }
                    }
                }
            }
        }
        val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
        SoftStatusBarScrim(scrolled)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalBottomBarSpace.current)) { data ->
            Snackbar(data, shape = RoundedCornerShape(16.dp), containerColor = colors.ink, contentColor = colors.ground)
        }
    }
}

@Composable
private fun SwitchRow(switch: RepoSwitch, on: Boolean, settings: RepoSettings, forge: ForgeType, onChange: (RepoSettingsChange) -> Unit) {
    SwitchItem(
        title = stringResource(switch.label(forge)),
        summary = if (on && switch in settings.membersOnly) stringResource(R.string.repo_settings_members_only) else null,
        checked = on,
        onCheckedChange = { onChange(RepoSettingsChange.Switch(switch, it)) },
    )
}

/** What a switch is called, in its forge's own words where they differ. */
internal fun RepoSwitch.label(forge: ForgeType): Int = when (this) {
    RepoSwitch.ISSUES -> R.string.repo_switch_issues
    RepoSwitch.PULL_REQUESTS -> if (forge == ForgeType.GITLAB) R.string.repo_switch_merge_requests else R.string.repo_switch_pull_requests
    RepoSwitch.WIKI -> R.string.repo_switch_wiki
    RepoSwitch.DISCUSSIONS -> R.string.repo_switch_discussions
    RepoSwitch.PROJECTS -> R.string.repo_switch_projects
    RepoSwitch.CI -> if (forge == ForgeType.GITLAB) R.string.repo_switch_ci_pipelines else R.string.repo_switch_ci_actions
    RepoSwitch.RELEASES -> R.string.repo_switch_releases
    RepoSwitch.PACKAGES -> R.string.repo_switch_packages
    RepoSwitch.TEMPLATE -> R.string.repo_switch_template
    RepoSwitch.MERGE_COMMIT -> R.string.repo_switch_merge_commit
    RepoSwitch.SQUASH_MERGE -> R.string.repo_switch_squash_merge
    RepoSwitch.REBASE_MERGE -> R.string.repo_switch_rebase_merge
    RepoSwitch.FAST_FORWARD_MERGE -> R.string.repo_switch_fast_forward_merge
    RepoSwitch.AUTO_MERGE -> R.string.repo_switch_auto_merge
    RepoSwitch.DELETE_BRANCH_ON_MERGE -> R.string.repo_switch_delete_branch
    RepoSwitch.REQUIRE_PIPELINE -> R.string.repo_switch_require_pipeline
    RepoSwitch.REQUIRE_RESOLVED_THREADS -> R.string.repo_switch_require_threads
}

private val MergeStyle.label: Int
    get() = when (this) {
        MergeStyle.MERGE_COMMIT -> R.string.repo_settings_merge_style_merge
        MergeStyle.SEMI_LINEAR -> R.string.repo_settings_merge_style_semi_linear
        MergeStyle.FAST_FORWARD -> R.string.repo_settings_merge_style_fast_forward
    }

private val MergeMethod.label: Int
    get() = when (this) {
        MergeMethod.MERGE -> R.string.merge_method_merge
        MergeMethod.SQUASH -> R.string.merge_method_squash
        MergeMethod.REBASE -> R.string.merge_method_rebase
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val colors = Soft.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.ground,
        contentColor = colors.ink,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().imePadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = Soft.type.name, color = colors.ink, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

/** Words to write and save; what the forge refuses of them is said here, and they stay to be put right. */
@Composable
private fun TextSheet(title: String, current: String, singleLine: Boolean, saving: Boolean, refusal: String?, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    Sheet(title, onDismiss) {
        SoftTextField(text, { text = it }, placeholder = title, error = refusal, singleLine = singleLine, minLines = if (singleLine) 1 else 3, maxLines = if (singleLine) 1 else 8)
        SoftButton(stringResource(R.string.manage_save), onClick = { onSave(text.trim()) }, enabled = !saving && text.trim() != current)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopicsSheet(current: List<String>, saving: Boolean, refusal: String?, onSave: (List<String>) -> Unit, onDismiss: () -> Unit) {
    val colors = Soft.colors
    var topics by rememberSaveable { mutableStateOf(current) }
    var text by rememberSaveable { mutableStateOf("") }
    val add = {
        normalizeTopic(text)?.let { if (it !in topics) topics = topics + it }
        text = ""
    }
    Sheet(stringResource(R.string.repo_settings_topics), onDismiss) {
        if (topics.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                topics.forEach { topic ->
                    Row(
                        Modifier
                            .clip(SoftTokens.Pill)
                            .background(colors.surface)
                            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.repo_settings_topic_remove, topic)) { topics = topics - topic }
                            .heightIn(min = 48.dp)
                            .padding(start = 16.dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(topic, style = Soft.type.control, color = colors.ink)
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.repo_settings_topic_remove, topic), tint = colors.inkMuted, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftTextField(
                text, { text = it }, placeholder = stringResource(R.string.repo_settings_topic_add), error = refusal, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }),
            )
            SoftTonalButton(stringResource(R.string.repo_settings_topic_add_action), onClick = add, enabled = normalizeTopic(text) != null)
        }
        SoftButton(stringResource(R.string.manage_save), onClick = { onSave(topics) }, enabled = !saving && topics != current)
    }
}

/** The branches, the default one checked; picking another makes it the default at once. */
@Composable
private fun BranchSheet(current: String, branches: Loadable<List<String>>, onSelect: (String) -> Unit, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val colors = Soft.colors
    Sheet(stringResource(R.string.repo_settings_default_branch), onDismiss) {
        when (branches) {
            is Loadable.Failed -> SoftNotice(stringResource(R.string.repo_settings_branches_error), stringResource(branches.error.message), action = stringResource(R.string.retry), onAction = onRetry)
            is Loadable.Loaded -> Column {
                branches.value.forEach { branch ->
                    Row(
                        Modifier.fillMaxWidth().softPressable { onSelect(branch) }.heightIn(min = 52.dp).padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                            if (branch == current) Icon(Icons.Outlined.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(branch, style = Soft.type.body, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            else -> SoftLoadingRows(stringResource(R.string.repo_settings_branches_loading), rows = 3, leadingDot = false)
        }
    }
}

internal val RepoVisibility.label: Int
    get() = when (this) {
        RepoVisibility.PUBLIC -> R.string.repo_visibility_public
        RepoVisibility.INTERNAL -> R.string.repo_visibility_internal
        RepoVisibility.PRIVATE -> R.string.repo_visibility_private
    }

private val RepoVisibility.summary: Int
    get() = when (this) {
        RepoVisibility.PUBLIC -> R.string.repo_visibility_public_summary
        RepoVisibility.INTERNAL -> R.string.repo_visibility_internal_summary
        RepoVisibility.PRIVATE -> R.string.repo_visibility_private_summary
    }

/** Who may see the repository, picked among what its forge offers; nothing changes before it is confirmed. */
@Composable
private fun VisibilityDialog(current: RepoVisibility, offered: List<RepoVisibility>, onDismiss: () -> Unit, onConfirm: (RepoVisibility) -> Unit) {
    var picked by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.repo_settings_visibility)) },
        text = {
            Column(Modifier.selectableGroup()) {
                offered.forEach { option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(SoftTokens.RowCorner)
                            .selectable(selected = option == picked, role = Role.RadioButton) { picked = option }
                            .heightIn(min = 48.dp)
                            .padding(vertical = 8.dp),
                    ) {
                        RadioButton(selected = option == picked, onClick = null)
                        Column(Modifier.padding(start = 16.dp)) {
                            Text(stringResource(option.label), style = Soft.type.body)
                            Text(stringResource(option.summary), style = Soft.type.secondary, color = Soft.colors.inkMuted)
                        }
                    }
                }
                Text(stringResource(R.string.repo_settings_visibility_warning), style = Soft.type.secondary, modifier = Modifier.padding(top = 12.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(picked) }, enabled = picked != current) { Text(stringResource(R.string.repo_settings_visibility_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        shape = RoundedCornerShape(28.dp),
        containerColor = Soft.colors.raised,
    )
}

@Composable
private fun ArchiveDialog(archived: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (archived) R.string.repo_settings_unarchive_title else R.string.repo_settings_archive_title)) },
        text = { Text(stringResource(if (archived) R.string.repo_settings_unarchive_body else R.string.repo_settings_archive_body)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(if (archived) R.string.repo_settings_unarchive else R.string.repo_settings_archive)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        shape = RoundedCornerShape(28.dp),
        containerColor = Soft.colors.raised,
    )
}

/** Deleting asks for the repository's full name, written out: a tap alone can't do it. */
@Composable
private fun DeleteSheet(repo: RepoId, working: Boolean, refusal: String?, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var typed by rememberSaveable { mutableStateOf("") }
    Sheet(stringResource(R.string.repo_settings_delete), onDismiss) {
        Text(stringResource(R.string.repo_settings_delete_body, repo.fullName), style = Soft.type.secondary, color = Soft.colors.ink)
        SoftTextField(typed, { typed = it }, placeholder = repo.fullName, error = refusal)
        SoftButton(stringResource(R.string.repo_settings_delete_confirm), onClick = onDelete, enabled = !working && typed.trim() == repo.fullName)
    }
}
