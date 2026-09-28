package fr.arthurbrugiere.forgeline.trending

import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import fr.arthurbrugiere.forgeline.core.ui.format.compactCount
import fr.arthurbrugiere.forgeline.core.ui.format.parseHexColor
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftDark
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLight
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTheme
import fr.arthurbrugiere.forgeline.session.SessionState
import fr.arthurbrugiere.forgeline.ui.Avatar
import fr.arthurbrugiere.forgeline.ui.LocalOpenSearch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.text.NumberFormat

@Composable
fun TrendingRoute(
    session: SessionState,
    onSignIn: () -> Unit,
    onOpenRepo: (RepoId) -> Unit,
    viewModel: TrendingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val signedIn = session is SessionState.SignedIn
    TrendingScreen(
        state = state,
        onPeriodChange = viewModel::selectPeriod,
        onRefresh = viewModel::refresh,
        onToggleStar = { repo -> if (signedIn) viewModel.toggleStar(repo) else onSignIn() },
        onOpenRepo = onOpenRepo,
        onErrorShown = viewModel::errorShown,
        onStarFailureShown = viewModel::starFailureShown,
        onReadThrough = viewModel::readThrough,
    )
}

// Lazy list layout: the header field, the status line, then the rows.
private const val FIRST_ROW = 2
private val MaxReadingWidth = 720.dp
internal val MaxMeasure = 580.dp
internal const val DESCRIPTION_TAG = "trending_description"
private val Pill = RoundedCornerShape(percent = 50)
private val SoftCorner = RoundedCornerShape(20.dp)

// The one motion language: a soft spring, a little lively, never bouncy.
private val SoftSpring = spring<Float>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)

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
) {
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

    // Follows the app's theme choice (system, light or dark), and goes true black with the AMOLED setting.
    val background = MaterialTheme.colorScheme.background
    val colors = when {
        background.luminance() >= 0.5f -> SoftLight
        background == Color.Black -> SoftDark.copy(ground = Color.Black, surface = Color(0xFF16141D))
        else -> SoftDark
    }

    SoftTheme(colors) {
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
        ReportReading(listState, state, onReadThrough)

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
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
                ) {
                    item(key = "header", contentType = "header") { HeaderField(state.period, onPeriodChange, animations) }
                    if (hasItems) {
                        item(key = "status", contentType = "status") {
                            StatusLine(
                                updatedAtMillis = state.updatedAtMillis,
                                nowMillis = nowMillis,
                                resumeAt = resumeAt,
                                onResume = {
                                    val above = with(density) { 88.dp.roundToPx() }
                                    scope.launch { listState.animateScrollToItem(FIRST_ROW + resumeAt!! + 1, -above) }
                                },
                            )
                        }
                    }
                    when {
                        !hasItems && state.error != null -> item(key = "error") { ErrorMessage(state.error.message, onRetry = onRefresh) }
                        // Fetched, and genuinely nothing: say so instead of loading forever.
                        !hasItems && state.updatedAtMillis != null && !state.isRefreshing -> item(key = "empty") { EmptyMessage(onRefresh) }
                        !hasItems -> item(key = "loading") { LoadingRows() }
                        else -> itemsIndexed(
                            state.items,
                            key = { _, item -> item.repo.id.fullName },
                            contentType = { _, _ -> "row" },
                        ) { index, item ->
                            val rise = animations && index < 8 &&
                                (periodJustChanged || SystemClock.uptimeMillis() - changedAt < 600)
                            Column(Modifier.widthIn(max = MaxReadingWidth).fillMaxWidth().then(riseIn(item.repo.id, state.period, index, rise))) {
                                RepoRow(
                                    rank = index + 1,
                                    item = item,
                                    period = state.period,
                                    onToggleStar = { onToggleStar(item.repo.id) },
                                    onOpen = { onOpenRepo(item.repo.id) },
                                )
                                if (index == resumeAt) StoppedHere()
                            }
                        }
                    }
                }
            }
            // Once the tinted field has scrolled away, the status bar gets the ground behind it.
            val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(if (scrolled) colors.ground.copy(alpha = 0.94f) else Color.Transparent),
            )
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars)) { data ->
                Snackbar(data, shape = RoundedCornerShape(16.dp), containerColor = colors.ink, contentColor = colors.ground, actionColor = colors.thumb)
            }
        }
    }
}

