package fr.arthurbrugiere.forgeline.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.model.FeedAction
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftIconButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSectionTitle
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSnackbarHost
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftStatusBarScrim
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.session.SessionState
import fr.arthurbrugiere.forgeline.ui.LeftOffMark
import fr.arthurbrugiere.forgeline.ui.LocalBottomBarSpace
import fr.arthurbrugiere.forgeline.ui.LocalOpenSearch
import fr.arthurbrugiere.forgeline.ui.LocalUserSettings
import fr.arthurbrugiere.forgeline.ui.ReportReading
import fr.arthurbrugiere.forgeline.ui.label
import fr.arthurbrugiere.forgeline.ui.listBottomPadding
import fr.arthurbrugiere.forgeline.ui.message
import fr.arthurbrugiere.forgeline.ui.openInCustomTab
import fr.arthurbrugiere.forgeline.ui.rememberNow
import fr.arthurbrugiere.forgeline.ui.sideSafeArea
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Instant
import java.time.ZoneId

@Composable
fun FeedRoute(
    session: SessionState,
    onSignIn: () -> Unit,
    onOpenRepo: (RepoId) -> Unit,
    onOpenIssue: (IssueRef) -> Unit,
    onOpenUser: (ForgeInstance, String) -> Unit,
    onBrowseTrending: () -> Unit = {},
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
                    secondaryAction = stringResource(R.string.browse_trending),
                    onSecondaryAction = onBrowseTrending,
                    icon = Icons.Outlined.DynamicFeed,
                )
            }
        }
        return
    }
    // Keyed by account so switching accounts never shows the previous Feed.
    val viewModel = hiltViewModel<FeedViewModel>(key = session.account.id)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    FeedScreen(
        state = state,
        onOpenUrl = { openInCustomTab(context, it) },
        onRefresh = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        onOpenRepo = onOpenRepo,
        onOpenIssue = onOpenIssue,
        onOpenUser = onOpenUser,
        onErrorShown = viewModel::errorShown,
        onVisible = viewModel::onVisible,
        onReadThrough = viewModel::readThrough,
        onBrowseTrending = onBrowseTrending,
    )
}

/** Whether rows name their forge: only when accounts span more than one. */
internal val LocalShowForge = staticCompositionLocalOf { false }

