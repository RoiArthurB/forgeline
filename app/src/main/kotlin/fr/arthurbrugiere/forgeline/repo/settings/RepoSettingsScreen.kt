package fr.arthurbrugiere.forgeline.repo.settings

import fr.arthurbrugiere.forgeline.core.ui.soft.SoftChoiceSheet
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSheet
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSnackbarHost
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftIconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.foundation.selection.selectableGroup
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.PagesBuild
import fr.arthurbrugiere.forgeline.core.model.PagesChange
import fr.arthurbrugiere.forgeline.core.model.PagesSite
import fr.arthurbrugiere.forgeline.core.model.RepoDeletion
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoVisibility
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.key
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.withTimeoutOrNull
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftChipTabs
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftDangerButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftConfirmSheet
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftChoiceRow
import androidx.compose.ui.semantics.contentDescription
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftStatusBarScrim
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTextField
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTonalButton
import fr.arthurbrugiere.forgeline.core.ui.soft.softPressable
import fr.arthurbrugiere.forgeline.navigation.RepoSettingsRoute
import fr.arthurbrugiere.forgeline.repo.Loadable
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
    /** Opens signing in, for a sign-in that must be made again before it may delete. */
    onSignIn: () -> Unit,
) {
    val repo = route.repo
    val viewModel = hiltViewModel<RepoSettingsViewModel, RepoSettingsViewModel.Factory>(key = "settings:${repo.key}") { it.create(repo) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    RepoSettingsScreen(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onChange = { viewModel.change(it) },
        onChangePages = { viewModel.changePages(it) },
        onUndo = viewModel::undo,
        onAppliedShown = viewModel::appliedShown,
        onRename = viewModel::rename,
        onDelete = viewModel::delete,
        onCheckDelete = viewModel::checkDelete,
        onSignIn = onSignIn,
        onRenamed = onRenamed,
        onDeleted = onDeleted,
        onLoadBranches = viewModel::loadBranches,
        onOpenUrl = rememberCustomTabOpener(),
        onRefusalShown = viewModel::refusalShown,
        onErrorShown = viewModel::errorShown,
    )
}

/** What is being edited in a sheet. */
private enum class Editing { DESCRIPTION, WEBSITE, TOPICS, BRANCH, RENAME, DELETE, PAGES_SOURCE, PAGES_DOMAIN, MERGE_STYLE, DEFAULT_MERGE }

/**
 * The groups the settings are read in, one at a time under the header. What can't be taken back comes last, and is
 * never the one that opens first.
 */
internal enum class SettingsTab(val label: Int) {
    ABOUT(R.string.repo_settings_about),
    FEATURES(R.string.repo_settings_features),
    MERGING(R.string.repo_settings_merging),
    PAGES(R.string.repo_pages),
    DANGER(R.string.repo_settings_danger),
}

/** What is asked to be confirmed in a dialog. */
private enum class Confirming { VISIBILITY, ARCHIVE, UNPUBLISH }

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
    onChangePages: (PagesChange) -> Unit = {},
    /** Takes the change just said back. */
    onUndo: () -> Unit = {},
    onAppliedShown: () -> Unit = {},
    onRename: (String) -> Unit = {},
    onDelete: () -> Unit = {},
    /** Asks whether this sign-in may delete at all, as the sheet for it opens. */
    onCheckDelete: () -> Unit = {},
    onSignIn: () -> Unit = {},
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
            Editing.PAGES_SOURCE -> PagesSourceSheet(
                state.pages, settings.defaultBranch, state.branches, state.working, refusal,
                onSave = {
                    closing = true
                    onChangePages(PagesChange.Publish(it))
                },
                onDismiss = dismiss,
            )
            Editing.PAGES_DOMAIN -> TextSheet(
                stringResource(R.string.repo_pages_domain), state.pages?.customDomain.orEmpty(), singleLine = true, saving = state.working, refusal = refusal,
                onSave = {
                    closing = true
                    onChangePages(PagesChange.Domain(it))
                },
                onDismiss = dismiss,
            )
            Editing.MERGE_STYLE -> settings.mergeStyle?.let { current ->
                SoftChoiceSheet(
                    stringResource(R.string.repo_settings_merge_style), MergeStyle.entries.map { stringResource(it.label) }, current.ordinal,
                    onSelect = { onChange(RepoSettingsChange.Style(MergeStyle.entries[it])) }, onDismiss = dismiss,
                )
            }
            Editing.DEFAULT_MERGE -> settings.defaultMerge?.let { current ->
                SoftChoiceSheet(
                    stringResource(R.string.repo_settings_default_merge), MergeMethod.entries.map { stringResource(it.label) }, current.ordinal,
                    onSelect = { onChange(RepoSettingsChange.DefaultMerge(MergeMethod.entries[it])) }, onDismiss = dismiss,
                )
            }
            Editing.DELETE -> DeleteSheet(
                state.repo, state.working, state.mayDelete,
                // Signed in again since the sheet opened and still refused: said in the forge's place.
                refusal = if (state.refused == ForgeError.Unauthorized) stringResource(R.string.repo_settings_delete_sign_in, forgeName) else refusal,
                onDelete = onDelete,
                onSignIn = {
                    dismiss()
                    onSignIn()
                },
                onDismiss = dismiss,
            )
            null -> Unit
        }
        when (confirming) {
            Confirming.VISIBILITY -> VisibilitySheet(settings.visibility, settings.visibilities, onDismiss = { confirming = null }) {
                confirming = null
                onChange(RepoSettingsChange.Visibility(it))
            }
            // The website goes down for whoever follows a link to it: the ink pill.
            Confirming.UNPUBLISH -> SoftConfirmSheet(
                title = stringResource(R.string.repo_pages_unpublish_title),
                body = stringResource(R.string.repo_pages_unpublish_body),
                confirm = stringResource(R.string.repo_pages_unpublish),
                cancel = stringResource(R.string.cancel),
                danger = true,
                onConfirm = {
                    confirming = null
                    onChangePages(PagesChange.Unpublish)
                },
                onDismiss = { confirming = null },
            )
            // Archiving is undone by unarchiving: asked about, and no more than that.
            Confirming.ARCHIVE -> SoftConfirmSheet(
                title = stringResource(if (settings.archived) R.string.repo_settings_unarchive_title else R.string.repo_settings_archive_title),
                body = stringResource(if (settings.archived) R.string.repo_settings_unarchive_body else R.string.repo_settings_archive_body),
                confirm = stringResource(if (settings.archived) R.string.repo_settings_unarchive else R.string.repo_settings_archive),
                cancel = stringResource(R.string.cancel),
                onConfirm = {
                    confirming = null
                    onChange(RepoSettingsChange.Archived(!settings.archived))
                },
                onDismiss = { confirming = null },
            )
            null -> Unit
        }
    }

    val none = stringResource(R.string.manage_none)
    val forgeType = state.repo.forge.type
    val site = state.pages
    // A site its forge publishes by itself has nothing to set until it is there.
    val hasPages = site != null && (site.published || site.canPublish)
    val tabs = settings?.let {
        buildList {
            add(SettingsTab.ABOUT)
            if (it.switches.keys.any { switch -> !switch.merging }) add(SettingsTab.FEATURES)
            if (it.switches.keys.any { switch -> switch.merging } || it.mergeStyle != null || it.defaultMerge != null) add(SettingsTab.MERGING)
            if (hasPages) add(SettingsTab.PAGES)
            add(SettingsTab.DANGER)
        }
    }.orEmpty()
    var picked by rememberSaveable { mutableStateOf(SettingsTab.ABOUT) }
    val tab = picked.takeIf { it in tabs } ?: SettingsTab.ABOUT
    // Each group keeps where it was read to, the way back from another.
    val listStates = remember { mutableMapOf<SettingsTab, LazyListState>() }
    val listState = listStates.getOrPut(tab) { LazyListState() }

    // What the forge took is said once; a switch or a choice can be taken back for a few seconds.
    val appliedText = state.applied?.let { appliedText(it, settings, forgeType) }
    val undoLabel = stringResource(R.string.inbox_undo)
    LaunchedEffect(state.applied?.id) {
        val applied = state.applied ?: return@LaunchedEffect
        if (appliedText == null) return@LaunchedEffect onAppliedShown()
        if (applied.undo != null || applied.pagesUndo != null) {
            val result = withTimeoutOrNull(UNDO_MILLIS) { snackbar.showSnackbar(appliedText, actionLabel = undoLabel, duration = SnackbarDuration.Indefinite) }
            if (result == SnackbarResult.ActionPerformed) onUndo() else onAppliedShown()
        } else {
            snackbar.showSnackbar(appliedText)
            onAppliedShown()
        }
    }

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
            key(tab) {
                LazyColumn(
                    state = listState,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    contentPadding = PaddingValues(bottom = listBottomPadding()),
                    modifier = Modifier.fillMaxSize().sideSafeArea(),
                ) {
                    item(key = "header") {
                        // The repository's own warm field: these are its settings, and the page under it is its page.
                        SoftHeader(
                            tint = colors.fields[0],
                            onBack = onBack,
                            backDescription = stringResource(R.string.navigate_up),
                            actions = {
                                SoftIconButton(onClick = { onOpenUrl(state.repo.settingsUrl) }) {
                                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.repo_open_on_forge, forgeName), tint = colors.ink)
                                }
                            },
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(state.repo.fullName, style = Soft.type.secondary, color = colors.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(stringResource(R.string.settings_title), style = Soft.type.detailTitle, color = colors.ink, modifier = Modifier.semantics { heading() })
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
                            item(key = "tabs") {
                                SoftChipTabs(
                                    options = tabs.map { stringResource(it.label) },
                                    selected = tabs.indexOf(tab),
                                    onSelect = { picked = tabs[it] },
                                    modifier = Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                                )
                            }
                            when (tab) {
                                SettingsTab.ABOUT -> item(key = "about") {
                                    Column {
                                        EditRow(stringResource(R.string.repo_settings_description), settings.description.ifBlank { none }) { editing = Editing.DESCRIPTION }
                                        if (settings.hasWebsite) {
                                            EditRow(stringResource(R.string.repo_settings_website), settings.website.ifBlank { none }) { editing = Editing.WEBSITE }
                                        }
                                        EditRow(stringResource(R.string.repo_settings_topics), topicsSummary(settings.topics) ?: none) { editing = Editing.TOPICS }
                                        EditRow(stringResource(R.string.repo_settings_default_branch), settings.defaultBranch.ifEmpty { none }) {
                                            onLoadBranches()
                                            editing = Editing.BRANCH
                                        }
                                        // Where to look when the app doesn't have the setting.
                                        Spacer(Modifier.size(12.dp))
                                        LeaveRow(stringResource(R.string.repo_settings_more, forgeName), stringResource(R.string.repo_settings_more_summary)) { onOpenUrl(state.repo.settingsUrl) }
                                    }
                                }
                                SettingsTab.FEATURES -> item(key = "features") {
                                    Column {
                                        settings.switches.filterKeys { !it.merging }.forEach { (switch, on) -> SwitchRow(switch, on, settings, forgeType, onChange) }
                                    }
                                }
                                SettingsTab.MERGING -> item(key = "merging") {
                                    Column {
                                        settings.mergeStyle?.let { style ->
                                            EditRow(stringResource(R.string.repo_settings_merge_style), stringResource(style.label)) { editing = Editing.MERGE_STYLE }
                                        }
                                        settings.switches.filterKeys { it.merging }.forEach { (switch, on) -> SwitchRow(switch, on, settings, forgeType, onChange) }
                                        settings.defaultMerge?.let { method ->
                                            EditRow(stringResource(R.string.repo_settings_default_merge), stringResource(method.label)) { editing = Editing.DEFAULT_MERGE }
                                        }
                                    }
                                }
                                SettingsTab.PAGES -> item(key = "pages") {
                                    Column {
                                        val openSource = {
                                            onLoadBranches()
                                            editing = Editing.PAGES_SOURCE
                                        }
                                        if (site == null || !site.published) {
                                            EditRow(stringResource(R.string.repo_pages_publish), stringResource(R.string.repo_pages_not_published), onClick = openSource)
                                        } else {
                                            site.url?.let { url -> LeaveRow(stringResource(R.string.repo_pages_address), url) { onOpenUrl(url) } }
                                            site.build?.let { build -> EditRow(stringResource(R.string.repo_pages_source), build.label(), onClick = openSource) }
                                            site.httpsEnforced?.let { enforced ->
                                                SwitchItem(stringResource(R.string.repo_pages_https), null, enforced, onCheckedChange = { onChangePages(PagesChange.Https(it)) })
                                            }
                                            site.customDomain?.let { domain ->
                                                EditRow(stringResource(R.string.repo_pages_domain), domain.ifBlank { none }) { editing = Editing.PAGES_DOMAIN }
                                            }
                                            // Asks first, from its own sheet: no glyph promises more than that.
                                            SettingRow(stringResource(R.string.repo_pages_unpublish), stringResource(R.string.repo_pages_unpublish_summary), onClick = { confirming = Confirming.UNPUBLISH })
                                        }
                                    }
                                }
                                SettingsTab.DANGER -> item(key = "danger") {
                                    Column(Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth()) {
                                        // Each asks before it happens; none of them is one more tap through.
                                        SettingRow(stringResource(R.string.repo_settings_visibility), stringResource(settings.visibility.label), onClick = { confirming = Confirming.VISIBILITY })
                                        SettingRow(
                                            stringResource(if (settings.archived) R.string.repo_settings_unarchive else R.string.repo_settings_archive),
                                            stringResource(if (settings.archived) R.string.repo_settings_archived_summary else R.string.repo_settings_archive_summary),
                                            onClick = { confirming = Confirming.ARCHIVE },
                                        )
                                        SettingRow(stringResource(R.string.repo_settings_rename), state.repo.name, onClick = { editing = Editing.RENAME })
                                        // Deleting stands apart, below the rest, on the ink pill of what can't be taken back.
                                        Column(
                                            Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp),
                                            verticalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            Text(stringResource(R.string.repo_settings_delete_summary), style = Soft.type.secondary, color = colors.inkMuted)
                                            SoftDangerButton(
                                                stringResource(R.string.repo_settings_delete),
                                                onClick = {
                                                    onCheckDelete()
                                                    editing = Editing.DELETE
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                            )
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
        SoftSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalBottomBarSpace.current))
    }
}

/** How long a change can be taken back from the snackbar that says it, as long as the Inbox's. */
private const val UNDO_MILLIS = 5_000L

/** A row that opens an editor for what it shows: the chevron says so. */
@Composable
private fun EditRow(title: String, summary: String, onClick: () -> Unit) {
    SettingRow(title, summary, onClick = onClick, trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = Soft.colors.inkMuted, modifier = Modifier.size(18.dp)) })
}

/** A row that leaves the app for the forge's website or the repository's site: the open-in-new glyph says so. */
@Composable
private fun LeaveRow(title: String, summary: String, onClick: () -> Unit) {
    SettingRow(title, summary, onClick = onClick, trailing = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, tint = Soft.colors.inkMuted, modifier = Modifier.size(18.dp)) })
}

