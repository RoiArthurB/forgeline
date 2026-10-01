package fr.arthurbrugiere.forgeline.trending

import fr.arthurbrugiere.forgeline.ui.sideSafeArea
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftChoicePill
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftChipTabs
import fr.arthurbrugiere.forgeline.core.ui.format.ForgeIcon
import fr.arthurbrugiere.forgeline.core.ui.format.ForgeMark
import fr.arthurbrugiere.forgeline.session.signedInOn
import fr.arthurbrugiere.forgeline.core.ui.format.languageColor
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import android.os.SystemClock
import android.text.format.DateUtils
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.ui.ReportReading
import fr.arthurbrugiere.forgeline.ui.LeftOffMark
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import fr.arthurbrugiere.forgeline.core.ui.format.compactCount
import fr.arthurbrugiere.forgeline.core.ui.format.parseHexColor
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftStatusBarScrim
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSwitch
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.animationsEnabled
import fr.arthurbrugiere.forgeline.core.ui.soft.softPressable
import fr.arthurbrugiere.forgeline.ui.LocalBottomBarSpace
import fr.arthurbrugiere.forgeline.ui.listBottomPadding
import fr.arthurbrugiere.forgeline.session.SessionState
import fr.arthurbrugiere.forgeline.ui.Avatar
import fr.arthurbrugiere.forgeline.ui.LocalOpenSearch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.text.NumberFormat
import androidx.compose.runtime.getValue
import fr.arthurbrugiere.forgeline.ui.rememberCustomTabOpener
import androidx.compose.runtime.setValue

@Composable
fun TrendingRoute(
    session: SessionState,
    onSignIn: () -> Unit,
    onOpenRepo: (RepoId) -> Unit,
    viewModel: TrendingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openUrl = rememberCustomTabOpener()
    TrendingScreen(
        state = state,
        onPeriodChange = viewModel::selectPeriod,
        onRefresh = viewModel::refresh,
        // Starring needs an account on the repository's own forge.
        onToggleStar = { repo -> if (session.signedInOn(repo.forge)) viewModel.toggleStar(repo) else onSignIn() },
        // The app can't open a GitLab repository yet: its page on gitlab.com can.
        onOpenRepo = { repo -> if (repo.forge.isBrowsable) onOpenRepo(repo) else openUrl(repo.webUrl) },
        onErrorShown = viewModel::errorShown,
        onStarFailureShown = viewModel::starFailureShown,
        onReadThrough = viewModel::readThrough,
        onSelectForge = viewModel::selectForge,
    )
}

// Lazy list layout: the header field, the status line, then the rows.
private const val FIRST_ROW = 2
internal val MaxMeasure = SoftTokens.MaxMeasure
internal const val DESCRIPTION_TAG = "trending_description"