/** The Feed's lilac field: the title and search. */
@Composable
private fun FeedHeader() {
    val colors = Soft.colors
    val openSearch = LocalOpenSearch.current
    SoftHeader(
        title = stringResource(R.string.tab_feed),
        actions = {
            if (openSearch != null) {
                SoftIconButton(onClick = openSearch) {
                    Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.search), tint = Soft.colors.ink)
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
    onOpenUser: (ForgeInstance, String) -> Unit,
    onErrorShown: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenUrl: (String) -> Unit = {},
    onVisible: (List<FeedItem>) -> Unit = {},
    onReadThrough: (FeedItem) -> Unit = {},
    onBrowseTrending: () -> Unit = {},
    nowMillis: Long = rememberNow(state.items),
    zone: ZoneId = ZoneId.systemDefault(),
) = CompositionLocalProvider(LocalShowForge provides state.showForge) {
    val colors = Soft.colors
    val marks = LocalUserSettings.current.readingMarks
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
    // Rows on screen ask for their previews (repo details, pull request titles), fetched once and cached.
    val itemsByKey = remember(state.items) { state.items.associateBy { it.key } }
    // Still one chronological timeline, just marked by day so it reads in chapters. Grouped once per list, not at every
    // recomposition (a refresh starting or ending, a preview arriving).
    val days = remember(state.items, nowMillis, zone) { state.items.groupBy { it.createdAt.day(nowMillis, zone) } }
    LaunchedEffect(listState, itemsByKey) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { itemsByKey[it.key] } }
            .distinctUntilChanged()
            .collect { visible -> if (visible.isNotEmpty()) onVisible(visible) }
    }
    // The newest activity read (a while on screen) is where the next visit's "Where you left off" goes.
    ReportReading(
        listState,
        resetKey = null,
        positionOf = { key -> itemsByKey[key]?.createdAt?.toEpochMilli() },
        onRead = { key -> itemsByKey[key]?.let(onReadThrough) },
    )
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
                modifier = Modifier.fillMaxSize().sideSafeArea(),
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
                        // A new account follows nobody yet: Trending is where people and projects to follow are found.
                        SoftNotice(
                            stringResource(R.string.feed_empty_title),
                            stringResource(R.string.feed_empty_body),
                            secondaryAction = stringResource(R.string.browse_trending),
                            onSecondaryAction = onBrowseTrending,
                            icon = Icons.Outlined.DynamicFeed,
                        )
                    }
                    else -> {
                        days.forEach { (day, items) ->
                            item(key = "day-$day", contentType = "day") { SoftSectionTitle(stringResource(day.label)) }
                            items(items, key = { it.key }, contentType = { it.action.kind }) { item ->
                                Column(Modifier.widthIn(max = SoftTokens.MaxReadingWidth).animateItem()) {
                                    if (marks && item.key == state.leftOffBefore) LeftOffMark()
                                    // Each row gets only its own preview: one arriving redraws the rows it is for, not all of them.
                                    FeedRow(
                                        item,
                                        repoPreview = state.previews.repos[item.repo],
                                        release = item.release?.let { state.previews.releases[it] },
                                        conversation = item.conversation?.let { state.previews.conversations[it] },
                                        nowMillis, onOpenRepo, onOpenIssue, onOpenUser, onOpenUrl,
                                    )
                                }
                            }
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
        SoftSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalBottomBarSpace.current))
    }
}

private enum class Day(val label: Int) {
    TODAY(R.string.feed_day_today),
    YESTERDAY(R.string.feed_day_yesterday),
    THIS_WEEK(R.string.feed_day_week),
    EARLIER(R.string.feed_day_earlier),
}

private fun Instant.day(nowMillis: Long, zone: ZoneId): Day {
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val date = atZone(zone).toLocalDate()
    return when {
        !date.isBefore(today) -> Day.TODAY
        date == today.minusDays(1) -> Day.YESTERDAY
        date.isAfter(today.minusDays(7)) -> Day.THIS_WEEK
        else -> Day.EARLIER
    }
}

internal fun FeedItem.open(
    onOpenRepo: (RepoId) -> Unit,
    onOpenIssue: (IssueRef) -> Unit,
    onOpenUser: (ForgeInstance, String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenRelease: ((RepoId, String) -> Unit)?,
    onOpenDiscussion: ((RepoId, Int) -> Unit)? = null,
) {
    when (val action = action) {
        // What was released, not only where: outside the app shell there is no release page, so its repository.
        is FeedAction.Released -> if (onOpenRelease != null) onOpenRelease(repo, action.tag) else onOpenRepo(repo)
        // Outside the app shell there is no discussion page: an announcement then opens on its forge.
        is FeedAction.Announced -> if (onOpenDiscussion != null) onOpenDiscussion(repo, action.number) else onOpenUrl("${repo.webUrl}/discussions/${action.number}")
        // Each says its kind: on GitLab a merge request and an issue can share a number.
        is FeedAction.Issue -> onOpenIssue(IssueRef(repo, action.number, isPullRequest = false))
        is FeedAction.PullRequest -> onOpenIssue(IssueRef(repo, action.number, isPullRequest = true))
        is FeedAction.Commented -> onOpenIssue(IssueRef(repo, action.number, action.isPullRequest))
        is FeedAction.Reviewed -> onOpenIssue(IssueRef(repo, action.number, isPullRequest = true))
        is FeedAction.Forked -> onOpenRepo(action.fork)
        is FeedAction.AddedMember -> onOpenUser(repo.forge, action.login)
        else -> onOpenRepo(repo)
    }
}