/** Reports the furthest row read (fully on screen) whenever the reader gets further down. */
@Composable
private fun ReportReading(listState: LazyListState, state: TrendingUiState, onReadThrough: (Int) -> Unit) {
    val indexByKey = remember(state.items) { state.items.withIndex().associate { (index, item) -> item.repo.id.fullName to index } }
    LaunchedEffect(listState, indexByKey, state.period) {
        var furthest = -1
        snapshotFlow {
            val layout = listState.layoutInfo
            layout.visibleItemsInfo
                .filter { it.offset + it.size <= layout.viewportEndOffset }
                .mapNotNull { indexByKey[it.key] }
                .maxOrNull()
        }
            .distinctUntilChanged()
            .collect { index ->
                if (index != null && index > furthest) {
                    furthest = index
                    onReadThrough(index)
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
            progress.animateTo(1f, SoftSpring)
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
private fun animationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
}

@Composable
private fun HeaderField(period: TrendingPeriod, onSelect: (TrendingPeriod) -> Unit, animations: Boolean) {
    val colors = Soft.colors
    val openSearch = LocalOpenSearch.current
    val field by animateColorAsState(colors.fields[period.ordinal], tween(if (animations) 400 else 0), label = "field")
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
            .background(field),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = MaxReadingWidth)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.tab_trending),
                    style = Soft.type.title,
                    color = colors.ink,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                if (openSearch != null) {
                    IconButton(onClick = openSearch) {
                        Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.search), tint = colors.ink)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            PeriodSwitch(period, onSelect, animations, Modifier.padding(end = 12.dp))
        }
    }
}

/**
 * A pill track with an ember thumb that springs to the chosen period. The pill is as tall as its tallest label
 * (48dp minimum), so large font scales grow it instead of cutting words off, and every label stays centred.
 */
@Composable
private fun PeriodSwitch(selected: TrendingPeriod, onSelect: (TrendingPeriod) -> Unit, animations: Boolean, modifier: Modifier = Modifier) {
    val colors = Soft.colors
    val periods = TrendingPeriod.entries
    val thumbIndex by animateFloatAsState(
        selected.ordinal.toFloat(),
        if (animations) spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow) else tween(0),
        label = "thumb",
    )
    val minHeight = with(LocalDensity.current) { 48.dp.roundToPx() }
    Layout(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(colors.track)
            .padding(4.dp)
            .selectableGroup(),
        content = {
            Box(
                Modifier
                    .shadow(6.dp, RoundedCornerShape(24.dp), ambientColor = colors.thumb, spotColor = colors.thumb)
                    .background(colors.thumb, RoundedCornerShape(24.dp)),
            )
            periods.forEach { period ->
                val isSelected = period == selected
                Box(
                    Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(period) })
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // Shrinks a word that can't wrap (at very large font scales) instead of cutting it off.
                    Text(
                        stringResource(period.label),
                        style = Soft.type.control,
                        color = if (isSelected) colors.onThumb else colors.inkMuted,
                        textAlign = TextAlign.Center,
                        autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = Soft.type.control.fontSize),
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val segment = constraints.maxWidth / periods.size
        // Measure each label at its real width first; the tallest one sets the height for all.
        val labels = measurables.drop(1).map { it.measure(Constraints(minWidth = segment, maxWidth = segment, minHeight = minHeight)) }
        val height = labels.maxOf { it.height }
        val thumb = measurables.first().measure(Constraints.fixed(segment, height))
        layout(constraints.maxWidth, height) {
            thumb.placeRelative((thumbIndex * segment).toInt(), 0)
            labels.forEachIndexed { index, label -> label.placeRelative(index * segment, (height - label.height) / 2) }
        }
    }
}