/** Whether rows name their forge: only when the page mixes more than one. */
private val LocalShowForge = staticCompositionLocalOf { false }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrendingScreen(
    state: TrendingUiState,
    onPeriodChange: (TrendingPeriod) -> Unit,
    onRefresh: () -> Unit,
    onToggleStar: (RepoId) -> Unit,
    onOpenRepo: (RepoId) -> Unit,
    onErrorShown: () -> Unit,
    onStarFailureShown: () -> Unit,
    modifier: Modifier = Modifier,
    onReadThrough: (Int) -> Unit = {},
    nowMillis: Long = System.currentTimeMillis(),
    onSelectForge: (ForgeInstance?) -> Unit = {},
) = CompositionLocalProvider(LocalShowForge provides state.showForge) {
    val snackbar = remember { SnackbarHostState() }
    val refreshFailed = stringResource(R.string.trending_refresh_failed)
    val starFailed = stringResource(R.string.trending_star_failed)
    val hasItems = state.items.isNotEmpty()

    LaunchedEffect(state.error, hasItems) {
        if (state.error != null && hasItems) {
            snackbar.showSnackbar(refreshFailed)
            onErrorShown()
        }
    }
    LaunchedEffect(state.starFailed) {
        if (state.starFailed) {
            snackbar.showSnackbar(starFailed)
            onStarFailureShown()
        }
    }

    val colors = Soft.colors

    run {
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current
        val animations = animationsEnabled()

        // When the period changes, the rows rise in softly. True in the very frame it changes, so rows that come
        // with it animate too; the timestamp covers rows that arrive a moment later from the cache.
        var shownPeriod by rememberSaveable { mutableStateOf(state.period) }
        var changedAt by remember { mutableLongStateOf(0L) }
        val periodJustChanged = state.period != shownPeriod
        LaunchedEffect(state.period) {
            if (state.period != shownPeriod) {
                shownPeriod = state.period
                changedAt = SystemClock.uptimeMillis()
                if (listState.firstVisibleItemIndex > 1) listState.scrollToItem(0)
            }
        }

        val resumeAt = state.resumeAt?.takeIf { it in 0 until state.items.lastIndex }
        val rankByKey = remember(state.items) { state.items.withIndex().associate { (index, item) -> item.repo.id.fullName to index.toLong() } }
        ReportReading(listState, resetKey = state.period, positionOf = { rankByKey[it] }, onRead = { key -> rankByKey[key]?.let { onReadThrough(it.toInt()) } })
        // Once the mark is on screen or above it, the way back to it has done its job.
        val markReached by remember(resumeAt) {
            derivedStateOf { resumeAt != null && listState.firstVisibleItemIndex + listState.layoutInfo.visibleItemsInfo.size > FIRST_ROW + resumeAt + 1 }
        }

        Box(modifier.fillMaxSize().background(colors.ground)) {
            val pullState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = state.isRefreshing && hasItems,
                onRefresh = onRefresh,
                state = pullState,
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = pullState,
                        isRefreshing = state.isRefreshing && hasItems,
                        modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars),
                        containerColor = colors.surface,
                        color = colors.accent,
                    )
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    state = listState,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    contentPadding = PaddingValues(bottom = listBottomPadding()),
                    modifier = Modifier
                        .fillMaxSize()
                        .sideSafeArea(),
                ) {
                    item(key = "header", contentType = "header") {
                        val openSearch = LocalOpenSearch.current
                        SoftHeader(
                            tint = colors.fields[state.period.ordinal],
                            title = stringResource(R.string.tab_trending),
                            actions = {
                                if (openSearch != null) {
                                    IconButton(onClick = openSearch) {
                                        Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.search), tint = colors.ink)
                                    }
                                }
                            },
                        ) {
                            // The period is the one axis in the open; which forge sits in the status line below.
                            SoftSwitch(
                                options = TrendingPeriod.entries.map { stringResource(it.label) },
                                selected = state.period.ordinal,
                                onSelect = { onPeriodChange(TrendingPeriod.entries[it]) },
                            )
                        }
                    }
                    // Shown with several forges even when one forge has nothing, so the choice is never stranded.
                    if (hasItems || state.forges.size > 1) {
                        item(key = "status", contentType = "status") {
                            StatusLine(
                                updatedAtMillis = state.updatedAtMillis,
                                nowMillis = nowMillis,
                                resumeAt = resumeAt?.takeUnless { markReached },
                                onResume = {
                                    val above = with(density) { 88.dp.roundToPx() }
                                    scope.launch { listState.animateScrollToItem(FIRST_ROW + resumeAt!! + 1, -above) }
                                },
                                forgePicker = if (state.forges.size > 1) {
                                    {
                                        // Every forge mixed, or one forge's ranking alone.
                                        SoftChoicePill(
                                            name = stringResource(R.string.choice_forge),
                                            options = listOf(stringResource(R.string.search_all_forges)) + state.forges.map { it.displayName },
                                            selected = state.forges.indexOf(state.onlyForge) + 1,
                                            onSelect = { onSelectForge(state.forges.getOrNull(it - 1)) },
                                            leading = { index, color -> state.forges.getOrNull(index - 1)?.let { ForgeIcon(it, size = 18.dp, tint = color, contentDescription = null) } },
                                        )
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                    when {
                        !hasItems && state.error != null -> item(key = "error") {
                            SoftNotice(
                                stringResource(R.string.trending_error_title),
                                stringResource(state.error.message),
                                action = stringResource(R.string.retry),
                                onAction = onRefresh,
                            )
                        }
                        // Fetched, and genuinely nothing: say so instead of loading forever.
                        !hasItems && state.updatedAtMillis != null && !state.isRefreshing -> item(key = "empty") {
                            SoftNotice(
                                stringResource(R.string.trending_empty_title),
                                // Name the forge that came back empty; with several mixed, blame none of them.
                                (state.onlyForge ?: state.forges.singleOrNull() ?: ForgeInstance.GitHub.takeIf { state.forges.isEmpty() })
                                    ?.let { stringResource(R.string.trending_empty_body, it.displayName) }
                                    ?: stringResource(R.string.trending_empty_body_mixed),
                                action = stringResource(R.string.trending_refresh),
                                onAction = onRefresh,
                            )
                        }
                        !hasItems -> item(key = "loading") { SoftLoadingRows(stringResource(R.string.trending_loading)) }
                        else -> itemsIndexed(
                            state.items,
                            // By forge too: the same owner/name can trend on GitHub and on Codeberg.
                            key = { _, item -> item.repo.id.key },
                            contentType = { _, _ -> "row" },
                        ) { index, item ->
                            val rise = animations && index < 8 &&
                                (periodJustChanged || SystemClock.uptimeMillis() - changedAt < 600)
                            Column(Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().then(riseIn(item.repo.id, state.period, index, rise))) {
                                RepoRow(
                                    rank = index + 1,
                                    item = item,
                                    period = state.period,
                                    onToggleStar = { onToggleStar(item.repo.id) },
                                    onOpen = { onOpenRepo(item.repo.id) },
                                )
                                if (index == resumeAt) LeftOffMark()
                            }
                        }
                    }
                }
            }
            // Once the tinted field has scrolled away, the status bar gets the ground behind it.
            val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
            SoftStatusBarScrim(scrolled)
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalBottomBarSpace.current)) { data ->
                Snackbar(data, shape = RoundedCornerShape(16.dp), containerColor = colors.ink, contentColor = colors.ground)
            }
        }
    }
}

