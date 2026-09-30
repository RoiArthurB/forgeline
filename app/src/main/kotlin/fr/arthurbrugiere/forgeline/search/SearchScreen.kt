package fr.arthurbrugiere.forgeline.search

import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import fr.arthurbrugiere.forgeline.ui.IssueSummaryRow
import fr.arthurbrugiere.forgeline.ui.RepoSummaryRow
import fr.arthurbrugiere.forgeline.ui.message
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.sp
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftStatusBarScrim
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSwitch
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTextField
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTonalButton
import fr.arthurbrugiere.forgeline.core.ui.soft.softPressable
import fr.arthurbrugiere.forgeline.ui.listBottomPadding
import androidx.compose.runtime.getValue

@Composable
fun SearchRoute(
    onOpenRepo: (RepoId) -> Unit,
    onOpenIssue: (IssueRef) -> Unit,
    onOpenUser: (ForgeInstance, String) -> Unit,
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
    onOpenUser: (ForgeInstance, String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    nowMillis: Long = System.currentTimeMillis(),
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    // A fresh search starts with the keyboard up; coming back to results doesn't.
    LaunchedEffect(Unit) { if (!state.results.submitted && state.query.isEmpty()) focus.requestFocus() }

    val colors = Soft.colors
    val results = state.results
    val listState = rememberLazyListState()
    LaunchedEffect(listState, results.hasMore) {
        if (!results.hasMore) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .distinctUntilChanged()
            .collect { last -> if (last != null && last >= listState.layoutInfo.totalItemsCount - 5) onLoadMore() }
    }
    Box(modifier.fillMaxSize().background(colors.ground)) {
        LazyColumn(
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(bottom = listBottomPadding()),
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
        ) {
            item(key = "header") {
                SoftHeader(tint = colors.fields[1], onBack = onBack, backDescription = stringResource(R.string.navigate_up)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SearchField(
                            query = state.query,
                            onQueryChange = onQueryChange,
                            onSubmit = {
                                keyboard?.hide()
                                onSubmit()
                            },
                            onClear = {
                                onQueryChange("")
                                focus.requestFocus()
                            },
                            focus = focus,
                        )
                        SoftSwitch(
                            options = SearchScope.entries.map { stringResource(it.label) },
                            selected = state.scope.ordinal,
                            onSelect = { onSelectScope(SearchScope.entries[it]) },
                        )
                    }
                }
            }
            when {
                !results.submitted -> item(key = "intro") {
                    SoftNotice(stringResource(R.string.search_intro_title), stringResource(R.string.search_intro_body))
                }
                results.items.isEmpty() && results.isLoading -> item(key = "loading") {
                    SoftLoadingRows(stringResource(R.string.search_loading), rows = 4)
                }
                results.items.isEmpty() && results.error != null -> item(key = "error") {
                    SoftNotice(
                        stringResource(R.string.search_error_title),
                        stringResource(results.error.message),
                        action = stringResource(R.string.retry),
                        onAction = onRetry,
                    )
                }
                results.items.isEmpty() -> item(key = "empty") {
                    SoftNotice(stringResource(R.string.search_empty_title), stringResource(R.string.search_empty_body, results.query.orEmpty()))
                }
                else -> {
                    results.totalCount?.let { total ->
                        item(key = "count") {
                            Text(
                                pluralStringResource(R.plurals.search_results, total, compactCount(total)),
                                style = Soft.type.secondary,
                                color = colors.inkMuted,
                                modifier = Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                            )
                        }
                    }
                    // Keyed by position: GitHub search pages can repeat an item across pages.
                    itemsIndexed(results.items, key = { index, _ -> index }, contentType = { _, item -> item::class }) { _, item ->
                        // Which forge, once more than one is searched.
                        fun ForgeInstance.named() = displayName.takeIf { results.showForge }
                        when (item) {
                            is SearchResult.Repository -> RepoSummaryRow(item.repo, onOpenRepo, forge = item.repo.id.forge.named())
                            is SearchResult.Issue -> IssueSummaryRow(
                                item.result.issue,
                                nowMillis,
                                onOpen = { number -> onOpenIssue(IssueRef(item.result.repo, number)) },
                                repo = item.result.repo,
                                forge = item.result.repo.forge.named(),
                            )
                            is SearchResult.User -> UserRow(item.user, item.user.forge.named(), onOpenUser)
                        }
                    }
                    when {
                        results.error != null -> item(key = "retry") {
                            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                SoftTonalButton(stringResource(R.string.retry), onRetry)
                            }
                        }
                        results.hasMore -> item(key = "more") {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = colors.accent, trackColor = colors.surface)
                            }
                        }
                    }
                }
            }
        }
        val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
        SoftStatusBarScrim(scrolled)
    }
}

/** The query in the one text field, on the ground over the lilac field: a search glyph and a clear button. */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, onSubmit: () -> Unit, onClear: () -> Unit, focus: FocusRequester) {
    val colors = Soft.colors
    SoftTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = stringResource(R.string.search_hint),
        background = colors.ground,
        leading = { Icon(Icons.Outlined.Search, contentDescription = null, tint = colors.inkMuted) },
        trailing = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.search_clear), tint = colors.inkMuted)
                }
            } else {
                Spacer(Modifier.height(48.dp))
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
}

@Composable
private fun UserRow(user: UserSummary, forge: String?, onOpenUser: (ForgeInstance, String) -> Unit) {
    val colors = Soft.colors
    Row(
        Modifier
            .widthIn(max = SoftTokens.MaxReadingWidth)
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .softPressable { onOpenUser(user.forge, user.login) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(user.avatarUrl, user.login, size = 40.dp, placeholderColor = colors.surface, placeholderContentColor = colors.inkMuted)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(user.login, style = Soft.type.body, color = colors.ink)
            val organization = stringResource(R.string.search_organization).takeIf { user.isOrganization }
            listOfNotNull(organization, forge).takeIf { it.isNotEmpty() }?.let {
                Text(it.joinToString(" · "), style = Soft.type.meta, color = colors.inkMuted)
            }
        }
    }
}

private val SearchScope.label: Int
    get() = when (this) {
        SearchScope.REPOSITORIES -> R.string.search_scope_repositories
        SearchScope.ISSUES -> R.string.search_scope_issues
        SearchScope.USERS -> R.string.search_scope_users
    }
