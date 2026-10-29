package fr.arthurbrugiere.forgeline.repo

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.actions.WorkflowDispatchSheet
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.ForgeType
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoDetails
import fr.arthurbrugiere.forgeline.core.model.RepoFile
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.ui.format.compactCount
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftChipTabs
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftConfirmSheet
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftIconButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSnackbarHost
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftStatusBarScrim
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTonalButton
import fr.arthurbrugiere.forgeline.navigation.RepoRoute
import fr.arthurbrugiere.forgeline.navigation.openForgeLink
import fr.arthurbrugiere.forgeline.session.SessionState
import fr.arthurbrugiere.forgeline.session.signedInOn
import fr.arthurbrugiere.forgeline.ui.IssueSummaryRow
import fr.arthurbrugiere.forgeline.ui.LocalBottomBarSpace
import fr.arthurbrugiere.forgeline.ui.LocalOpenRelease
import fr.arthurbrugiere.forgeline.ui.Message
import fr.arthurbrugiere.forgeline.ui.SayOnce
import fr.arthurbrugiere.forgeline.ui.ShareLinkButton
import fr.arthurbrugiere.forgeline.ui.listBottomPadding
import fr.arthurbrugiere.forgeline.ui.loadable
import fr.arthurbrugiere.forgeline.ui.message
import fr.arthurbrugiere.forgeline.ui.rememberCustomTabOpener
import fr.arthurbrugiere.forgeline.ui.rememberNow
import fr.arthurbrugiere.forgeline.ui.sideSafeArea

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
    /** Opens the history of the repository from a ref, of the folder at [path] when the Code tab is in one. */
    onOpenHistory: (RepoId, ref: String, path: String?) -> Unit = { _, _, _ -> },
    /** Opens the form for a new pull request in a repository. */
    onNewPullRequest: (RepoId) -> Unit = {},
    /** Opens what the repository's owner sets on it. */
    onOpenSettings: (RepoId) -> Unit = {},
) {
    val id = route.repo
    val viewModel = hiltViewModel<RepoViewModel, RepoViewModel.Factory>(key = id.key) { it.create(id) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openUrl = rememberCustomTabOpener()
    val openDiscussion = fr.arthurbrugiere.forgeline.ui.LocalOpenDiscussion.current
    val openWiki = fr.arthurbrugiere.forgeline.ui.LocalOpenWiki.current
    val openCommit = fr.arthurbrugiere.forgeline.ui.LocalOpenCommit.current
    val openTopic = fr.arthurbrugiere.forgeline.ui.LocalOpenTopic.current
    val openRelease = LocalOpenRelease.current
    val signedIn = session.signedInOn(id.forge)
    var dispatching by rememberSaveable { mutableStateOf(false) }
    val details = state.details
    val picker = hiltViewModel<fr.arthurbrugiere.forgeline.collections.CollectionPickerViewModel, fr.arthurbrugiere.forgeline.collections.CollectionPickerViewModel.Factory>(
        key = "collect/${id.key}",
    ) { it.create(id) }
    val collections by picker.state.collectAsStateWithLifecycle()
    var collecting by rememberSaveable { mutableStateOf(false) }
    if (collecting && details != null) {
        val summary = details.toSummary()
        fr.arthurbrugiere.forgeline.collections.CollectionPickerSheet(
            repoName = details.id.fullName,
            state = collections,
            onToggle = { picker.toggle(it, summary) },
            onCreate = { picker.createWith(it, summary) },
            onDismiss = { collecting = false },
        )
    }
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
        onOpenDiscussion = { number -> state.details?.let { openDiscussion?.invoke(it.id, number) ?: openUrl("${it.id.webUrl}/discussions/$number") } },
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
        onNewPullRequest = { if (signedIn) state.details?.let { onNewPullRequest(it.id) } else onSignIn() },
        onOpenSettings = { state.details?.let { onOpenSettings(it.id) } },
        onOpenHistory = { state.details?.let { details -> onOpenHistory(details.id, state.browsedRef ?: details.defaultBranch, state.code.path.ifEmpty { null }) } },
        onShowOpen = viewModel::showOpen,
        onSearch = viewModel::search,
        onOpenRelease = { tag -> state.details?.let { openRelease?.invoke(it.id, tag) } },
        onOpenRun = { runId -> state.details?.let { onOpenRun(it.id, runId) } },
        onOpenUser = onOpenUser,
        onLinkClick = { url -> openForgeLink(url, id.forge, onOpenRepo, onOpenIssue, onOpenUser, openUrl, onOpenRun, openRelease, openDiscussion, openWiki, openCommit, openTopic) },
        onOpenInBrowser = openUrl,
        onLoadRefs = viewModel::loadRefs,
        onRunWorkflow = if (signedIn) ({ dispatching = true }) else null,
        onWorkflowStartShown = viewModel::workflowStartShown,
        onSelectRef = viewModel::selectRef,
        onErrorShown = viewModel::errorShown,
        onStarFailureShown = viewModel::starFailureShown,
        onCollect = { collecting = true },
        collected = collections.holding.isNotEmpty(),
    )
}

