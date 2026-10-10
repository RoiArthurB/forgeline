package fr.arthurbrugiere.forgeline.feed

import fr.arthurbrugiere.forgeline.ui.LocalUserSettings
import fr.arthurbrugiere.forgeline.ui.openInCustomTab
import fr.arthurbrugiere.forgeline.ui.rememberNow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.outlined.Campaign
import fr.arthurbrugiere.forgeline.ui.sideSafeArea
import androidx.compose.ui.platform.LocalDensity
import fr.arthurbrugiere.forgeline.core.ui.format.forgeInlineContent
import fr.arthurbrugiere.forgeline.core.ui.format.appendForge
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.clickable
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import fr.arthurbrugiere.forgeline.core.ui.soft.animationsEnabled
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import fr.arthurbrugiere.forgeline.ui.LocalOpenRelease
import fr.arthurbrugiere.forgeline.ui.ReportReading
import fr.arthurbrugiere.forgeline.ui.LeftOffMark
import fr.arthurbrugiere.forgeline.core.model.FeedAction
import fr.arthurbrugiere.forgeline.core.model.IssueAction
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
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
import androidx.compose.material.icons.outlined.Adjust
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Commit
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
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import fr.arthurbrugiere.forgeline.core.model.RepoPreview
import fr.arthurbrugiere.forgeline.core.ui.format.compactCount
import fr.arthurbrugiere.forgeline.core.ui.format.languageColor
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftPill
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSectionTitle
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTag
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
    )
}

/** Whether rows name their forge: only when accounts span more than one. */
private val LocalShowForge = staticCompositionLocalOf { false }

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
    onOpenUser: (ForgeInstance, String) -> Unit,
    onErrorShown: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenUrl: (String) -> Unit = {},
    onVisible: (List<FeedItem>) -> Unit = {},
    onReadThrough: (FeedItem) -> Unit = {},
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
                        SoftNotice(stringResource(R.string.feed_empty_title), stringResource(R.string.feed_empty_body))
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
                                        repoPreview = item.previewRepo?.let { state.previews.repos[it] },
                                        pullTitle = item.previewPull?.let { state.previews.pullTitles[it] },
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
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalBottomBarSpace.current)) { data ->
            Snackbar(data, shape = RoundedCornerShape(16.dp), containerColor = colors.ink, contentColor = colors.ground)
        }
    }
}

/**
 * One event, kind first: a short line saying who did what and when, then the thing it's about, drawn for its kind:
 * a repository preview, a state pill and title for issues and pull requests, a tag for a release, a branch for a push.
 */
@Composable
private fun FeedRow(
    item: FeedItem,
    repoPreview: RepoPreview?,
    pullTitle: String?,
    nowMillis: Long,
    onOpenRepo: (RepoId) -> Unit,
    onOpenIssue: (IssueRef) -> Unit,
    onOpenUser: (ForgeInstance, String) -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Soft.colors
    val actor = item.actors.first()
    val openRelease = LocalOpenRelease.current
    val openDiscussion = fr.arthurbrugiere.forgeline.ui.LocalOpenDiscussion.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .softPressable { item.open(onOpenRepo, onOpenIssue, onOpenUser, onOpenUrl, openRelease, openDiscussion) }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Avatar(
            actor.avatarUrl,
            actor.login,
            size = 40.dp,
            placeholderColor = colors.surface,
            placeholderContentColor = colors.inkMuted,
            modifier = Modifier
                .clip(CircleShape)
                .clickable { onOpenUser(item.repo.forge, actor.login) }
                .semantics { contentDescription = actor.login },
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            // Which forge (its logo), once more than one is signed in.
            val forge = item.repo.forge.takeIf { LocalShowForge.current }
            // A name is never broken across lines, and GitLab's can be long (group/subgroup/project): one that doesn't
            // leave room pushed the end of the line out of sight, which is the forge and the time. The repository's
            // name gives way first, step by step, then the row takes one more line.
            var squeeze by remember(item.key, forge) { mutableIntStateOf(0) }
            val repoLabel = feedRepoLabel(item.repo.fullName, squeeze)
            val lines = (if (LocalDensity.current.fontScale > 1.3f) 4 else 2) + if (squeeze > LAST_SQUEEZE) 1 else 0
            Text(
                buildAnnotatedString {
                    append(item.headline(repoLabel).emphasizing(item.names(repoLabel), colors.ink))
                    // Each dot sticks to what precedes it, so a line never starts with "·"; the time is kept whole.
                    val time = relative(item.createdAt, nowMillis, abbreviated = true).replace(' ', '\u00A0')
                    withStyle(SpanStyle(color = colors.inkMuted)) {
                        forge?.let { append("\u00A0· "); appendForge(it) }
                        append("\u00A0· $time")
                    }
                },
                inlineContent = forge?.let { forgeInlineContent(it, colors.inkMuted) }.orEmpty(),
                style = Soft.type.secondary,
                color = colors.inkMuted,
                maxLines = lines,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (it.hasVisualOverflow) squeeze = nextFeedSqueeze(item.repo.fullName, squeeze) },
                modifier = Modifier.padding(top = 2.dp),
            )
            FeedObject(item, repoPreview, pullTitle, Modifier.padding(top = 8.dp))
        }
    }
}