/** Rows shown right after a period change rise in softly, staggered down the list. They start visible. */
@Composable
private fun riseIn(id: RepoId, period: TrendingPeriod, index: Int, enabled: Boolean): Modifier {
    val progress = remember(id, period) { Animatable(if (enabled) 0f else 1f) }
    LaunchedEffect(progress) {
        if (progress.value < 1f) {
            delay(index * 35L)
            progress.animateTo(1f, SoftTokens.spring())
        }
    }
    if (progress.value >= 1f && !progress.isRunning) return Modifier
    val rise = with(LocalDensity.current) { 16.dp.toPx() }
    return Modifier.graphicsLayer {
        alpha = 0.35f + 0.65f * progress.value
        translationY = (1f - progress.value) * rise
    }
}

@Composable
private fun StatusLine(updatedAtMillis: Long?, nowMillis: Long, resumeAt: Int?, onResume: () -> Unit, forgePicker: (@Composable () -> Unit)? = null) {
    val colors = Soft.colors
    Row(
        Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            updatedAtMillis?.let {
                stringResource(R.string.trending_updated, DateUtils.getRelativeTimeSpanString(it, nowMillis, DateUtils.MINUTE_IN_MILLIS).toString())
            }.orEmpty(),
            style = Soft.type.secondary,
            color = colors.inkMuted,
            modifier = Modifier.weight(1f),
        )
        if (resumeAt != null) {
            Row(
                Modifier
                    .clip(SoftTokens.Pill)
                    .clickable(role = Role.Button, onClick = onResume)
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(R.string.trending_resume), style = Soft.type.label, color = colors.inkMuted)
                Icon(Icons.Outlined.ArrowDownward, contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(16.dp))
            }
        }
        forgePicker?.let {
            Spacer(Modifier.width(8.dp))
            it()
            Spacer(Modifier.width(12.dp))
        }
    }
}

