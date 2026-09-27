package fr.arthurbrugiere.forgeline.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.model.FeedAction
import fr.arthurbrugiere.forgeline.core.model.IssueAction
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.PullRequestAction
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewState
import fr.arthurbrugiere.forgeline.session.SessionState
import fr.arthurbrugiere.forgeline.ui.Avatar
import fr.arthurbrugiere.forgeline.ui.EmptyState
import fr.arthurbrugiere.forgeline.ui.TopLevelScreen
import fr.arthurbrugiere.forgeline.ui.message
import fr.arthurbrugiere.forgeline.ui.relative
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun FeedRoute(
    session: SessionState,
    onSignIn: () -> Unit,
    onOpenRepo: (RepoId) -> Unit,
    onOpenIssue: (IssueRef) -> Unit,
    onOpenUser: (String) -> Unit,
) {
    if (session !is SessionState.SignedIn) {
        TopLevelScreen(title = stringResource(R.string.tab_feed)) { padding ->
            if (session == SessionState.SignedOut) {
                EmptyState(
                    icon = Icons.Outlined.DynamicFeed,
                    title = stringResource(R.string.feed_signed_out_title),
                    body = stringResource(R.string.feed_signed_out_body),
                    actionLabel = stringResource(R.string.sign_in),
                    onAction = onSignIn,
                    modifier = Modifier.padding(padding),
                )
            }
        }
        return
    }
    // Keyed by account so switching accounts never shows the previous Feed.
    val viewModel = hiltViewModel<FeedViewModel>(key = session.account.id)
    val state by viewModel.state.collectAsStateWithLifecycle()
    FeedScreen(
        state = state,
        onRefresh = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        onOpenRepo = onOpenRepo,
        onOpenIssue = onOpenIssue,
        onOpenUser = onOpenUser,
        onErrorShown = viewModel::errorShown,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    state: FeedUiState,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenRepo: (RepoId) -> Unit,
    onOpenIssue: (IssueRef) -> Unit,
    onOpenUser: (String) -> Unit,
    onErrorShown: () -> Unit,
    modifier: Modifier = Modifier,
    nowMillis: Long = System.currentTimeMillis(),
) {
    val snackbar = remember { SnackbarHostState() }
    val refreshFailed = stringResource(R.string.trending_refresh_failed)
    val hasItems = state.items.isNotEmpty()
    LaunchedEffect(state.error, hasItems) {
        if (state.error != null && hasItems) {
            snackbar.showSnackbar(refreshFailed)
            onErrorShown()
        }
    }

    TopLevelScreen(title = stringResource(R.string.tab_feed), modifier = modifier, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            when {
                !hasItems && state.error != null -> EmptyState(
                    icon = Icons.Outlined.CloudOff,
                    title = stringResource(R.string.feed_error_title),
                    body = stringResource(state.error.message),
                    actionLabel = stringResource(R.string.retry),
                    onAction = onRefresh,
                )
                // Nothing loaded yet: the pull-to-refresh indicator shows progress.
                !hasItems && state.syncedAtMillis == null -> Box(Modifier.fillMaxSize())
                !hasItems -> EmptyState(
                    icon = Icons.Outlined.DynamicFeed,
                    title = stringResource(R.string.feed_empty_title),
                    body = stringResource(R.string.feed_empty_body),
                )
                else -> FeedList(state, onLoadMore, onOpenRepo, onOpenIssue, onOpenUser, nowMillis)
            }
        }
    }
}