/** The thing an event is about, drawn for its kind. */
@Composable
private fun FeedObject(item: FeedItem, repoPreview: RepoPreview?, pullTitle: String?, modifier: Modifier = Modifier) {
    val colors = Soft.colors
    when (val a = item.action) {
        FeedAction.Starred, FeedAction.MadePublic, is FeedAction.Forked -> RepoObject(item.repo, repoPreview, null, modifier)
        is FeedAction.CreatedRepo -> RepoObject(item.repo, null, a.description, modifier)
        is FeedAction.Released -> Column(modifier) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                StatePill(a.tag, Icons.Outlined.Sell, colors.fields[0])
                if (a.prerelease) SoftTag(stringResource(R.string.repo_prerelease))
            }
            a.name?.takeIf { it != a.tag }?.let { ObjectTitle(it) }
        }
        is FeedAction.Announced -> Column(modifier) {
            StatePill(stringResource(R.string.feed_state_announcement), Icons.Outlined.Campaign, colors.fields[1], number = a.number)
            ObjectTitle(a.title)
        }
        is FeedAction.Issue -> Column(modifier) {
            when (a.action) {
                IssueAction.OPENED -> StatePill(stringResource(R.string.feed_state_open), Icons.Outlined.Adjust, colors.fields[2], number = a.number)
                IssueAction.REOPENED -> StatePill(stringResource(R.string.feed_state_reopened), Icons.Outlined.Replay, colors.fields[2], number = a.number)
                IssueAction.CLOSED -> StatePill(stringResource(R.string.feed_state_closed), Icons.Outlined.CheckCircleOutline, colors.fields[0], number = a.number)
            }
            ObjectTitle(a.title)
        }
        is FeedAction.PullRequest -> Column(modifier) {
            when (a.action) {
                PullRequestAction.OPENED -> StatePill(stringResource(R.string.feed_state_open), Icons.AutoMirrored.Outlined.CallMerge, colors.fields[2], number = a.number)
                PullRequestAction.REOPENED -> StatePill(stringResource(R.string.feed_state_reopened), Icons.AutoMirrored.Outlined.CallMerge, colors.fields[2], number = a.number)
                PullRequestAction.MERGED -> StatePill(stringResource(R.string.feed_state_merged), Icons.AutoMirrored.Outlined.CallMerge, colors.fields[1], number = a.number)
                PullRequestAction.CLOSED -> StatePill(stringResource(R.string.feed_state_closed), Icons.Outlined.Close, colors.fields[0], number = a.number)
            }
            LateTitle(a.title ?: pullTitle)
        }
        is FeedAction.Reviewed -> Column(modifier) {
            when (a.state) {
                ReviewState.APPROVED -> StatePill(stringResource(R.string.feed_state_approved), Icons.Outlined.Check, colors.fields[2], number = a.number)
                ReviewState.CHANGES_REQUESTED -> StatePill(stringResource(R.string.feed_state_changes), Icons.Outlined.RateReview, colors.fields[0], number = a.number)
                else -> StatePill(stringResource(R.string.feed_state_reviewed), Icons.Outlined.RateReview, colors.surface, number = a.number)
            }
            LateTitle(pullTitle)
        }
        is FeedAction.Commented -> Column(modifier) {
            StatePill(stringResource(R.string.feed_state_comment), Icons.Outlined.ChatBubbleOutline, colors.fields[1], number = a.number)
            LateTitle(a.title ?: pullTitle)
        }
        is FeedAction.Pushed -> StatePill(a.branch, Icons.Outlined.Commit, colors.surface, monospace = true, modifier = modifier)
        is FeedAction.Branch -> StatePill(a.name, if (a.isTag) Icons.Outlined.Sell else Icons.Outlined.AccountTree, colors.surface, monospace = true, modifier = modifier)
        // The line already says who joined where.
        is FeedAction.AddedMember -> Unit
    }
}

