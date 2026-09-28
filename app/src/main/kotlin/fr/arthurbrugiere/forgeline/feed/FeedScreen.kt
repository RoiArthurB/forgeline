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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import fr.arthurbrugiere.forgeline.ui.message
import fr.arthurbrugiere.forgeline.ui.relative
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Adjust
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Commit
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftStatusBarScrim
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.softPressable
import fr.arthurbrugiere.forgeline.ui.LocalBottomBarSpace
import fr.arthurbrugiere.forgeline.ui.LocalOpenSearch
import fr.arthurbrugiere.forgeline.ui.listBottomPadding
import androidx.compose.runtime.getValue

@Composable
fun FeedRoute(
    session: SessionState,
    onSignIn: () -> Unit,
    onOpenRepo: (RepoId) -> Unit,
    onOpenIssue: (IssueRef) -> Unit,
    onOpenUser: (String) -> Unit,
) {
    if (session !is SessionState.SignedIn) {
        Column(Modifier.fillMaxSize().background(Soft.colors.ground), horizontalAlignment = Alignment.CenterHorizontally) {
            FeedHeader()
            if (session == SessionState.SignedOut) {
                SoftNotice(
                    stringResource(R.string.feed_signed_out_title),
                    stringResource(R.string.feed_signed_out_body),
                    action = stringResource(R.string.sign_in),
                    onAction = onSignIn,
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

/** The Feed's lilac field: the title and search. */
@Composable
private fun FeedHeader() {
    val colors = Soft.colors
    val openSearch = LocalOpenSearch.current
    SoftHeader(
        tint = colors.fields[1],
        title = stringResource(R.string.tab_feed),
        actions = {
            if (openSearch != null) {
                IconButton(onClick = openSearch) {
                    Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.search), tint = colors.ink)
                }
            }
        },
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
    val colors = Soft.colors
    val snackbar = remember { SnackbarHostState() }
    val refreshFailed = stringResource(R.string.trending_refresh_failed)
    val hasItems = state.items.isNotEmpty()
    LaunchedEffect(state.error, hasItems) {
        if (state.error != null && hasItems) {
            snackbar.showSnackbar(refreshFailed)
            onErrorShown()
        }
    }

    val listState = rememberLazyListState()
    // Older activity loads as the end comes into view.
    LaunchedEffect(listState, state.hasMore) {
        if (!state.hasMore) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .distinctUntilChanged()
            .collect { last -> if (last != null && last >= listState.layoutInfo.totalItemsCount - 5) onLoadMore() }
    }
    Box(modifier.fillMaxSize().background(colors.ground)) {
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            state = pullState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = state.isRefreshing,
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
                modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
            ) {
                item(key = "header", contentType = "header") { FeedHeader() }
                when {
                    !hasItems && state.error != null -> item(key = "error") {
                        SoftNotice(
                            stringResource(R.string.feed_error_title),
                            stringResource(state.error.message),
                            action = stringResource(R.string.retry),
                            onAction = onRefresh,
                        )
                    }
                    !hasItems && state.syncedAtMillis == null -> item(key = "loading") {
                        SoftLoadingRows(stringResource(R.string.feed_loading))
                    }
                    !hasItems -> item(key = "empty") {
                        SoftNotice(stringResource(R.string.feed_empty_title), stringResource(R.string.feed_empty_body))
                    }
                    else -> {
                        item(key = "top-gap") { Spacer(Modifier.height(8.dp)) }
                        items(state.items, key = { it.key }, contentType = { "event" }) { item ->
                            FeedRow(
                                item, nowMillis, onOpenRepo, onOpenIssue, onOpenUser,
                                Modifier.widthIn(max = SoftTokens.MaxReadingWidth).animateItem(),
                            )
                        }
                        if (state.hasMore) {
                            item(key = "more") {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = colors.accent, trackColor = colors.surface)
                                }
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

@Composable
private fun FeedRow(
    item: FeedItem,
    nowMillis: Long,
    onOpenRepo: (RepoId) -> Unit,
    onOpenIssue: (IssueRef) -> Unit,
    onOpenUser: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Soft.colors
    val actor = item.actors.first()
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .softPressable { item.open(onOpenRepo, onOpenIssue, onOpenUser) }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // The actor, with a small badge saying what kind of activity this is.
        Box(Modifier.size(44.dp)) {
            Avatar(
                actor.avatarUrl,
                actor.login,
                size = 40.dp,
                placeholderColor = colors.surface,
                placeholderContentColor = colors.inkMuted,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onOpenUser(actor.login) }
                    .semantics { contentDescription = actor.login },
            )
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(20.dp)
                    .background(colors.ground, CircleShape)
                    .padding(2.dp)
                    .background(colors.fields[item.action.tintIndex], CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(item.action.icon, contentDescription = null, tint = colors.ink, modifier = Modifier.size(11.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.headline().emphasizing(item.names(), colors.ink),
                style = Soft.type.body,
                color = colors.inkMuted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(item.action.detail(), relative(item.createdAt, nowMillis)).joinToString(" · "),
                style = Soft.type.meta,
                color = colors.inkMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** A glyph for the badge on the actor's avatar. */
private val FeedAction.icon: ImageVector
    get() = when (this) {
        FeedAction.Starred -> Icons.Filled.Star
        is FeedAction.Forked -> Icons.AutoMirrored.Outlined.CallSplit
        is FeedAction.CreatedRepo, FeedAction.MadePublic -> Icons.Outlined.AutoAwesome
        is FeedAction.Released -> Icons.Outlined.NewReleases
        is FeedAction.Issue -> Icons.Outlined.Adjust
        is FeedAction.PullRequest -> Icons.AutoMirrored.Outlined.CallMerge
        is FeedAction.Commented -> Icons.Outlined.ChatBubbleOutline
        is FeedAction.Reviewed -> Icons.Outlined.RateReview
        is FeedAction.Pushed, is FeedAction.Branch -> Icons.Outlined.Commit
        is FeedAction.AddedMember -> Icons.Outlined.PersonAdd
    }

/** Warm for appreciation (stars, releases), cool for conversation, fresh for code. */
private val FeedAction.tintIndex: Int
    get() = when (this) {
        FeedAction.Starred, is FeedAction.Released, is FeedAction.CreatedRepo, FeedAction.MadePublic -> 0
        is FeedAction.Issue, is FeedAction.Commented, is FeedAction.Reviewed, is FeedAction.AddedMember -> 1
        is FeedAction.Forked, is FeedAction.PullRequest, is FeedAction.Pushed, is FeedAction.Branch -> 2
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

private fun String.emphasizing(names: List<String>, ink: Color): AnnotatedString = buildAnnotatedString {
    append(this@emphasizing)
    names.forEach { name ->
        val start = this@emphasizing.indexOf(name)
        if (start >= 0) addStyle(SpanStyle(fontWeight = FontWeight.Medium, color = ink), start, start + name.length)
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

