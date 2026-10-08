package fr.arthurbrugiere.forgeline.repo

import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.filled.Visibility
import fr.arthurbrugiere.forgeline.ui.sideSafeArea
import fr.arthurbrugiere.forgeline.ui.rememberNow
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import fr.arthurbrugiere.forgeline.core.ui.format.languageColor
import fr.arthurbrugiere.forgeline.core.ui.format.ForgeMark
import fr.arthurbrugiere.forgeline.session.signedInOn
import fr.arthurbrugiere.forgeline.navigation.openForgeLink
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.ui.platform.LocalDensity
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTextField
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSwitch
import fr.arthurbrugiere.forgeline.core.model.IssueQuery
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.SubdirectoryArrowLeft
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.markdown.ForgelineMarkdown
import fr.arthurbrugiere.forgeline.core.markdown.ReadmeContext
import fr.arthurbrugiere.forgeline.core.markdown.rememberReadmeState
import fr.arthurbrugiere.forgeline.core.model.Release
import fr.arthurbrugiere.forgeline.core.model.RepoDetails
import fr.arthurbrugiere.forgeline.core.model.RepoFile
import fr.arthurbrugiere.forgeline.core.model.RepoFileType
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RunConclusion
import fr.arthurbrugiere.forgeline.core.model.RunStatus
import fr.arthurbrugiere.forgeline.core.model.WorkflowRun
import fr.arthurbrugiere.forgeline.core.ui.format.compactCount
import fr.arthurbrugiere.forgeline.navigation.ForgeLinks
import fr.arthurbrugiere.forgeline.navigation.RepoRoute
import fr.arthurbrugiere.forgeline.navigation.IssueRoute
import fr.arthurbrugiere.forgeline.navigation.RunRoute
import fr.arthurbrugiere.forgeline.navigation.UserRoute
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.session.SessionState
import fr.arthurbrugiere.forgeline.actions.RowIcon
import fr.arthurbrugiere.forgeline.actions.WorkflowDispatchSheet
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTonalButton
import fr.arthurbrugiere.forgeline.actions.RunStatusIcon
import fr.arthurbrugiere.forgeline.ui.IssueSummaryRow
import fr.arthurbrugiere.forgeline.ui.Avatar
import fr.arthurbrugiere.forgeline.ui.Badge
import fr.arthurbrugiere.forgeline.ui.Message
import fr.arthurbrugiere.forgeline.ui.loadable
import fr.arthurbrugiere.forgeline.ui.message
import fr.arthurbrugiere.forgeline.ui.relative
import fr.arthurbrugiere.forgeline.ui.rememberCustomTabOpener
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Snackbar
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftChipTabs
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftStatusBarScrim
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTag
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.softPressable
import fr.arthurbrugiere.forgeline.ui.LocalBottomBarSpace
import fr.arthurbrugiere.forgeline.ui.SayOnce
import fr.arthurbrugiere.forgeline.ui.LocalOpenRelease
import fr.arthurbrugiere.forgeline.ui.listBottomPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
fun RepoRoute(
    route: RepoRoute,
    session: SessionState,
    onBack: () -> Unit,
    onOpenRepo: (RepoId) -> Unit,
    onOpenFile: (RepoId, path: String, ref: String) -> Unit,
    onOpenIssue: (IssueRef) -> Unit,
    onNewIssue: (RepoId) -> Unit,
    onOpenRun: (RepoId, Long) -> Unit,
    onOpenUser: (String) -> Unit,
    onSignIn: () -> Unit,
) {
    val id = route.repo
    val viewModel = hiltViewModel<RepoViewModel, RepoViewModel.Factory>(key = id.key) { it.create(id) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openUrl = rememberCustomTabOpener()
    val openRelease = LocalOpenRelease.current
    val signedIn = session.signedInOn(id.forge)
    var dispatching by rememberSaveable { mutableStateOf(false) }
    val details = state.details
    if (dispatching && details != null) {
        WorkflowDispatchSheet(
            repo = details.id,
            defaultBranch = details.defaultBranch,
            onDismiss = { dispatching = false },
            onStarted = {
                dispatching = false
                viewModel.workflowStarted()
            },
        )
    }
    LaunchedEffect(state.forkedTo) {
        state.forkedTo?.let { fork ->
            viewModel.forkOpened()
            onOpenRepo(fork)
        }
    }
    RepoScreen(
        state = state,
        signedIn = signedIn,
        onToggleWatch = viewModel::toggleWatch,
        onLoadMore = viewModel::loadMore,
        onFork = viewModel::fork,
        onWatchFailureShown = viewModel::watchFailureShown,
        onForkErrorShown = viewModel::forkErrorShown,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onSelectTab = viewModel::selectTab,
        onRetryTab = viewModel::retryTab,
        onToggleStar = { if (signedIn) viewModel.toggleStar() else onSignIn() },
        onOpenDirectory = viewModel::openDirectory,
        onOpenParentDirectory = viewModel::openParentDirectory,
        onOpenFile = { file -> state.details?.let { onOpenFile(it.id, file.path, state.browsedRef ?: it.defaultBranch) } },
        // The kind goes with the number: on GitLab a merge request and an issue can share theirs.
        onOpenIssue = { number, isPullRequest -> state.details?.let { onOpenIssue(IssueRef(it.id, number, isPullRequest)) } },
        // Opening an issue needs an account on the repository's forge, like starring it.
        onNewIssue = { if (signedIn) state.details?.let { onNewIssue(it.id) } else onSignIn() },
        onShowOpen = viewModel::showOpen,
        onSearch = viewModel::search,
        onOpenRelease = { tag -> state.details?.let { openRelease?.invoke(it.id, tag) } },
        onOpenRun = { runId -> state.details?.let { onOpenRun(it.id, runId) } },
        onOpenUser = onOpenUser,
        onLinkClick = { url -> openForgeLink(url, id.forge, onOpenRepo, onOpenIssue, onOpenUser, openUrl, onOpenRun, openRelease) },
        onOpenInBrowser = openUrl,
        onLoadRefs = viewModel::loadRefs,
        onRunWorkflow = if (signedIn) ({ dispatching = true }) else null,
        onWorkflowStartShown = viewModel::workflowStartShown,
        onSelectRef = viewModel::selectRef,
        onErrorShown = viewModel::errorShown,
        onStarFailureShown = viewModel::starFailureShown,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RepoScreen(
    state: RepoUiState,
    signedIn: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelectTab: (RepoTab) -> Unit,
    onRetryTab: () -> Unit,
    onToggleStar: () -> Unit,
    onOpenDirectory: (String) -> Unit,
    onOpenParentDirectory: () -> Unit,
    onOpenFile: (RepoFile) -> Unit,
    onOpenIssue: (number: Int, isPullRequest: Boolean) -> Unit,
    onNewIssue: () -> Unit,
    onOpenUser: (String) -> Unit,
    /** Opens the page of the release under a tag. */
    onOpenRelease: (String) -> Unit = {},
    /** Lists the open (true) or the closed issues or pull requests. */
    onShowOpen: (Boolean) -> Unit = {},
    /** Looks for words among the issues or pull requests. */
    onSearch: (String) -> Unit = {},
    onLinkClick: (String) -> Unit,
    onOpenRun: (Long) -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onLoadRefs: () -> Unit,
    onSelectRef: (String) -> Unit,
    /** Null when signed out: starting a workflow needs an account. */
    onRunWorkflow: (() -> Unit)?,
    onWorkflowStartShown: () -> Unit,
    onErrorShown: () -> Unit,
    onStarFailureShown: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleWatch: () -> Unit = {},
    /** Asks for the next page of the issues or pull requests listed. */
    onLoadMore: () -> Unit = {},
    /** Copies the repository to the reader's account, once they have said yes. */
    onFork: () -> Unit = {},
    onWatchFailureShown: () -> Unit = {},
    onForkErrorShown: () -> Unit = {},
    nowMillis: Long = rememberNow(state.issues, state.pulls, state.pinned, state.releases, state.runs),
) {
    val colors = Soft.colors
    val snackbar = remember { SnackbarHostState() }
    val refreshFailed = stringResource(R.string.trending_refresh_failed)
    val starFailed = stringResource(R.string.trending_star_failed)
    val details = state.details
    LaunchedEffect(state.error, details != null) {
        if (state.error != null && details != null) {
            snackbar.showSnackbar(refreshFailed)
            onErrorShown()
        }
    }
    val workflowStarted = stringResource(R.string.dispatch_started)
    LaunchedEffect(state.workflowStarted) {
        if (state.workflowStarted) {
            snackbar.showSnackbar(workflowStarted)
            onWorkflowStartShown()
        }
    }
    LaunchedEffect(state.starFailed) {
        if (state.starFailed) {
            snackbar.showSnackbar(starFailed)
            onStarFailureShown()
        }
    }
    SayOnce(stringResource(R.string.repo_watch_failed).takeIf { state.watchFailed }, snackbar, onWatchFailureShown)
    val forkFailed = state.forkError?.let { error ->
        stringResource(
            when {
                error == ForgeError.Network -> R.string.repo_fork_offline
                // Forgejo refuses a second fork, and no forge copies a repository onto itself.
                error is ForgeError.Http && (error.status == 409 || error.status == 422) -> R.string.repo_fork_exists
                error is ForgeError.Http && (error.status == 403 || error.status == 404) -> R.string.repo_fork_refused
                else -> R.string.repo_fork_failed
            },
        )
    }
    SayOnce(forkFailed, snackbar, onForkErrorShown)
    // A fork makes a repository in the reader's name: asked about first.
    var forking by rememberSaveable { mutableStateOf(false) }
    if (forking) {
        val forge = (details?.id ?: state.requested).forge.displayName
        AlertDialog(
            onDismissRequest = { forking = false },
            title = { Text(stringResource(R.string.repo_fork_title, (details?.id ?: state.requested).name)) },
            text = { Text(stringResource(R.string.repo_fork_body, forge)) },
            confirmButton = {
                TextButton(onClick = {
                    forking = false
                    onFork()
                }) { Text(stringResource(R.string.repo_fork_action)) }
            },
            dismissButton = { TextButton(onClick = { forking = false }) { Text(stringResource(R.string.cancel)) } },
            shape = RoundedCornerShape(28.dp),
            containerColor = colors.raised,
        )
    }

    val id = details?.id ?: state.requested
    val listState = rememberLazyListState()
    var pickingRef by rememberSaveable { mutableStateOf(false) }
    Box(modifier.fillMaxSize().background(colors.ground)) {
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = state.isRefreshing && details != null,
            onRefresh = onRefresh,
            state = pullState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = state.isRefreshing && details != null,
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
                        tint = colors.fields[0],
                        onBack = onBack,
                        backDescription = stringResource(R.string.navigate_up),
                        actions = {
                            IconButton(onClick = { onOpenInBrowser(id.webUrl) }) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.OpenInNew,
                                    contentDescription = stringResource(R.string.repo_open_on_forge, id.forge.displayName),
                                    tint = colors.ink,
                                )
                            }
                        },
                    ) {
                        if (details != null) {
                            RepoHeader(details, state.starred, signedIn, onToggleStar, onLinkClick, onOpenUser, state.watching, onToggleWatch, state.isForking, onFork = { forking = true })
                        } else {
                            Text(id.name, style = Soft.type.title, color = colors.ink)
                        }
                    }
                }
                when {
                    details == null && state.error != null -> item(key = "error") {
                        SoftNotice(
                            stringResource(R.string.repo_error_title),
                            stringResource(
                                if (state.error is ForgeError.Http && state.error.status == 404) R.string.repo_error_not_found else state.error.message,
                            ),
                            action = stringResource(R.string.retry),
                            onAction = onRefresh,
                        )
                    }
                    details == null -> item(key = "loading") { SoftLoadingRows(stringResource(R.string.repo_loading), rows = 3, leadingDot = false) }
                    else -> {
                        stickyHeader(key = "tabs") {
                            SoftChipTabs(
                                options = state.tabs.map { stringResource(it.label) },
                                selected = state.tabs.indexOf(state.tab).coerceAtLeast(0),
                                onSelect = { onSelectTab(state.tabs[it]) },
                            )
                        }
                        val browsedRef = state.browsedRef
                        if (browsedRef != null && (state.tab == RepoTab.README || state.tab == RepoTab.CODE)) {
                            item(key = "ref") {
                                RefPill(browsedRef, state.refs, onClick = {
                                    onLoadRefs()
                                    pickingRef = true
                                })
                            }
                        }
                        when (state.tab) {
                            RepoTab.README -> when (val refReadme = state.refReadme) {
                                // Another ref's README loads on demand; the default branch's comes from the cache.
                                Loadable.Loading -> item(key = "readme-loading") { SoftLoadingRows(stringResource(R.string.repo_loading), rows = 3, leadingDot = false) }
                                is Loadable.Failed -> item(key = "readme-failed") {
                                    SoftNotice(
                                        stringResource(R.string.repo_tab_failed),
                                        stringResource(refReadme.error.message),
                                        action = stringResource(R.string.retry),
                                        onAction = onRetryTab,
                                    )
                                }
                                else -> item(key = "readme") {
                                    val readme = state.readme
                                    if (readme == null || state.readmeContext == null) {
                                        Message(stringResource(R.string.repo_no_readme))
                                    } else {
                                        Readme(readme.markdown, state.readmeContext, onLinkClick)
                                    }
                                }
                            }
                            RepoTab.CODE -> code(state.code, onRetryTab, onOpenDirectory, onOpenParentDirectory, onOpenFile)
                            RepoTab.ISSUES -> {
                                item(key = "issues-find") {
                                    ListControls(
                                        state.issueQuery, stringResource(R.string.repo_search_issues), onShowOpen, onSearch,
                                        onNewIssue = onNewIssue.takeIf { details.takesIssues },
                                    )
                                }
                                // What the repository pins heads its plain list: not the closed ones, nor a search's results.
                                if (state.issueQuery.isDefault && state.pinned.isNotEmpty() && state.issues is Loadable.Loaded) {
                                    item(key = "pinned-title") { ListTitle(stringResource(R.string.repo_pinned)) }
                                    items(state.pinned, key = { "pinned-${it.number}" }) { IssueSummaryRow(it, nowMillis, { number -> onOpenIssue(number, it.isPullRequest) }, pinned = true) }
                                    item(key = "open-title") { ListTitle(stringResource(R.string.repo_open_issues)) }
                                }
                                loadable(state.issues, state.issueQuery.emptyMessage(R.string.repo_no_issues, R.string.repo_no_closed_issues), onRetryTab) { issues ->
                                    items(issues, key = { "issue-${it.number}" }) { IssueSummaryRow(it, nowMillis, { number -> onOpenIssue(number, it.isPullRequest) }) }
                                    more(state.issuePaging, onLoadMore)
                                }
                            }
                            RepoTab.PULLS -> {
                                item(key = "pulls-find") { ListControls(state.pullQuery, stringResource(R.string.repo_search_pulls), onShowOpen, onSearch, onNewIssue = null) }
                                loadable(state.pulls, state.pullQuery.emptyMessage(R.string.repo_no_pulls, R.string.repo_no_closed_pulls), onRetryTab) { pulls ->
                                    items(pulls, key = { "pull-${it.number}" }) { IssueSummaryRow(it, nowMillis, { number -> onOpenIssue(number, it.isPullRequest) }) }
                                    more(state.pullPaging, onLoadMore)
                                }
                            }
                            RepoTab.RELEASES -> loadable(state.releases, R.string.repo_no_releases, onRetryTab) { releases ->
                                items(releases, key = { "release-${it.tag}" }) { ReleaseRow(it, nowMillis, onClick = { onOpenRelease(it.tag) }) }
                            }
                            RepoTab.ACTIONS -> {
                                if (onRunWorkflow != null) {
                                    item(key = "run-workflow") {
                                        Box(RowModifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                                            SoftTonalButton(stringResource(R.string.dispatch_title), onClick = onRunWorkflow)
                                        }
                                    }
                                }
                                loadable(state.runs, R.string.repo_no_runs, onRetryTab) { runs ->
                                    items(runs, key = { "run-${it.id}" }) { RunRow(it, nowMillis, onClick = { onOpenRun(it.id) }) }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (pickingRef && details != null) {
            RefSheet(
                current = state.browsedRef ?: details.defaultBranch,
                defaultBranch = details.defaultBranch,
                refs = state.refs,
                onSelect = {
                    pickingRef = false
                    onSelectRef(it)
                },
                onRetry = onLoadRefs,
                onDismiss = { pickingRef = false },
            )
        }
        val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
        SoftStatusBarScrim(scrolled)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalBottomBarSpace.current)) { data ->
            Snackbar(data, shape = RoundedCornerShape(16.dp), containerColor = colors.ink, contentColor = colors.ground)
        }
    }
}

/** What an empty list says: nothing matches the words, or there is none open, or none closed. */
private fun IssueQuery.emptyMessage(noneOpen: Int, noneClosed: Int): Int = when {
    text.isNotBlank() -> R.string.repo_no_match
    open -> noneOpen
    else -> noneClosed
}

/**
 * What heads a list of issues or pull requests: the open or the closed ones, the action that opens a new one where
 * there is one, and a field to look for words among them.
 */
@Composable
private fun ListControls(query: IssueQuery, searchHint: String, onShowOpen: (Boolean) -> Unit, onSearch: (String) -> Unit, onNewIssue: (() -> Unit)?) {
    val colors = Soft.colors
    Column(RowModifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val options = listOf(stringResource(R.string.repo_list_open), stringResource(R.string.repo_list_closed))
        // Side by side, the switch's words shrink at large text: there, the action goes under it.
        if (onNewIssue != null && LocalDensity.current.fontScale > 1.3f) {
            SoftSwitch(options, selected = if (query.open) 0 else 1, onSelect = { onShowOpen(it == 0) })
            SoftTonalButton(stringResource(R.string.new_issue_title), onClick = onNewIssue)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftSwitch(options, selected = if (query.open) 0 else 1, onSelect = { onShowOpen(it == 0) }, modifier = Modifier.weight(1f))
                if (onNewIssue != null) SoftTonalButton(stringResource(R.string.new_issue_title), onClick = onNewIssue)
            }
        }
        SoftTextField(
            value = query.text,
            onValueChange = onSearch,
            placeholder = searchHint,
            leading = { Icon(Icons.Outlined.Search, contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(20.dp)) },
            trailing = {
                if (query.text.isNotEmpty()) {
                    IconButton(onClick = { onSearch("") }) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.search_clear), tint = colors.inkMuted)
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A quiet word above a group of rows. */
@Composable
private fun ListTitle(text: String) {
    Text(
        text,
        style = Soft.type.label,
        color = Soft.colors.inkMuted,
        modifier = RowModifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 2.dp).semantics { heading() },
    )
}

/** An archived repository is read-only, and an owner can switch issues off. */
private val RepoDetails.takesIssues: Boolean get() = hasIssues && !isArchived

/**
 * The end of a list that has more: reaching it asks for the next page, by itself. A page that couldn't be loaded is
 * asked for by hand instead, so a forge that is down isn't asked again and again.
 */
private fun LazyListScope.more(paging: ListPaging, onLoadMore: () -> Unit) {
    val next = paging.next ?: return
    item(key = "more") {
        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            if (paging.failed) {
                SoftTonalButton(stringResource(R.string.issue_load_more), onLoadMore)
            } else {
                LaunchedEffect(next) { onLoadMore() }
                val loading = stringResource(R.string.repo_loading_more)
                CircularProgressIndicator(Modifier.semantics { contentDescription = loading }, color = Soft.colors.accent, trackColor = Soft.colors.surface)
            }
        }
    }
}

@Composable
private fun RepoHeader(
    details: RepoDetails,
    starred: Boolean?,
    signedIn: Boolean,
    onToggleStar: () -> Unit,
    onLinkClick: (String) -> Unit,
    onOpenUser: (String) -> Unit,
    watching: Boolean? = null,
    onToggleWatch: () -> Unit = {},
    isForking: Boolean = false,
    onFork: () -> Unit = {},
) {
    val colors = Soft.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.clip(SoftTokens.Pill).clickable { onOpenUser(details.id.owner) }.padding(end = 8.dp),
            ) {
                Avatar(details.ownerAvatarUrl, details.id.owner, size = 28.dp, placeholderColor = colors.ground, placeholderContentColor = colors.inkMuted)
                Text(details.id.owner, style = Soft.type.secondary, color = colors.inkMuted)
            }
            // Where the repository lives: always said here, whatever else is signed in.
            ForgeMark(details.id.forge, style = Soft.type.secondary, color = colors.inkMuted, size = 16.dp)
        }
        Text(details.id.name, style = Soft.type.detailTitle, color = colors.ink, modifier = Modifier.semantics { heading() })
        details.description?.let {
            Text(it, style = Soft.type.body.copy(fontSize = 16.sp), color = colors.ink, modifier = Modifier.widthIn(max = SoftTokens.MaxMeasure))
        }
        details.homepage?.let { url ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.clip(SoftTokens.Pill).clickable { onLinkClick(url) },
            ) {
                Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.accent)
                Text(url.removePrefix("https://").removePrefix("http://").trimEnd('/'), style = Soft.type.secondary, color = colors.accent)
            }
        }
        if (details.topics.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                details.topics.forEach { SoftTag(it, background = colors.ground) }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Stat(stringResource(R.string.trending_stars, compactCount(details.stars)))
            Stat(stringResource(R.string.trending_forks, compactCount(details.forks)))
            Stat(stringResource(R.string.repo_watchers, compactCount(details.watchers)))
            details.language?.let { language ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    languageColor(language)?.let { dot ->
                        Box(Modifier.size(9.dp).background(dot, CircleShape))
                        Spacer(Modifier.width(6.dp))
                    }
                    Stat(language)
                }
            }
            details.license?.let { Stat(it) }
        }
        if (details.isArchived || details.isFork) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (details.isArchived) SoftTag(stringResource(R.string.repo_archived), background = colors.ground)
                if (details.isFork) SoftTag(stringResource(R.string.repo_fork), background = colors.ground)
            }
        }
        val isStarred = starred == true && signedIn
        // Side by side while they fit; at large text or on a narrow screen they go one under another.
        FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HeaderAction(
                icon = if (isStarred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                label = stringResource(if (isStarred) R.string.repo_starred else R.string.repo_star),
                // The one thing asked of a passer-by stands out until it is done.
                prominent = !isStarred,
                on = isStarred,
                onToggle = onToggleStar,
            )
            // Watching and forking are an account's: nothing is offered before the forge has said where it stands.
            if (signedIn && watching != null) {
                HeaderAction(
                    icon = if (watching) Icons.Filled.Visibility else Icons.Outlined.Visibility,
                    label = stringResource(if (watching) R.string.repo_watching else R.string.repo_watch),
                    on = watching,
                    onToggle = onToggleWatch,
                )
            }
            if (signedIn) {
                HeaderAction(
                    icon = Icons.AutoMirrored.Outlined.CallSplit,
                    label = stringResource(if (isForking) R.string.repo_forking else R.string.repo_fork_action),
                    enabled = !isForking,
                    onClick = onFork,
                )
            }
        }
    }
}