@Composable
private fun StatusLine(updatedAtMillis: Long?, nowMillis: Long, resumeAt: Int?, onResume: () -> Unit) {
    val colors = Soft.colors
    Row(
        Modifier.widthIn(max = MaxReadingWidth).fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp).heightIn(min = 48.dp),
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
                    .clip(Pill)
                    .clickable(role = Role.Button, onClick = onResume)
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(R.string.trending_resume, resumeAt + 2), style = Soft.type.label, color = colors.accent)
                Icon(Icons.Outlined.ArrowDownward, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
            }
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
    // The soft pressed surface: a palette tint behind the row while it's pressed or focused, not a ripple.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(SoftCorner)
            .background(if (pressed || focused) colors.surface else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
            .padding(start = 12.dp, end = 4.dp, top = 14.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                "$rank",
                style = type.figure,
                color = colors.accent,
                modifier = Modifier.width(30.dp).padding(top = 3.dp).clearAndSetSemantics { contentDescription = rankDescription },
            )
            Column(Modifier.weight(1f)) {
                Text(repo.id.owner, style = type.secondary, color = colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                        repo.language?.let { language ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                parseHexColor(repo.languageColor)?.let { dot ->
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
                toggle = { StarToggle(item.starred == true, repo.id.fullName, onToggleStar) },
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

/** The soft marker left where the last browse stopped. */
@Composable
private fun StoppedHere() {
    val colors = Soft.colors
    Box(Modifier.fillMaxWidth().padding(start = 50.dp, top = 6.dp, bottom = 10.dp)) {
        Row(
            Modifier.clip(Pill).background(colors.fields[0]).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).background(colors.thumb, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.trending_stopped_here), style = Soft.type.label, color = colors.ink)
        }
    }
}

@Composable
private fun LoadingRows() {
    val colors = Soft.colors
    val description = stringResource(R.string.trending_loading)
    Column(
        Modifier.widthIn(max = MaxReadingWidth).fillMaxWidth().padding(top = 16.dp).clearAndSetSemantics { contentDescription = description },
    ) {
        repeat(5) { index ->
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp)) {
                Box(Modifier.size(14.dp).background(colors.surface, CircleShape))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.fillMaxWidth(0.25f).height(12.dp).background(colors.surface, Pill))
                    Box(Modifier.fillMaxWidth(if (index % 2 == 0) 0.6f else 0.45f).height(20.dp).background(colors.surface, Pill))
                    Box(Modifier.fillMaxWidth(0.95f).height(12.dp).background(colors.surface, Pill))
                    Box(Modifier.fillMaxWidth(0.7f).height(12.dp).background(colors.surface, Pill))
                }
            }
        }
    }
}

@Composable
private fun ErrorMessage(message: Int, onRetry: () -> Unit) {
    Notice(stringResource(R.string.trending_error_title), stringResource(message), stringResource(R.string.retry), onRetry)
}

@Composable
private fun EmptyMessage(onRefresh: () -> Unit) {
    Notice(stringResource(R.string.trending_empty_title), stringResource(R.string.trending_empty_body), stringResource(R.string.trending_refresh), onRefresh)
}

@Composable
private fun Notice(title: String, body: String, action: String, onAction: () -> Unit) {
    val colors = Soft.colors
    Column(Modifier.widthIn(max = MaxReadingWidth).fillMaxWidth().padding(horizontal = 20.dp, vertical = 32.dp)) {
        Text(title, style = Soft.type.name, color = colors.ink)
        Spacer(Modifier.height(6.dp))
        Text(body, style = Soft.type.body, color = colors.inkMuted)
        Spacer(Modifier.height(20.dp))
        Box(
            Modifier
                .clip(Pill)
                .background(colors.thumb)
                .clickable(role = Role.Button, onClick = onAction)
                .heightIn(min = 48.dp)
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(action, style = Soft.type.control, color = colors.onThumb)
        }
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
