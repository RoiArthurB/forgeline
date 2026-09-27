package fr.arthurbrugiere.forgeline.repo

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Balance
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.SubdirectoryArrowLeft
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
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
import fr.arthurbrugiere.forgeline.navigation.UserRoute
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.session.SessionState
import fr.arthurbrugiere.forgeline.ui.IssueSummaryRow
import fr.arthurbrugiere.forgeline.ui.Avatar
import fr.arthurbrugiere.forgeline.ui.Badge
import fr.arthurbrugiere.forgeline.ui.Message
import fr.arthurbrugiere.forgeline.ui.loadable
import fr.arthurbrugiere.forgeline.ui.message
import fr.arthurbrugiere.forgeline.ui.relative
import fr.arthurbrugiere.forgeline.ui.EmptyState
import fr.arthurbrugiere.forgeline.ui.rememberCustomTabOpener

@Composable
fun RepoRoute(
    route: RepoRoute,
    session: SessionState,
    onBack: () -> Unit,
    onOpenRepo: (RepoId) -> Unit,
    onOpenFile: (RepoId, path: String, ref: String) -> Unit,
    onOpenIssue: (IssueRef) -> Unit,
    onOpenUser: (String) -> Unit,
    onSignIn: () -> Unit,
) {
    val id = RepoId(route.owner, route.name)
    val viewModel = hiltViewModel<RepoViewModel, RepoViewModel.Factory>(key = id.fullName) { it.create(id) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openUrl = rememberCustomTabOpener()
    val signedIn = session is SessionState.SignedIn
    RepoScreen(
        state = state,
        signedIn = signedIn,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onSelectTab = viewModel::selectTab,
        onRetryTab = viewModel::retryTab,
        onToggleStar = { if (signedIn) viewModel.toggleStar() else onSignIn() },
        onOpenDirectory = viewModel::openDirectory,
        onOpenParentDirectory = viewModel::openParentDirectory,
        onOpenFile = { file -> state.details?.let { onOpenFile(it.id, file.path, it.defaultBranch) } },
        onOpenIssue = { number -> state.details?.let { onOpenIssue(IssueRef(it.id, number)) } },
        onOpenUser = onOpenUser,
        onLinkClick = { url ->
            when (val target = ForgeLinks.routeFor(url)) {
                is RepoRoute -> onOpenRepo(RepoId(target.owner, target.name))
                is IssueRoute -> onOpenIssue(IssueRef(RepoId(target.owner, target.name), target.number))
                is UserRoute -> onOpenUser(target.login)
                else -> if (!url.startsWith("#")) openUrl(url)
            }
        },
        onOpenInBrowser = openUrl,
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
    onOpenIssue: (Int) -> Unit,
    onOpenUser: (String) -> Unit,
    onLinkClick: (String) -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onErrorShown: () -> Unit,
    onStarFailureShown: () -> Unit,
    modifier: Modifier = Modifier,
    nowMillis: Long = System.currentTimeMillis(),
) {
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
    LaunchedEffect(state.starFailed) {
        if (state.starFailed) {
            snackbar.showSnackbar(starFailed)
            onStarFailureShown()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text((details?.id ?: state.requested).name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.navigate_up))
                    }
                },
                actions = {
                    IconButton(onClick = { onOpenInBrowser("https://github.com/${(details?.id ?: state.requested).fullName}") }) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.repo_open_on_forge))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                details == null && state.error != null -> EmptyState(
                    icon = Icons.Outlined.CloudOff,
                    title = stringResource(R.string.repo_error_title),
                    body = stringResource(
                        if (state.error is ForgeError.Http && state.error.status == 404) R.string.repo_error_not_found else state.error.message,
                    ),
                    actionLabel = stringResource(R.string.retry),
                    onAction = onRefresh,
                )
                details == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = onRefresh) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                        item(key = "header") { RepoHeader(details, state.starred, signedIn, onToggleStar, onLinkClick, onOpenUser) }
                        stickyHeader(key = "tabs") { RepoTabs(state.tab, onSelectTab) }
                        when (state.tab) {
                            RepoTab.README -> item(key = "readme") {
                                val readme = state.readme
                                if (readme == null || state.readmeContext == null) {
                                    Message(stringResource(R.string.repo_no_readme))
                                } else {
                                    Readme(readme.markdown, state.readmeContext, onLinkClick)
                                }
                            }
                            RepoTab.CODE -> code(state.code, onRetryTab, onOpenDirectory, onOpenParentDirectory, onOpenFile)
                            RepoTab.ISSUES -> loadable(state.issues, R.string.repo_no_issues, onRetryTab) { issues ->
                                items(issues, key = { "issue-${it.number}" }) { IssueSummaryRow(it, nowMillis, onOpenIssue) }
                            }
                            RepoTab.PULLS -> loadable(state.pulls, R.string.repo_no_pulls, onRetryTab) { pulls ->
                                items(pulls, key = { "pull-${it.number}" }) { IssueSummaryRow(it, nowMillis, onOpenIssue) }
                            }
                            RepoTab.RELEASES -> loadable(state.releases, R.string.repo_no_releases, onRetryTab) { releases ->
                                items(releases, key = { "release-${it.tag}" }) { ReleaseRow(it, state.readmeContext, nowMillis, onLinkClick) }
                            }
                            RepoTab.ACTIONS -> loadable(state.runs, R.string.repo_no_runs, onRetryTab) { runs ->
                                items(runs, key = { "run-${it.id}" }) { RunRow(it, nowMillis) }
                            }
                        }
                    }
                }
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
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.clickable { onOpenUser(details.id.owner) },
        ) {
            Avatar(details.ownerAvatarUrl, details.id.owner, size = 24.dp)
            Text(details.id.owner, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(details.id.name, style = MaterialTheme.typography.headlineSmall)
        details.description?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        details.homepage?.let { url ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    url.removePrefix("https://").removePrefix("http://").trimEnd('/'),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onLinkClick(url) },
                )
            }
        }
        if (details.topics.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                details.topics.forEach { SuggestionChip(onClick = {}, label = { Text(it) }) }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat(Icons.Outlined.StarBorder, compactCount(details.stars))
            Stat(Icons.AutoMirrored.Outlined.CallSplit, compactCount(details.forks))
            Stat(Icons.Outlined.Visibility, stringResource(R.string.repo_watchers, compactCount(details.watchers)))
            details.language?.let { Stat(null, it) }
            details.license?.let { Stat(Icons.Outlined.Balance, it) }
            if (details.isArchived) Badge(stringResource(R.string.repo_archived))
            if (details.isFork) Badge(stringResource(R.string.repo_fork))
        }
        val isStarred = starred == true && signedIn
        FilledTonalButton(onClick = onToggleStar) {
            Icon(if (isStarred) Icons.Filled.Star else Icons.Outlined.StarBorder, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                stringResource(if (isStarred) R.string.repo_starred else R.string.repo_star),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun RepoTabs(selected: RepoTab, onSelect: (RepoTab) -> Unit) {
    PrimaryScrollableTabRow(
        selectedTabIndex = selected.ordinal,
        containerColor = MaterialTheme.colorScheme.surface,
        edgePadding = 8.dp,
    ) {
        RepoTab.entries.forEach { tab ->
            Tab(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                text = { Text(stringResource(tab.label)) },
                selectedContentColor = MaterialTheme.colorScheme.primary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Readme(markdown: String, context: ReadmeContext, onLinkClick: (String) -> Unit) {
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val parsed = rememberReadmeState(markdown, context, darkTheme)
    if (parsed == null) {
        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        ForgelineMarkdown(parsed, onLinkClick, Modifier.padding(16.dp))
    }
}

private fun LazyListScope.code(
    code: CodeState,
    onRetry: () -> Unit,
    onOpenDirectory: (String) -> Unit,
    onOpenParentDirectory: () -> Unit,
    onOpenFile: (RepoFile) -> Unit,
) {
    if (code.path.isNotEmpty()) {
        item(key = "code-path") {
            ListItem(
                headlineContent = { Text(stringResource(R.string.repo_parent_folder)) },
                supportingContent = { Text(code.path) },
                leadingContent = { Icon(Icons.Outlined.SubdirectoryArrowLeft, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onOpenParentDirectory),
            )
        }
    }
    loadable(code.entries, R.string.repo_empty_folder, onRetry) { entries ->
        items(entries, key = { "file-${it.path}" }) { file ->
            ListItem(
                headlineContent = { Text(file.name) },
                leadingContent = {
                    Icon(
                        when (file.type) {
                            RepoFileType.DIR -> Icons.Outlined.Folder
                            RepoFileType.SUBMODULE, RepoFileType.SYMLINK -> Icons.Outlined.Link
                            RepoFileType.FILE -> Icons.AutoMirrored.Outlined.InsertDriveFile
                        },
                        contentDescription = null,
                        tint = if (file.type == RepoFileType.DIR) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                modifier = Modifier.clickable {
                    when (file.type) {
                        RepoFileType.DIR -> onOpenDirectory(file.path)
                        RepoFileType.FILE -> onOpenFile(file)
                        RepoFileType.SYMLINK, RepoFileType.SUBMODULE -> Unit
                    }
                },
            )
        }
    }
}

@Composable
private fun ReleaseRow(release: Release, context: ReadmeContext?, nowMillis: Long, onLinkClick: (String) -> Unit) {
    var expanded by rememberSaveable(release.tag) { mutableStateOf(false) }
    Column(Modifier.clickable { expanded = !expanded }) {
        ListItem(
            leadingContent = { Icon(Icons.Outlined.NewReleases, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            headlineContent = { Text(release.name ?: release.tag) },
            supportingContent = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(listOfNotNull(release.tag.takeIf { release.name != null }, release.publishedAt?.let { relative(it, nowMillis) }).joinToString(" · "))
                    if (release.isPrerelease) Badge(stringResource(R.string.repo_prerelease))
                }
            },
        )
        val body = release.body
        if (expanded && body != null && context != null) {
            val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
            rememberReadmeState(body, context, darkTheme)?.let { ForgelineMarkdown(it, onLinkClick, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        }
        HorizontalDivider()
    }
}

@Composable
private fun RunRow(run: WorkflowRun, nowMillis: Long) {
    ListItem(
        leadingContent = { RunStatusIcon(run) },
        headlineContent = { Text(run.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                listOfNotNull(
                    stringResource(R.string.repo_run_meta, run.workflowName, run.branch ?: run.event, run.runNumber),
                    relative(run.createdAt, nowMillis),
                ).joinToString(" · "),
            )
        },
    )
    HorizontalDivider()
}

@Composable
private fun RunStatusIcon(run: WorkflowRun) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    when {
        run.status == RunStatus.IN_PROGRESS -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        run.status == RunStatus.QUEUED -> Icon(Icons.Outlined.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        run.conclusion == RunConclusion.SUCCESS -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = if (dark) Color(0xFF3FB950) else Color(0xFF1A7F37))
        run.conclusion == RunConclusion.FAILURE || run.conclusion == RunConclusion.TIMED_OUT ->
            Icon(Icons.Filled.Cancel, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        else -> Icon(Icons.Outlined.Block, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Stat(icon: ImageVector?, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
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