@Composable
private fun FeedList(
    state: FeedUiState,
    onLoadMore: () -> Unit,
    onOpenRepo: (RepoId) -> Unit,
    onOpenIssue: (IssueRef) -> Unit,
    onOpenUser: (String) -> Unit,
    nowMillis: Long,
) {
    val listState = rememberLazyListState()
    // Older activity loads as the end comes into view.
    LaunchedEffect(listState, state.hasMore) {
        if (!state.hasMore) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .distinctUntilChanged()
            .collect { last -> if (last != null && last >= listState.layoutInfo.totalItemsCount - 5) onLoadMore() }
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        items(state.items, key = { it.key }) { item ->
            FeedRow(item, nowMillis, onOpenRepo, onOpenIssue, onOpenUser, Modifier.animateItem())
        }
        if (state.hasMore) {
            item(key = "more") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun FeedRow(
    item: FeedItem,
    nowMillis: Long,
    onOpenRepo: (RepoId) -> Unit,
    onOpenIssue: (IssueRef) -> Unit,
    onOpenUser: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val actor = item.actors.first()
    ListItem(
        modifier = modifier.clickable { item.open(onOpenRepo, onOpenIssue, onOpenUser) },
        leadingContent = {
            Avatar(
                actor.avatarUrl,
                actor.login,
                size = 40.dp,
                modifier = Modifier
                    .clickable { onOpenUser(actor.login) }
                    .semantics { contentDescription = actor.login },
            )
        },
        headlineContent = {
            Text(item.headline().emphasizing(item.names()), maxLines = 3, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                listOfNotNull(item.action.detail(), relative(item.createdAt, nowMillis)).joinToString(" · "),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

private fun FeedItem.open(onOpenRepo: (RepoId) -> Unit, onOpenIssue: (IssueRef) -> Unit, onOpenUser: (String) -> Unit) {
    when (val action = action) {
        is FeedAction.Issue -> onOpenIssue(IssueRef(repo, action.number))
        is FeedAction.PullRequest -> onOpenIssue(IssueRef(repo, action.number))
        is FeedAction.Commented -> onOpenIssue(IssueRef(repo, action.number))
        is FeedAction.Reviewed -> onOpenIssue(IssueRef(repo, action.number))
        is FeedAction.Forked -> onOpenRepo(action.fork)
        is FeedAction.AddedMember -> onOpenUser(action.login)
        else -> onOpenRepo(repo)
    }
}

@Composable
private fun FeedItem.headline(): String {
    val who = when (actors.size) {
        1 -> actors[0].login
        2 -> stringResource(R.string.feed_actors_two, actors[0].login, actors[1].login)
        else -> pluralStringResource(R.plurals.feed_actors_many, actors.size - 1, actors[0].login, actors.size - 1)
    }
    val repo = repo.fullName
    return when (val a = action) {
        FeedAction.Starred -> stringResource(R.string.feed_starred, who, repo)
        is FeedAction.Forked -> stringResource(R.string.feed_forked, who, repo, a.fork.fullName)
        is FeedAction.CreatedRepo -> stringResource(R.string.feed_created_repo, who, repo)
        FeedAction.MadePublic -> stringResource(R.string.feed_made_public, who, repo)
        is FeedAction.Released -> stringResource(if (a.prerelease) R.string.feed_prereleased else R.string.feed_released, who, repo, a.tag)
        is FeedAction.Issue -> stringResource(
            when (a.action) {
                IssueAction.OPENED -> R.string.feed_issue_opened
                IssueAction.CLOSED -> R.string.feed_issue_closed
                IssueAction.REOPENED -> R.string.feed_issue_reopened
            },
            who,
            repo,
        )
        is FeedAction.PullRequest -> stringResource(
            when (a.action) {
                PullRequestAction.OPENED -> R.string.feed_pr_opened
                PullRequestAction.MERGED -> R.string.feed_pr_merged
                PullRequestAction.CLOSED -> R.string.feed_pr_closed
                PullRequestAction.REOPENED -> R.string.feed_pr_reopened
            },
            who,
            repo,
            a.number,
        )
        is FeedAction.Commented ->
            if (a.isPullRequest) stringResource(R.string.feed_commented_pr, who, repo, a.number) else stringResource(R.string.feed_commented_issue, who, repo)
        is FeedAction.Reviewed -> stringResource(
            when (a.state) {
                ReviewState.APPROVED -> R.string.feed_approved
                ReviewState.CHANGES_REQUESTED -> R.string.feed_changes_requested
                else -> R.string.feed_reviewed
            },
            who,
            repo,
            a.number,
        )
        is FeedAction.Pushed -> stringResource(R.string.feed_pushed, who, repo, a.branch)
        is FeedAction.Branch -> stringResource(
            when {
                a.isTag && a.deleted -> R.string.feed_tag_deleted
                a.isTag -> R.string.feed_tag_created
                a.deleted -> R.string.feed_branch_deleted
                else -> R.string.feed_branch_created
            },
            who,
            repo,
            a.name,
        )
        is FeedAction.AddedMember -> stringResource(R.string.feed_member_added, who, repo, a.login)
    }
}

/** The people and repos a headline names, emphasized so the timeline can be skimmed. */
private fun FeedItem.names(): List<String> = buildList {
    addAll(actors.take(2).map { it.login })
    add(repo.fullName)
    when (val a = action) {
        is FeedAction.Forked -> add(a.fork.fullName)
        is FeedAction.AddedMember -> add(a.login)
        else -> Unit
    }
}

private fun String.emphasizing(names: List<String>): AnnotatedString = buildAnnotatedString {
    append(this@emphasizing)
    names.forEach { name ->
        val start = this@emphasizing.indexOf(name)
        if (start >= 0) addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), start, start + name.length)
    }
}

/** The second line: what the activity is about, when the headline doesn't already say it. */
private fun FeedAction.detail(): String? = when (this) {
    is FeedAction.Issue -> "#$number $title"
    is FeedAction.Commented -> title?.let { "#$number $it" }
    is FeedAction.CreatedRepo -> description
    is FeedAction.Released -> name?.takeIf { it != tag }
    else -> null
}