/**
 * One of the header's actions, as a pill. With [onToggle] it is a switch, so screen readers hear "on" or "off" as well
 * as the label; with [onClick] a button.
 */
@Composable
private fun HeaderAction(
    icon: ImageVector,
    label: String,
    prominent: Boolean = false,
    on: Boolean = false,
    enabled: Boolean = true,
    onToggle: (() -> Unit)? = null,
    onClick: () -> Unit = {},
) {
    val colors = Soft.colors
    Box(
        Modifier
            .clip(SoftTokens.Pill)
            .background(if (prominent) colors.thumb else colors.ground)
            .then(
                if (onToggle != null) Modifier.toggleable(value = on, role = Role.Switch, onValueChange = { onToggle() })
                else Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
            )
            .heightIn(min = 48.dp)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                icon,
                contentDescription = null,
                tint = when {
                    prominent -> colors.onThumb
                    on -> colors.accent
                    else -> colors.ink.copy(alpha = if (enabled) 1f else 0.5f)
                },
                modifier = Modifier.size(18.dp),
            )
            Text(label, style = Soft.type.control, color = if (prominent) colors.onThumb else colors.ink.copy(alpha = if (enabled) 1f else 0.5f))
        }
    }
}

@Composable
private fun Readme(markdown: String, context: ReadmeContext, onLinkClick: (String) -> Unit) {
    val darkTheme = Soft.colors.isDark
    val parsed = rememberReadmeState(markdown, context, darkTheme)
    if (parsed == null) {
        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Soft.colors.accent, trackColor = Soft.colors.surface)
        }
    } else {
        ForgelineMarkdown(parsed, onLinkClick, Modifier.widthIn(max = SoftTokens.MaxReadingWidth).padding(horizontal = 20.dp, vertical = 12.dp))
    }
}