/** A repository at a glance: its name, description, language and stars, on a soft panel. */
@Composable
private fun RepoObject(repo: RepoId, preview: RepoPreview?, description: String?, modifier: Modifier = Modifier) {
    val colors = Soft.colors
    // Details arriving later grow the panel and fade in, rather than popping the rows below down.
    val animations = animationsEnabled()
    val startedEmpty = remember(repo) { preview == null }
    Column(
        modifier
            .widthIn(max = SoftTokens.MaxMeasure)
            .fillMaxWidth()
            .clip(SoftTokens.RowCorner)
            .background(colors.surface)
            .then(if (animations) Modifier.animateContentSize(tween(PREVIEW_MILLIS)) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = colors.inkMuted)) { append("${repo.owner}/\u2060") }
                append(repo.name)
            },
            style = Soft.type.name.copy(fontSize = 18.sp, lineHeight = 22.sp),
            color = colors.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        description?.let { RepoDescription(it) }
        if (preview != null) {
            FadeInIfLate(late = startedEmpty) {
                Column {
                    if (description == null) preview.description?.let { RepoDescription(it) }
                    RepoMeta(preview)
                }
            }
        }
    }
}

@Composable
private fun RepoDescription(text: String) {
    Text(text, style = Soft.type.secondary, color = Soft.colors.ink, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
}

/** A repository's language (with its linguist color) and stars. */
@Composable
private fun RepoMeta(preview: RepoPreview) {
    val colors = Soft.colors
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        preview.language?.let { language ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                languageColor(language)?.let { dot ->
                    Box(Modifier.size(9.dp).background(dot, CircleShape))
                    Spacer(Modifier.width(6.dp))
                }
                Text(language, style = Soft.type.meta, color = colors.inkMuted)
            }
        }
        Text(stringResource(R.string.trending_stars, compactCount(preview.stars)), style = Soft.type.meta, color = colors.inkMuted)
    }
}

/**
 * Fades [content] in when it arrived after its row was already on screen ([late]); content that was there from the
 * start (cached previews scrolling in) shows at once.
 */
@Composable
private fun FadeInIfLate(late: Boolean, content: @Composable () -> Unit) {
    val animate = late && animationsEnabled()
    val alpha = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) { if (animate) alpha.animateTo(1f, tween(PREVIEW_MILLIS)) }
    Box(Modifier.graphicsLayer { this.alpha = alpha.value }) { content() }
}

private const val PREVIEW_MILLIS = 220

/** A state (Open, Merged, a tag, a branch...) as a soft pill, then an optional #number. */
@Composable
private fun StatePill(
    label: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    number: Int? = null,
    monospace: Boolean = false,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SoftPill(label, icon, tint, monospace = monospace)
        number?.let { Text("#$it", style = Soft.type.meta, color = Soft.colors.inkMuted) }
    }
}

/** The title of what an event is about, set to be read. */
@Composable
private fun ObjectTitle(title: String) {
    Text(
        title,
        style = Soft.type.body.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
        color = Soft.colors.ink,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 6.dp).widthIn(max = SoftTokens.MaxMeasure),
    )
}

/**
 * A title that may still be on its way (a pull request's: carried by Forgejo's events, fetched after the Feed shows for
 * GitHub's, which give only the number): its placeholder bar until then, and a fade when it lands.
 */
@Composable
private fun LateTitle(title: String?) {
    val startedEmpty = remember { title == null }
    if (title == null) PendingTitle() else FadeInIfLate(late = startedEmpty) { ObjectTitle(title) }
}