/** What a collection keeps of a repository to show its row. */
private fun fr.arthurbrugiere.forgeline.core.model.RepoDetails.toSummary() =
    fr.arthurbrugiere.forgeline.core.model.RepoSummary(id, description, language, stars, forks, isFork, null, ownerAvatarUrl)

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
    /** Opens the page of the release under a tag. */
    onOpenRelease: (String) -> Unit = {},
    /** Lists the open (true) or the closed issues or pull requests. */
    onShowOpen: (Boolean) -> Unit = {},
    /** Looks for words among the issues or pull requests. */
    onSearch: (String) -> Unit = {},
    /** Opens the history of what the Code tab shows: the ref browsed, and the folder it is in. */
    onOpenHistory: () -> Unit = {},
    /** Opens the repository's settings; only offered to who administers it. */
    onOpenSettings: () -> Unit = {},
    /** Opens the form for a new pull request; null where none can be opened. */
    onNewPullRequest: (() -> Unit)? = null,
    onToggleWatch: () -> Unit = {},
    onOpenDiscussion: (Int) -> Unit = {},
    /** Asks for the next page of the issues or pull requests listed. */
    onLoadMore: () -> Unit = {},
    /** Copies the repository to the reader's account, once they have said yes. */
    onFork: () -> Unit = {},
    onWatchFailureShown: () -> Unit = {},
    onForkErrorShown: () -> Unit = {},
    /** Puts the repository in a collection or out of one; null where collections aren't offered. */
    onCollect: (() -> Unit)? = null,
    /** Whether the repository is in at least one collection: its bookmark is filled. */
    collected: Boolean = false,
    nowMillis: Long = rememberNow(state.issues, state.pulls, state.pinned, state.releases, state.runs),
) {
    // Outside the app shell (previews, tests) the wiki opens on its forge.
    val openWiki = fr.arthurbrugiere.forgeline.ui.LocalOpenWiki.current
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
        SoftConfirmSheet(
            title = stringResource(R.string.repo_fork_title, (details?.id ?: state.requested).name),
            body = stringResource(R.string.repo_fork_body, forge),
            confirm = stringResource(R.string.repo_fork_action),
            cancel = stringResource(R.string.cancel),
            onConfirm = {
                forking = false
                onFork()
            },
            onDismiss = { forking = false },
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
                        onBack = onBack,
                        backDescription = stringResource(R.string.navigate_up),
                        actions = {
                            if (state.canAdminister && details != null) {
                                SoftIconButton(onClick = onOpenSettings) {
                                    Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.repo_settings_title), tint = Soft.colors.ink)
                                }
                            }
                            if (onCollect != null && details != null) {
                                SoftIconButton(onClick = onCollect) {
                                    Icon(
                                        if (collected) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                                        contentDescription = stringResource(R.string.collection_pick),
                                        tint = if (collected) Soft.colors.accent else Soft.colors.ink,
                                    )
                                }
                            }
                            ShareLinkButton(id.webUrl, id.fullName)
                            SoftIconButton(onClick = { onOpenInBrowser(id.webUrl) }) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.OpenInNew,
                                    contentDescription = stringResource(R.string.repo_open_on_forge, id.forge.displayName),
                                    tint = Soft.colors.ink,
                                )
                            }
                        },
                    ) {
                        if (details != null) {
                            RepoHeader(details, state.starred, signedIn, onToggleStar, onLinkClick, onOpenUser, state.watching, onToggleWatch, state.isForking, onFork = { forking = true }, onOpenWiki = { openWiki?.invoke(details.id, null) ?: onOpenInBrowser(details.id.wikiUrl) }, languages = state.languages)
                        } else {
                            Text(id.name, style = Soft.type.title, color = Soft.colors.ink)
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
                                // Issues and pull requests say how many are open, as every forge's own page does.
                                options = state.tabs.map { tab ->
                                    val open = when (tab) {
                                        RepoTab.ISSUES -> details.openIssues
                                        RepoTab.PULLS -> details.openPulls
                                        else -> null
                                    }
                                    stringResource(tab.label) + (open?.let { " ${compactCount(it)}" } ?: "")
                                },
                                selected = state.tabs.indexOf(state.tab).coerceAtLeast(0),
                                onSelect = { onSelectTab(state.tabs[it]) },
                            )
                        }
                        val browsedRef = state.browsedRef
                        // Nothing pushed: no branch to pick, no history, no files; the tabs that would show them say so.
                        val unpushed = details.isEmpty && (state.tab == RepoTab.README || state.tab == RepoTab.CODE)
                        if (browsedRef != null && !unpushed && (state.tab == RepoTab.README || state.tab == RepoTab.CODE)) {
                            item(key = "ref") {
                                RefPill(browsedRef, state.refs, onClick = {
                                    onLoadRefs()
                                    pickingRef = true
                                })
                            }
                        }
                        if (state.tab == RepoTab.CODE && !unpushed) {
                            item(key = "history") {
                                Row(RowModifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                                    SoftTonalButton(stringResource(R.string.history_open), onClick = onOpenHistory)
                                }
                            }
                        }
                        if (unpushed) {
                            item(key = "unpushed") { SoftNotice(stringResource(R.string.repo_unpushed_title), stringResource(R.string.repo_unpushed_body)) }
                        } else when (state.tab) {
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
                                        ReadmeBody(readme.markdown, state.readmeContext, onLinkClick)
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
                                item(key = "pulls-find") {
                                    ListControls(
                                        state.pullQuery, stringResource(R.string.repo_search_pulls), onShowOpen, onSearch,
                                        onNewIssue = onNewPullRequest, newLabel = stringResource(R.string.new_pull_title),
                                    )
                                }
                                loadable(state.pulls, state.pullQuery.emptyMessage(R.string.repo_no_pulls, R.string.repo_no_closed_pulls), onRetryTab) { pulls ->
                                    items(pulls, key = { "pull-${it.number}" }) { IssueSummaryRow(it, nowMillis, { number -> onOpenIssue(number, it.isPullRequest) }) }
                                    more(state.pullPaging, onLoadMore)
                                }
                            }
                            RepoTab.DISCUSSIONS -> if ((state.discussions as? Loadable.Failed)?.error == ForgeError.Unauthorized) {
                                // GitHub only answers an account about discussions.
                                item(key = "discussions-sign-in") {
                                    SoftNotice(
                                        stringResource(R.string.repo_discussions_sign_in_title),
                                        stringResource(R.string.repo_discussions_sign_in_body, id.forge.displayName),
                                        action = stringResource(R.string.repo_open_on_forge, id.forge.displayName),
                                        onAction = { onOpenInBrowser("${id.webUrl}/discussions") },
                                    )
                                }
                            } else {
                                loadable(state.discussions, R.string.repo_no_discussions, onRetryTab) { discussions ->
                                    items(discussions, key = { "discussion-${it.number}" }) { DiscussionRow(it, nowMillis) { onOpenDiscussion(it.number) } }
                                    more(state.discussionPaging, onLoadMore)
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
        SoftSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalBottomBarSpace.current))
    }
}

/** Where a repository's wiki is on its forge's site. */
internal val RepoId.wikiUrl: String get() = if (forge.type == ForgeType.GITLAB) "$webUrl/-/wikis" else "$webUrl/wiki"

/** An archived repository is read-only, and an owner can switch issues off. */
private val RepoDetails.takesIssues: Boolean get() = hasIssues && !isArchived

private val RepoTab.label: Int
    get() = when (this) {
        RepoTab.README -> R.string.repo_tab_readme
        RepoTab.CODE -> R.string.repo_tab_code
        RepoTab.ISSUES -> R.string.repo_tab_issues
        RepoTab.PULLS -> R.string.repo_tab_pulls
        RepoTab.DISCUSSIONS -> R.string.repo_tab_discussions
        RepoTab.RELEASES -> R.string.repo_tab_releases
        RepoTab.ACTIONS -> R.string.repo_tab_actions
    }