@Composable
private fun RepoRow(rank: Int, item: TrendingItem, period: TrendingPeriod, onToggleStar: () -> Unit, onOpen: () -> Unit) {
    val colors = Soft.colors
    val type = Soft.type
    val repo = item.repo
    val gained = NumberFormat.getIntegerInstance().format(repo.periodStars)
    val rankDescription = stringResource(R.string.trending_rank, rank)
    val gainedDescription = stringResource(period.gainedLabel, gained)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .softPressable(onClick = onOpen)
            .padding(start = 12.dp, end = 4.dp, top = 14.dp, bottom = 4.dp),
    ) {
        // The rank column grows with the font: a fixed 30dp split "10" in two once the text was scaled up.
        val rankWidth = with(LocalDensity.current) { 30.sp.toDp() }
        Row(verticalAlignment = Alignment.Top) {
            Text(
                "$rank",
                style = type.figure,
                color = colors.accent,
                modifier = Modifier.width(rankWidth).padding(top = 3.dp).clearAndSetSemantics { contentDescription = rankDescription },
            )
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(
                        repo.ownerAvatarUrl,
                        repo.id.owner,
                        size = 20.dp,
                        placeholderColor = colors.surface,
                        placeholderContentColor = colors.inkMuted,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(repo.id.owner, style = type.secondary, color = colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(repo.id.name, style = type.name, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "+$gained",
                style = type.figure,
                color = colors.accent,
                modifier = Modifier.padding(top = 3.dp, end = 12.dp).clearAndSetSemantics { contentDescription = gainedDescription },
            )
        }
        Column(Modifier.fillMaxWidth().padding(start = 30.dp)) {
            repo.description?.let {
                // Capped so lines stay near a comfortable 65-75 characters on wide screens.
                Text(
                    it,
                    style = type.body,
                    color = colors.ink,
                    maxLines = descriptionMaxLines(LocalDensity.current.fontScale),
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = MaxMeasure).fillMaxWidth().padding(top = 6.dp, end = 12.dp).testTag(DESCRIPTION_TAG),
                )
            }
            MetaLine(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                stats = {
                    // Wraps only when even the stats alone don't fit, as at large font scales.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (LocalShowForge.current) ForgeMark(repo.id.forge, style = Soft.type.meta, color = Soft.colors.inkMuted)
                        repo.language?.let { language ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Forgejo names a language without its colour: the app's own table fills it in.
                                (parseHexColor(repo.languageColor) ?: languageColor(language))?.let { dot ->
                                    Box(Modifier.size(9.dp).background(dot, CircleShape))
                                    Spacer(Modifier.width(6.dp))
                                }
                                MetaText(language)
                            }
                        }
                        MetaText(stringResource(R.string.trending_stars, compactCount(repo.stars)))
                        MetaText(stringResource(R.string.trending_forks, compactCount(repo.forks)))
                    }
                },
                builders = {
                    if (repo.builtBy.isNotEmpty()) {
                        val builders = repo.builtBy.take(3)
                        val description = stringResource(R.string.trending_built_by_names, builders.joinToString { it.login })
                        Row(
                            Modifier.clearAndSetSemantics { contentDescription = description },
                            horizontalArrangement = Arrangement.spacedBy((-6).dp),
                        ) {
                            builders.forEach {
                                Avatar(
                                    it.avatarUrl,
                                    it.login,
                                    size = 22.dp,
                                    modifier = Modifier.border(2.dp, colors.ground, CircleShape),
                                    placeholderColor = colors.surface,
                                    placeholderContentColor = colors.inkMuted,
                                )
                            }
                        }
                    }
                },
                // Nothing to star with on a forge the app can't sign in to; the layout still needs its slot.
                toggle = { if (repo.id.forge.isBrowsable) StarToggle(item.starred == true, repo.id.fullName, onToggleStar) else Spacer(Modifier) },
            )
        }
    }
}

