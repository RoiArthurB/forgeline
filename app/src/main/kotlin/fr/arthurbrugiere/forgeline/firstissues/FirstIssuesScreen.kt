package fr.arthurbrugiere.forgeline.firstissues

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VolunteerActivism
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.model.FirstIssueSource
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSnackbarHost
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftStatusBarScrim
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSwitch
import fr.arthurbrugiere.forgeline.ui.IssueSummaryRow
import fr.arthurbrugiere.forgeline.ui.LocalBottomBarSpace
import fr.arthurbrugiere.forgeline.ui.listBottomPadding
import fr.arthurbrugiere.forgeline.ui.message
import fr.arthurbrugiere.forgeline.ui.rememberNow
import fr.arthurbrugiere.forgeline.ui.sideSafeArea

@Composable
fun FirstIssuesRoute(onBack: () -> Unit, onOpenIssue: (IssueRef) -> Unit, viewModel: FirstIssuesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    FirstIssuesScreen(
        state = state,
        onBack = onBack,
        onOpenIssue = onOpenIssue,
        onSelectSource = viewModel::selectSource,
        onRefresh = { viewModel.refresh() },
        onErrorShown = viewModel::errorShown,
    )
}

/**
 * Open issues their maintainers marked for a first contribution, in the repositories one starred lately and in
 * today's Trending, the newest first: a way in to a project, not just a look at it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirstIssuesScreen(
    state: FirstIssuesUiState,
    onBack: () -> Unit,
    onOpenIssue: (IssueRef) -> Unit,
    onSelectSource: (FirstIssueSource?) -> Unit,
    onRefresh: () -> Unit,
    onErrorShown: () -> Unit,
    modifier: Modifier = Modifier,
    nowMillis: Long = rememberNow(state.fetchedAtMillis),
) {
    val colors = Soft.colors
    val snackbar = remember { SnackbarHostState() }
    val refreshFailed = stringResource(R.string.first_issues_refresh_failed)
    val shown = state.shown
    // With issues on screen, a failed refresh is a word in passing; without, the notice below says it.
    LaunchedEffect(state.error, shown.isNotEmpty()) {
        if (state.error != null && shown.isNotEmpty()) {
            snackbar.showSnackbar(refreshFailed)
            onErrorShown()
        }
    }
    val listState = rememberLazyListState()
    Box(modifier.fillMaxSize().background(colors.ground)) {
        val pullState = rememberPullToRefreshState()
        val refreshing = state.isRefreshing && state.all != null
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            state = pullState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = refreshing,
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
                    SoftHeader(onBack = onBack, backDescription = stringResource(R.string.navigate_up)) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.first_issues_title), style = Soft.type.hero, color = Soft.colors.ink)
                            Text(stringResource(R.string.first_issues_summary), style = Soft.type.secondary, color = Soft.colors.inkMuted)
                            if (state.hasBothSources) {
                                val sources = listOf(null, FirstIssueSource.STARRED, FirstIssueSource.TRENDING)
                                SoftSwitch(
                                    options = listOf(stringResource(R.string.first_issues_all), stringResource(R.string.first_issues_starred), stringResource(R.string.first_issues_trending)),
                                    selected = sources.indexOf(state.source),
                                    onSelect = { onSelectSource(sources[it]) },
                                )
                            }
                        }
                    }
                }
                when {
                    state.all == null && state.error == null -> item(key = "loading") { SoftLoadingRows(stringResource(R.string.first_issues_loading), rows = 6) }
                    shown.isEmpty() && state.error != null -> item(key = "error") {
                        SoftNotice(stringResource(R.string.first_issues_error_title), stringResource(state.error.message), action = stringResource(R.string.retry), onAction = onRefresh)
                    }
                    shown.isEmpty() -> item(key = "empty") {
                        SoftNotice(stringResource(R.string.first_issues_empty_title), stringResource(R.string.first_issues_empty_body), icon = Icons.Outlined.VolunteerActivism)
                    }
                    else -> items(shown, key = { "${it.repo.key}#${it.issue.number}" }) { found ->
                        IssueSummaryRow(
                            found.issue,
                            nowMillis,
                            onOpen = { number -> onOpenIssue(IssueRef(found.repo, number, isPullRequest = false)) },
                            repo = found.repo,
                            forge = found.repo.forge.takeIf { state.showForge },
                        )
                    }
                }
            }
        }
        val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
        SoftStatusBarScrim(scrolled)
        SoftSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalBottomBarSpace.current))
    }
}
