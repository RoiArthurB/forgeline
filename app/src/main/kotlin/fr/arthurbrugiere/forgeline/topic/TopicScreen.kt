package fr.arthurbrugiere.forgeline.topic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.ui.format.ForgeIcon
import fr.arthurbrugiere.forgeline.core.ui.format.compactCount
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftChoicePill
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftStatusBarScrim
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTonalButton
import fr.arthurbrugiere.forgeline.navigation.TopicRoute
import fr.arthurbrugiere.forgeline.ui.RepoSummaryRow
import fr.arthurbrugiere.forgeline.ui.listBottomPadding
import fr.arthurbrugiere.forgeline.ui.message
import fr.arthurbrugiere.forgeline.ui.sideSafeArea
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun TopicRoute(route: TopicRoute, onBack: () -> Unit, onOpenRepo: (RepoId) -> Unit) {
    val viewModel = hiltViewModel<TopicViewModel, TopicViewModel.Factory>(key = "topic/${route.topic}") { it.create(route.topic) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    TopicScreen(
        state = state,
        onBack = onBack,
        onOpenRepo = onOpenRepo,
        onSelectForge = viewModel::selectForge,
        onLoadMore = viewModel::loadMore,
        onRetry = viewModel::retry,
    )
}

/** A topic: the repositories tagged with it on every forge, the most starred of each first. */
@Composable
fun TopicScreen(
    state: TopicUiState,
    onBack: () -> Unit,
    onOpenRepo: (RepoId) -> Unit,
    onSelectForge: (ForgeInstance?) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Soft.colors
    val listState = rememberLazyListState()
    LaunchedEffect(listState, state.hasMore) {
        if (!state.hasMore) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .distinctUntilChanged()
            .collect { last -> if (last != null && last >= listState.layoutInfo.totalItemsCount - 5) onLoadMore() }
    }
    Box(modifier.fillMaxSize().background(colors.ground)) {
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
                        if (state.forges.size > 1) {
                            SoftChoicePill(
                                name = stringResource(R.string.choice_forge),
                                options = listOf(stringResource(R.string.search_all_forges)) + state.forges.map { it.displayName },
                                selected = state.forges.indexOf(state.onlyForge) + 1,
                                onSelect = { onSelectForge(state.forges.getOrNull(it - 1)) },
                                leading = { index, color -> state.forges.getOrNull(index - 1)?.let { ForgeIcon(it, size = 18.dp, tint = color, contentDescription = null) } },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                    },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(state.topic, style = Soft.type.hero, color = Soft.colors.ink, modifier = Modifier.semantics { heading() })
                        Text(
                            state.totalCount?.let { pluralStringResource(R.plurals.topic_repositories, it, compactCount(it)) } ?: stringResource(R.string.topic_kicker),
                            style = Soft.type.secondary,
                            color = Soft.colors.inkMuted,
                        )
                    }
                }
            }
            when {
                state.repos.isEmpty() && state.isLoading -> item(key = "loading") { SoftLoadingRows(stringResource(R.string.topic_loading), rows = 6) }
                state.repos.isEmpty() && state.error != null -> item(key = "error") {
                    SoftNotice(stringResource(R.string.topic_error_title), stringResource(state.error.message), action = stringResource(R.string.retry), onAction = onRetry)
                }
                state.repos.isEmpty() -> item(key = "empty") {
                    SoftNotice(stringResource(R.string.topic_empty_title), stringResource(R.string.topic_empty_body, state.topic), icon = Icons.Outlined.SearchOff)
                }
                else -> {
                    items(state.repos, key = { it.id.key }) { repo ->
                        RepoSummaryRow(repo, onOpenRepo, forge = repo.id.forge.takeIf { state.showForge })
                    }
                    when {
                        state.error != null -> item(key = "retry") {
                            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { SoftTonalButton(stringResource(R.string.retry), onRetry) }
                        }
                        state.hasMore -> item(key = "more") {
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