/**
 * The meta line: the stats on the left in a fixed order, the star toggle on the right edge, and the builders just
 * before it only when everything fits. Crowded rows drop the builders first (their description goes with them);
 * only then do the stats wrap onto a second line.
 */
@Composable
private fun MetaLine(
    stats: @Composable () -> Unit,
    builders: @Composable () -> Unit,
    toggle: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    Layout(contents = listOf(stats, builders, toggle), modifier = modifier) { (statsM, buildersM, toggleM), constraints ->
        val width = constraints.maxWidth
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val togglePlaced = toggleM.first().measure(loose)
        val room = (width - togglePlaced.width - gap).coerceAtLeast(0)
        // Builders only join when the stats fit on one line beside them; wrapped stats come first.
        val statsOnOneLine = statsM.first().maxIntrinsicWidth(Constraints.Infinity)
        val statsPlaced = statsM.first().measure(loose.copy(maxWidth = room))
        val buildersPlaced = buildersM.firstOrNull()?.measure(loose)?.takeIf { statsOnOneLine + gap + it.width <= room }
        val height = maxOf(togglePlaced.height, statsPlaced.height, buildersPlaced?.height ?: 0)
        layout(width, height) {
            statsPlaced.placeRelative(0, (height - statsPlaced.height) / 2)
            val toggleX = width - togglePlaced.width
            buildersPlaced?.let { it.placeRelative(toggleX - gap - it.width, (height - it.height) / 2) }
            togglePlaced.placeRelative(toggleX, (height - togglePlaced.height) / 2)
        }
    }
}

@Composable
private fun MetaText(text: String) {
    Text(text, style = Soft.type.meta, color = Soft.colors.inkMuted, maxLines = 1, softWrap = false)
}

@Composable
private fun StarToggle(starred: Boolean, fullName: String, onToggle: () -> Unit) {
    val colors = Soft.colors
    val label = stringResource(if (starred) R.string.trending_unstar else R.string.trending_star, fullName)
    IconToggleButton(
        checked = starred,
        onCheckedChange = { onToggle() },
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        Icon(
            if (starred) Icons.Filled.Star else Icons.Outlined.StarBorder,
            contentDescription = null,
            tint = if (starred) colors.accent else colors.inkMuted,
        )
    }
}

/** Three lines at normal sizes; with enlarged text nothing is cut, since reading is the point of the screen. */
internal fun descriptionMaxLines(fontScale: Float): Int = if (fontScale > 1f) Int.MAX_VALUE else 3

private val TrendingPeriod.label: Int
    get() = when (this) {
        TrendingPeriod.DAILY -> R.string.trending_period_daily
        TrendingPeriod.WEEKLY -> R.string.trending_period_weekly
        TrendingPeriod.MONTHLY -> R.string.trending_period_monthly
    }

private val TrendingPeriod.gainedLabel: Int
    get() = when (this) {
        TrendingPeriod.DAILY -> R.string.trending_gained_daily
        TrendingPeriod.WEEKLY -> R.string.trending_gained_weekly
        TrendingPeriod.MONTHLY -> R.string.trending_gained_monthly
    }

private val ForgeError.message: Int
    get() = when (this) {
        ForgeError.Network -> R.string.trending_error_offline
        is ForgeError.RateLimited -> R.string.trending_error_rate_limited
        else -> R.string.trending_error_unknown
    }
