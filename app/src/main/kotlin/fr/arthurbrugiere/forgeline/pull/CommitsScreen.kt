package fr.arthurbrugiere.forgeline.pull

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.model.Commit
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftStatusBarScrim
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.softPressable
import fr.arthurbrugiere.forgeline.navigation.CommitsRoute
import fr.arthurbrugiere.forgeline.ui.Avatar
import fr.arthurbrugiere.forgeline.ui.SayOnce
import fr.arthurbrugiere.forgeline.ui.message
import fr.arthurbrugiere.forgeline.ui.rememberNow
import fr.arthurbrugiere.forgeline.ui.sideSafeArea

@Composable
fun CommitsRoute(route: CommitsRoute, onBack: () -> Unit, onOpenCommit: (String) -> Unit) {
    val target = route.target
    val viewModel = hiltViewModel<CommitsViewModel, CommitsViewModel.Factory>(key = "commits-$route") { it.create(target) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    CommitsScreen(state, onBack = onBack, onRefresh = viewModel::refresh, onLoadMore = viewModel::loadMore, onErrorShown = viewModel::errorShown, onOpenCommit = onOpenCommit)
}

/** The commits of a pull request, or a history: of a repository from a ref, or of one file. A commit opens on what it changed. */
@Composable
fun CommitsScreen(
    state: CommitsUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onErrorShown: () -> Unit,
    onOpenCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    nowMillis: Long = rememberNow(state.commits),
) {
    val colors = Soft.colors
    val commits = state.commits
    val snackbar = remember { SnackbarHostState() }
    SayOnce(stringResource(R.string.changes_more_failed).takeIf { state.error != null && commits != null }, snackbar, onErrorShown)
    val listState = rememberLazyListState()
    Box(modifier.fillMaxSize().background(colors.ground)) {
        LazyColumn(
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxSize().sideSafeArea().navigationBarsPadding(),
        ) {
            item(key = "header") {
                val target = state.target
                SoftHeader(
                    tint = colors.fields[1],
                    title = stringResource(if (target is CommitsTarget.Pull) R.string.commits_title else R.string.history_title),
                    onBack = onBack,
                    backDescription = stringResource(R.string.navigate_up),
                    content = {
                        val about = when (target) {
                            is CommitsTarget.Pull -> target.ref.label()
                            // What the history is of, then where it is read from.
                            is CommitsTarget.History -> listOfNotNull(target.path ?: target.repo.fullName, target.ref).joinToString(" · ")
                        }
                        Text(about, style = Soft.type.secondary, color = colors.inkMuted)
                    },
                )
            }
            when {
                commits == null && state.error != null -> item(key = "error") {
                    SoftNotice(stringResource(R.string.commits_error_title), stringResource(state.error.message), action = stringResource(R.string.retry), onAction = onRefresh)
                }
                commits == null -> item(key = "loading") { SoftLoadingRows(stringResource(R.string.commits_loading), rows = 5) }
                commits.isEmpty() -> item(key = "empty") { SoftNotice(stringResource(R.string.commits_empty_title), stringResource(R.string.commits_empty_body)) }
                else -> {
                    items(commits, key = { it.sha }, contentType = { "commit" }) { commit -> CommitRow(commit, nowMillis, onClick = { onOpenCommit(commit.sha) }) }
                    if (state.nextPage != null) {
                        item(key = "more") {
                            LaunchedEffect(state.nextPage) { onLoadMore() }
                            SoftLoadingRows(stringResource(R.string.commits_loading), rows = 1)
                        }
                    }
                }
            }
        }
        val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
        SoftStatusBarScrim(scrolled)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) { data ->
            Snackbar(data, shape = RoundedCornerShape(16.dp), containerColor = colors.ink, contentColor = colors.ground)
        }
    }
}

@Composable
internal fun CommitRow(commit: Commit, nowMillis: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Soft.colors
    Row(
        modifier
            .widthIn(max = SoftTokens.MaxReadingWidth)
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .softPressable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Avatar(commit.author?.avatarUrl, commit.author?.login ?: commit.authorName ?: "?", size = 32.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(commit.title, style = Soft.type.body, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(commit.byline(nowMillis), style = Soft.type.meta, color = colors.inkMuted)
        }
    }
}