/** Where a title will land: a soft bar, so the row keeps its shape. */
@Composable
private fun PendingTitle() {
    Box(Modifier.padding(top = 10.dp).fillMaxWidth(0.7f).height(14.dp).background(Soft.colors.surface, SoftTokens.Pill))
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

private fun FeedItem.open(
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

/** A word joiner after each slash, so "owner/name" never breaks across lines. */
internal fun String.unbreakable(): String = replace("/", "/\u2060")

/** The steps a repository's name gives way by before a row takes another line: see [feedRepoLabel]. */
private const val LAST_SQUEEZE = 2

/**
 * A repository's name in a Feed line, shortened as [squeeze] grows: whole, then a nested path without its middle
 * (`group/…/project`), then the project alone. Tapping the row still opens the repository, which says where it is.
 */
internal fun feedRepoLabel(fullName: String, squeeze: Int): String {
    val parts = fullName.split('/')
    return when {
        squeeze <= 0 -> fullName
        squeeze == 1 && parts.size > 2 -> "${parts.first()}/…/${parts.last()}"
        squeeze == 1 -> fullName
        else -> parts.last()
    }
}

/**
 * The step after [squeeze] for a line that still doesn't fit: the next one that changes the name (a step that leaves
 * the line as it was would never be laid out again), then the extra line, which is the last.
 */
internal fun nextFeedSqueeze(fullName: String, squeeze: Int): Int {
    val current = feedRepoLabel(fullName, squeeze)
    return ((squeeze + 1)..LAST_SQUEEZE).firstOrNull { feedRepoLabel(fullName, it) != current } ?: (LAST_SQUEEZE + 1)
}

@Composable
private fun FeedItem.headline(repoLabel: String): String = rawHeadline(repoLabel).unbreakable()

@Composable
private fun FeedItem.rawHeadline(repoLabel: String): String {
    val who = when (actors.size) {
        1 -> actors[0].login
        2 -> stringResource(R.string.feed_actors_two, actors[0].login, actors[1].login)
        else -> pluralStringResource(R.plurals.feed_actors_many, actors.size - 1, actors[0].login, actors.size - 1)
    }
    val repo = repoLabel
    return when (val a = action) {
        FeedAction.Starred -> stringResource(R.string.feed_line_starred, who)
        is FeedAction.Forked -> stringResource(R.string.feed_line_forked, who, a.fork.fullName)
        is FeedAction.CreatedRepo -> stringResource(R.string.feed_line_created, who)
        FeedAction.MadePublic -> stringResource(R.string.feed_line_made_public, who)
        is FeedAction.Released -> stringResource(R.string.feed_line_released, who, repo)
        is FeedAction.Announced -> stringResource(R.string.feed_line_announced, who, repo)
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
                PullRequestAction.OPENED -> R.string.feed_line_pr_opened
                PullRequestAction.MERGED -> R.string.feed_line_pr_merged
                PullRequestAction.CLOSED -> R.string.feed_line_pr_closed
                PullRequestAction.REOPENED -> R.string.feed_line_pr_reopened
            },
            who,
            repo,
        )
        is FeedAction.Commented -> stringResource(R.string.feed_line_commented, who, repo)
        is FeedAction.Reviewed -> stringResource(R.string.feed_line_reviewed, who, repo)
        is FeedAction.Pushed -> stringResource(R.string.feed_line_pushed, who, repo)
        is FeedAction.Branch -> stringResource(
            when {
                a.isTag && a.deleted -> R.string.feed_line_tag_deleted
                a.isTag -> R.string.feed_line_tag_created
                a.deleted -> R.string.feed_line_branch_deleted
                else -> R.string.feed_line_branch_created
            },
            who,
            repo,
        )
        is FeedAction.AddedMember -> stringResource(R.string.feed_member_added, who, repo, a.login)
    }
}

/** The people and repos a headline names, emphasized so the timeline can be skimmed. */
private fun FeedItem.names(repoLabel: String): List<String> = rawNames(repoLabel).map { it.unbreakable() }

private fun FeedItem.rawNames(repoLabel: String): List<String> = buildList {
    addAll(actors.take(2).map { it.login })
    add(repoLabel)
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