private val RowModifier = Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)

private fun LazyListScope.code(
    code: CodeState,
    onRetry: () -> Unit,
    onOpenDirectory: (String) -> Unit,
    onOpenParentDirectory: () -> Unit,
    onOpenFile: (RepoFile) -> Unit,
) {
    if (code.path.isNotEmpty()) {
        item(key = "code-path") {
            val colors = Soft.colors
            Row(
                RowModifier.softPressable(onClick = onOpenParentDirectory).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RowIcon(Icons.Outlined.SubdirectoryArrowLeft, colors.surface)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(stringResource(R.string.repo_parent_folder), style = Soft.type.body, color = colors.ink)
                    Text(code.path, style = Soft.type.meta, color = colors.inkMuted)
                }
            }
        }
    }
    loadable(code.entries, R.string.repo_empty_folder, onRetry) { entries ->
        items(entries, key = { "file-${it.path}" }) { file ->
            val colors = Soft.colors
            Row(
                RowModifier.softPressable {
                    when (file.type) {
                        RepoFileType.DIR -> onOpenDirectory(file.path)
                        RepoFileType.FILE -> onOpenFile(file)
                        RepoFileType.SYMLINK, RepoFileType.SUBMODULE -> Unit
                    }
                }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RowIcon(
                    when (file.type) {
                        RepoFileType.DIR -> Icons.Outlined.Folder
                        RepoFileType.SUBMODULE, RepoFileType.SYMLINK -> Icons.Outlined.Link
                        RepoFileType.FILE -> Icons.AutoMirrored.Outlined.InsertDriveFile
                    },
                    if (file.type == RepoFileType.DIR) colors.fields[1] else colors.surface,
                )
                Spacer(Modifier.width(14.dp))
                Text(file.name, style = Soft.type.body, color = colors.ink)
            }
        }
    }
}

