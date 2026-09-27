package fr.arthurbrugiere.forgeline.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.SearchScope
import fr.arthurbrugiere.forgeline.core.model.UserSummary
import fr.arthurbrugiere.forgeline.core.ui.format.compactCount
import fr.arthurbrugiere.forgeline.ui.Avatar
import fr.arthurbrugiere.forgeline.ui.EmptyState
import fr.arthurbrugiere.forgeline.ui.IssueSummaryRow
import fr.arthurbrugiere.forgeline.ui.RepoSummaryRow
import fr.arthurbrugiere.forgeline.ui.message
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun SearchRoute(
    onOpenRepo: (RepoId) -> Unit,
    onOpenIssue: (IssueRef) -> Unit,
    onOpenUser: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SearchScreen(
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onSubmit = viewModel::submit,
        onSelectScope = viewModel::selectScope,
        onLoadMore = viewModel::loadMore,
        onRetry = viewModel::retry,
        onOpenRepo = onOpenRepo,
        onOpenIssue = onOpenIssue,
        onOpenUser = onOpenUser,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSelectScope: (SearchScope) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onOpenRepo: (RepoId) -> Unit,
    onOpenIssue: (IssueRef) -> Unit,
    onOpenUser: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    nowMillis: Long = System.currentTimeMillis(),
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    // A fresh search starts with the keyboard up; coming back to results doesn't.
    LaunchedEffect(Unit) { if (!state.results.submitted && state.query.isEmpty()) focus.requestFocus() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.navigate_up))
                    }
                },
                title = {
                    TextField(
                        value = state.query,
                        onValueChange = onQueryChange,
                        placeholder = { Text(stringResource(R.string.search_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            keyboard?.hide()
                            onSubmit()
                        }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
                actions = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = {
                            onQueryChange("")
                            focus.requestFocus()
                        }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.search_clear))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = state.scope.ordinal) {
                SearchScope.entries.forEach { scope ->
                    Tab(
                        selected = scope == state.scope,
                        onClick = { onSelectScope(scope) },
                        text = { Text(stringResource(scope.label)) },
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val results = state.results
            when {
                !results.submitted -> EmptyState(
                    icon = Icons.Outlined.Search,
                    title = stringResource(R.string.search_intro_title),
                    body = stringResource(R.string.search_intro_body),
                )
                results.items.isEmpty() && results.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                results.items.isEmpty() && results.error != null -> EmptyState(
                    icon = Icons.Outlined.CloudOff,
                    title = stringResource(R.string.search_error_title),
                    body = stringResource(results.error.message),
                    actionLabel = stringResource(R.string.retry),
                    onAction = onRetry,
                )
                results.items.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.SearchOff,
                    title = stringResource(R.string.search_empty_title),
                    body = stringResource(R.string.search_empty_body, results.query.orEmpty()),
                )
                else -> ResultList(results, onLoadMore, onRetry, onOpenRepo, onOpenIssue, onOpenUser, nowMillis)
            }
        }
    }
}

@Composable
private fun ResultList(
    results: ScopeResults,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onOpenRepo: (RepoId) -> Unit,
    onOpenIssue: (IssueRef) -> Unit,
    onOpenUser: (String) -> Unit,
    nowMillis: Long,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(listState, results.hasMore) {
        if (!results.hasMore) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .distinctUntilChanged()
            .collect { last -> if (last != null && last >= listState.layoutInfo.totalItemsCount - 5) onLoadMore() }
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        results.totalCount?.let { total ->
            item(key = "count") {
                Text(
                    pluralStringResource(R.plurals.search_results, total, compactCount(total)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
        // Keyed by position: GitHub search pages can repeat an item across pages.
        itemsIndexed(results.items, key = { index, _ -> index }, contentType = { _, item -> item::class }) { _, item ->
            when (item) {
                is SearchResult.Repository -> RepoSummaryRow(item.repo, onOpenRepo)
                is SearchResult.Issue -> IssueSummaryRow(
                    item.result.issue,
                    nowMillis,
                    onOpen = { number -> onOpenIssue(IssueRef(item.result.repo, number)) },
                    repo = item.result.repo,
                )
                is SearchResult.User -> UserRow(item.user, onOpenUser)
            }
        }
        when {
            results.error != null -> item(key = "retry") {
                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                }
            }
            results.hasMore -> item(key = "more") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
}

@Composable
private fun UserRow(user: UserSummary, onOpenUser: (String) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onOpenUser(user.login) },
        leadingContent = { Avatar(user.avatarUrl, user.login, size = 40.dp) },
        headlineContent = { Text(user.login) },
        supportingContent = if (user.isOrganization) {
            { Text(stringResource(R.string.search_organization)) }
        } else {
            null
        },
    )
    HorizontalDivider()
}

private val SearchScope.label: Int
    get() = when (this) {
        SearchScope.REPOSITORIES -> R.string.search_scope_repositories
        SearchScope.ISSUES -> R.string.search_scope_issues
        SearchScope.USERS -> R.string.search_scope_users
    }