/** "kotlin, tools, android +2": three topics and how many more. */
@Composable
private fun topicsSummary(topics: List<String>): String? = when {
    topics.isEmpty() -> null
    topics.size <= 3 -> topics.joinToString(", ")
    else -> stringResource(R.string.repo_settings_topics_more, topics.take(3).joinToString(", "), topics.size - 3)
}

/** What the forge just took, in a few words: "Wiki switched off", "How it merges set to Fast-forward". */
@Composable
private fun appliedText(applied: Applied, settings: RepoSettings?, forge: ForgeType): String? {
    applied.pages?.let { change ->
        return when (change) {
            is PagesChange.Https -> stringResource(if (change.enforced) R.string.repo_settings_switched_on else R.string.repo_settings_switched_off, stringResource(R.string.repo_pages_https))
            is PagesChange.Publish -> stringResource(R.string.repo_settings_saved, stringResource(R.string.repo_pages_source))
            is PagesChange.Domain -> stringResource(R.string.repo_settings_saved, stringResource(R.string.repo_pages_domain))
            PagesChange.Unpublish -> stringResource(R.string.repo_pages_unpublished)
        }
    }
    return when (val change = applied.change ?: return null) {
        is RepoSettingsChange.Switch -> stringResource(if (change.on) R.string.repo_settings_switched_on else R.string.repo_settings_switched_off, stringResource(change.switch.label(forge)))
        is RepoSettingsChange.Style -> stringResource(R.string.repo_settings_set_to, stringResource(R.string.repo_settings_merge_style), stringResource(change.style.label))
        is RepoSettingsChange.DefaultMerge -> stringResource(R.string.repo_settings_set_to, stringResource(R.string.repo_settings_default_merge), stringResource(change.method.label))
        is RepoSettingsChange.DefaultBranch -> stringResource(R.string.repo_settings_set_to, stringResource(R.string.repo_settings_default_branch), change.branch)
        is RepoSettingsChange.Description -> stringResource(R.string.repo_settings_saved, stringResource(R.string.repo_settings_description))
        is RepoSettingsChange.Website -> stringResource(R.string.repo_settings_saved, stringResource(R.string.repo_settings_website))
        is RepoSettingsChange.Topics -> stringResource(R.string.repo_settings_saved, stringResource(R.string.repo_settings_topics))
        is RepoSettingsChange.Visibility -> stringResource(R.string.repo_settings_set_to, stringResource(R.string.repo_settings_visibility), stringResource(change.visibility.label))
        is RepoSettingsChange.Archived -> stringResource(if (change.archived) R.string.repo_settings_archived_done else R.string.repo_settings_unarchived_done)
    }.takeIf { settings != null }
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


/** Words to write and save; what the forge refuses of them is said here, and they stay to be put right. */
@Composable
private fun TextSheet(title: String, current: String, singleLine: Boolean, saving: Boolean, refusal: String?, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    SoftSheet(title, onDismiss) {
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
    SoftSheet(stringResource(R.string.repo_settings_topics), onDismiss) {
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
    SoftSheet(stringResource(R.string.repo_settings_default_branch), onDismiss) {
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
private fun VisibilitySheet(current: RepoVisibility, offered: List<RepoVisibility>, onDismiss: () -> Unit, onConfirm: (RepoVisibility) -> Unit) {
    var picked by rememberSaveable { mutableStateOf(current) }
    SoftConfirmSheet(
        title = stringResource(R.string.repo_settings_visibility),
        body = stringResource(R.string.repo_settings_visibility_warning),
        confirm = stringResource(R.string.repo_settings_visibility_confirm),
        cancel = stringResource(R.string.cancel),
        // Opening it to everyone shows its whole history, and closing it can cost its forks and stars.
        danger = true,
        confirmEnabled = picked != current,
        onConfirm = { onConfirm(picked) },
        onDismiss = onDismiss,
    ) {
        Column(Modifier.selectableGroup()) {
            offered.forEach { option -> SoftChoiceRow(stringResource(option.label), option == picked, { picked = option }, summary = stringResource(option.summary)) }
        }
    }
}

/**
 * Deleting asks for the repository's full name, written out in a field that starts empty: a tap alone can't do it.
 * A sign-in that may not delete is told so before anything is written, with the way to make it again.
 */
@Composable
private fun DeleteSheet(repo: RepoId, working: Boolean, mayDelete: Boolean?, refusal: String?, onDelete: () -> Unit, onSignIn: () -> Unit, onDismiss: () -> Unit) {
    val forgeName = repo.forge.displayName
    if (mayDelete == false) {
        SoftConfirmSheet(
            title = stringResource(R.string.repo_settings_delete),
            body = stringResource(R.string.repo_settings_delete_needs_sign_in, forgeName),
            confirm = stringResource(R.string.you_limited_action, forgeName),
            cancel = stringResource(R.string.cancel),
            onConfirm = onSignIn,
            onDismiss = onDismiss,
        )
        return
    }
    var typed by rememberSaveable { mutableStateOf("") }
    SoftConfirmSheet(
        title = stringResource(R.string.repo_settings_delete),
        body = stringResource(R.string.repo_settings_delete_body, repo.fullName),
        confirm = stringResource(if (working) R.string.manage_deleting else R.string.repo_settings_delete_confirm),
        cancel = stringResource(R.string.cancel),
        danger = true,
        confirmEnabled = !working && typed.trim() == repo.fullName,
        onConfirm = onDelete,
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // The name to write stays in sight above the field, which starts empty: a placeholder would look typed.
            val fieldName = stringResource(R.string.repo_settings_delete_field, repo.fullName)
            Text(fieldName, style = Soft.type.label, color = Soft.colors.ink)
            SoftTextField(typed, { typed = it }, placeholder = "", error = refusal, modifier = Modifier.semantics { contentDescription = fieldName })
        }
    }
}

/** "GitHub Actions", or the branch and the folder a site is made of: "main, /docs". */
@Composable
private fun PagesBuild.label(): String = when (this) {
    PagesBuild.Workflow -> stringResource(R.string.repo_pages_source_workflow)
    is PagesBuild.Branch -> stringResource(R.string.repo_pages_source_branch_value, branch, folder)
}

/** What the site is built from: what a workflow uploads, or a folder of a branch. Publishes a site that isn't yet. */
@Composable
private fun PagesSourceSheet(
    site: PagesSite?,
    defaultBranch: String,
    branches: Loadable<List<String>>,
    working: Boolean,
    refusal: String?,
    onSave: (PagesBuild) -> Unit,
    onDismiss: () -> Unit,
) {
    val current = site?.build
    val from = current as? PagesBuild.Branch
    var workflow by rememberSaveable { mutableStateOf(current == null || current == PagesBuild.Workflow) }
    var branch by rememberSaveable { mutableStateOf(from?.branch ?: defaultBranch) }
    var folder by rememberSaveable { mutableStateOf(from?.folder ?: "/") }
    val picked = if (workflow) PagesBuild.Workflow else PagesBuild.Branch(branch, folder)
    SoftSheet(stringResource(R.string.repo_pages_source), onDismiss) {
        Column(Modifier.selectableGroup()) {
            SoftChoiceRow(stringResource(R.string.repo_pages_source_workflow), workflow, { workflow = true }, summary = stringResource(R.string.repo_pages_source_workflow_summary))
            SoftChoiceRow(stringResource(R.string.repo_pages_source_branch), !workflow, { workflow = false }, summary = stringResource(R.string.repo_pages_source_branch_summary))
        }
        if (!workflow) {
            // The branches once read; the default one meanwhile, and whichever the site is on already.
            val offered = ((branches as? Loadable.Loaded)?.value.orEmpty() + branch + defaultBranch).filter { it.isNotEmpty() }.distinct()
            Text(stringResource(R.string.repo_pages_branch), style = Soft.type.label, color = Soft.colors.inkMuted)
            Column(Modifier.selectableGroup()) { offered.forEach { name -> SoftChoiceRow(name, name == branch, { branch = name }) } }
            Text(stringResource(R.string.repo_pages_folder), style = Soft.type.label, color = Soft.colors.inkMuted)
            Column(Modifier.selectableGroup()) { listOf("/", "/docs").forEach { path -> SoftChoiceRow(path, path == folder, { folder = path }) } }
        }
        refusal?.let { Text(it, style = Soft.type.secondary, color = Soft.colors.accent) }
        SoftButton(
            stringResource(if (site?.published == true) R.string.manage_save else R.string.repo_pages_publish),
            onClick = { onSave(picked) },
            enabled = !working && (site?.published != true || picked != current),
        )
    }
}