/** One release in the list: what it is called, its tag and age, where it stands, and how much it ships. Its page holds the rest. */
@Composable
private fun ReleaseRow(release: Release, nowMillis: Long, onClick: () -> Unit) {
    val colors = Soft.colors
    Row(RowModifier.softPressable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        RowIcon(Icons.Outlined.NewReleases, if (release.isPrerelease) colors.surface else colors.fields[0])
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(release.name ?: release.tag, style = Soft.type.body, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    release.tag.takeIf { release.name != null },
                    release.publishedAt?.let { relative(it, nowMillis) },
                    release.assets.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.release_assets, it, it) },
                ).joinToString(" · "),
                style = Soft.type.meta,
                color = colors.inkMuted,
            )
            if (release.isLatest || release.isPrerelease) {
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (release.isLatest) Badge(stringResource(R.string.release_latest))
                    if (release.isPrerelease) Badge(stringResource(R.string.repo_prerelease))
                }
            }
        }
    }
}

@Composable
private fun RunRow(run: WorkflowRun, nowMillis: Long, onClick: () -> Unit) {
    val colors = Soft.colors
    Row(RowModifier.softPressable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        RunStatusIcon(run)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(run.title, style = Soft.type.body, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    stringResource(R.string.repo_run_meta, run.workflowName, run.branch ?: run.event, run.runNumber),
                    relative(run.createdAt, nowMillis),
                ).joinToString(" · "),
                style = Soft.type.meta,
                color = colors.inkMuted,
            )
        }
    }
}

@Composable
private fun Stat(text: String) {
    Text(text, style = Soft.type.meta, color = Soft.colors.inkMuted)
}

private val RepoTab.label: Int
    get() = when (this) {
        RepoTab.README -> R.string.repo_tab_readme
        RepoTab.CODE -> R.string.repo_tab_code
        RepoTab.ISSUES -> R.string.repo_tab_issues
        RepoTab.PULLS -> R.string.repo_tab_pulls
        RepoTab.RELEASES -> R.string.repo_tab_releases
        RepoTab.ACTIONS -> R.string.repo_tab_actions
    }