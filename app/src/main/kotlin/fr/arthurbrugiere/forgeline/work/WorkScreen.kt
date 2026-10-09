package fr.arthurbrugiere.forgeline.work

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
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
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.WorkKind
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSectionTitle
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftStatusBarScrim
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.ui.IssueSummaryRow
import fr.arthurbrugiere.forgeline.ui.LocalBottomBarSpace
import fr.arthurbrugiere.forgeline.ui.SayOnce
import fr.arthurbrugiere.forgeline.ui.listBottomPadding
import fr.arthurbrugiere.forgeline.ui.message
import fr.arthurbrugiere.forgeline.ui.sideSafeArea

@Composable
fun WorkRoute(onBack: () -> Unit, onOpenIssue: (IssueRef) -> Unit) {
    val viewModel = hiltViewModel<WorkViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    WorkScreen(state, onBack = onBack, onRefresh = viewModel::refresh, onOpenIssue = onOpenIssue, onErrorShown = viewModel::errorShown)
}

/**
 * What waits on the people signed in, across their accounts: the reviews asked of them first (someone else is
 * waiting), then their own open pull requests, then what is assigned to them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkScreen(
    state: WorkUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenIssue: (IssueRef) -> Unit,
    onErrorShown: () -> Unit,
    modifier: Modifier = Modifier,
    nowMillis: Long = System.currentTimeMillis(),
) {
    val colors = Soft.colors
    val work = state.work
    val snackbar = remember { SnackbarHostState() }
    SayOnce(stringResource(R.string.trending_refresh_failed).takeIf { state.error != null && work != null }, snackbar, onErrorShown)
    val listState = rememberLazyListState()
    Box(modifier.fillMaxSize().background(colors.ground)) {
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = state.isLoading && work != null,
            onRefresh = onRefresh,
            state = pullState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = state.isLoading && work != null,
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
                        tint = colors.fields[2],
                        title = stringResource(R.string.work_title),
                        onBack = onBack,
                        backDescription = stringResource(R.string.navigate_up),
                    )
                }
                when {
                    work == null && state.error != null -> item(key = "error") {
                        SoftNotice(
                            stringResource(R.string.work_error_title),
                            stringResource(state.error.message),
                            action = stringResource(R.string.retry),
                            onAction = onRefresh,
                        )
                    }
                    work == null -> item(key = "loading") { SoftLoadingRows(stringResource(R.string.work_loading), rows = 4) }
                    else -> {
                        if (work.isEmpty && work.failed.isEmpty()) {
                            item(key = "empty") { SoftNotice(stringResource(R.string.work_empty_title), stringResource(R.string.work_empty_body)) }
                        }
                        WorkKind.entries.forEach { kind ->
                            val items = work.sections[kind].orEmpty()
                            if (items.isEmpty()) return@forEach
                            item(key = "title-$kind", contentType = "title") { SoftSectionTitle(stringResource(kind.title)) }
                            // Keyed by position: one conversation can be under two headings.
                            itemsIndexed(items, key = { index, _ -> "$kind-$index" }, contentType = { _, _ -> "row" }) { _, item ->
                                IssueSummaryRow(
                                    item.issue,
                                    nowMillis,
                                    onOpen = { number -> onOpenIssue(IssueRef(item.repo, number, item.issue.isPullRequest)) },
                                    repo = item.repo,
                                    // Which forge, once work comes from more than one.
                                    forge = item.repo.forge.takeIf { work.forges.size > 1 },
                                )
                            }
                        }
                        // A forge that couldn't be asked is said, so its silence isn't read as nothing to do.
                        if (work.failed.isNotEmpty()) {
                            item(key = "failed") {
                                Text(
                                    stringResource(R.string.work_failed, work.failed.joinToString { it.displayName }),
                                    style = Soft.type.secondary,
                                    color = colors.inkMuted,
                                    modifier = Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
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

private val WorkKind.title: Int
    get() = when (this) {
        WorkKind.REVIEW_REQUESTED -> R.string.work_reviews
        WorkKind.OWN_PULL_REQUESTS -> R.string.work_own
        WorkKind.ASSIGNED -> R.string.work_assigned
    }
