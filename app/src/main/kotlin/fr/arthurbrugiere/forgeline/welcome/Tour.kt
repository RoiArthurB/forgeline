package fr.arthurbrugiere.forgeline.welcome

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.model.NotificationThread
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.ui.format.ForgeIcon
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSwitch
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTheme
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTonalButton
import fr.arthurbrugiere.forgeline.core.ui.soft.animationsEnabled
import fr.arthurbrugiere.forgeline.core.ui.soft.onBand
import fr.arthurbrugiere.forgeline.feed.FeedScreen
import fr.arthurbrugiere.forgeline.inbox.InboxScreen
import fr.arthurbrugiere.forgeline.signin.SignInForge
import fr.arthurbrugiere.forgeline.signin.label
import fr.arthurbrugiere.forgeline.signin.where
import fr.arthurbrugiere.forgeline.trending.TrendingScreen
import fr.arthurbrugiere.forgeline.trending.TrendingUiState
import fr.arthurbrugiere.forgeline.trending.TrendingViewModel
import fr.arthurbrugiere.forgeline.ui.sideSafeArea
import fr.arthurbrugiere.forgeline.you.NavigationRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The least height a step's screen is given: a header and a few rows. */
private val MinWindowHeight = 420.dp

/** The tour's steps, each the app's own screen: what it holds, shown rather than told. */
internal enum class TourStep { TRENDING, INBOX, FEED, SIGN_IN }

/**
 * The tour, with live Trending: what the app first opens on, signed in nowhere. [trending] is the Trending tab's own,
 * so what is picked here is what the tab opens on.
 */
@Composable
fun TourRoute(trending: TrendingViewModel, onBrowse: () -> Unit, onOpenRepo: (RepoId) -> Unit, onSignIn: (SignInForge) -> Unit) {
    val state by trending.state.collectAsStateWithLifecycle()
    TourScreen(
        trending = state,
        onTrendingPeriod = trending::selectPeriod,
        onTrendingForge = trending::selectForge,
        onBrowse = onBrowse,
        onOpenRepo = onOpenRepo,
        onSignIn = onSignIn,
    )
}

/**
 * A few steps over the ink band, each with the real screen rising from the foot of the page: Trending as it is today,
 * the Inbox with example threads to swipe, the Feed with example activity, then the forges to sign in to. Skip and
 * the last step's "Browse Trending" lead into the app; a repository tapped opens there; Back steps back.
 */
@Composable
internal fun TourScreen(
    trending: TrendingUiState,
    onTrendingPeriod: (fr.arthurbrugiere.forgeline.core.model.TrendingPeriod) -> Unit,
    onTrendingForge: (fr.arthurbrugiere.forgeline.core.model.ForgeInstance?) -> Unit,
    onBrowse: () -> Unit,
    onOpenRepo: (RepoId) -> Unit,
    onSignIn: (SignInForge) -> Unit,
    startAt: TourStep = TourStep.TRENDING,
    nowMillis: Long = remember { System.currentTimeMillis() },
) {
    val page = Soft.colors
    val band = remember(page) { page.onBand() }
    val pager = rememberPagerState(initialPage = startAt.ordinal) { TourStep.entries.size }
    val scope = rememberCoroutineScope()
    val animations = animationsEnabled()
    val goTo = { step: Int -> scope.launch { if (animations) pager.animateScrollToPage(step) else pager.scrollToPage(step) } }
    BackHandler(enabled = pager.currentPage > 0) { goTo(pager.currentPage - 1) }

    androidx.compose.runtime.CompositionLocalProvider(LocalPageColors provides page) {
        SoftTheme(band) {
            Column(Modifier.fillMaxSize().background(band.ground).windowInsetsPadding(WindowInsets.statusBars)) {
                Box(Modifier.fillMaxWidth().sideSafeArea(), contentAlignment = Alignment.TopCenter) {
                    SoftSwitch(
                        options = TourStep.entries.map { stringResource(it.tab) },
                        selected = pager.currentPage,
                        onSelect = { goTo(it) },
                        modifier = Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 4.dp),
                    )
                }
                HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), beyondViewportPageCount = 1, key = { it }) { index ->
                    val step = TourStep.entries[index]
                    // At a large font or on a landscape phone the words would leave the screen no room: the step then
                    // scrolls as a whole, and the screen keeps a height worth showing.
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val roomy = maxHeight
                        var wordsHeight by remember { mutableStateOf(0.dp) }
                        val density = LocalDensity.current
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).sideSafeArea(), horizontalAlignment = Alignment.CenterHorizontally) {
                            StepWords(step, Modifier.onSizeChanged { wordsHeight = with(density) { it.height.toDp() } })
                            Window(Modifier.height(maxOf(roomy - wordsHeight, minOf(roomy, MinWindowHeight)))) {
                                when (step) {
                                    TourStep.TRENDING -> TrendingScreen(
                                        state = trending,
                                        onPeriodChange = onTrendingPeriod,
                                        onRefresh = {},
                                        // A star needs an account: the last step is where one is made.
                                        onToggleStar = { goTo(TourStep.SIGN_IN.ordinal) },
                                        onOpenRepo = onOpenRepo,
                                        onErrorShown = {},
                                        onStarFailureShown = {},
                                        onSelectForge = onTrendingForge,
                                    )
                                    TourStep.INBOX -> ExampleInbox(nowMillis)
                                    TourStep.FEED -> FeedScreen(
                                        state = remember(nowMillis) { exampleFeed(nowMillis) },
                                        onRefresh = {}, onLoadMore = {}, onOpenRepo = {}, onOpenIssue = {}, onOpenUser = { _, _ -> }, onErrorShown = {},
                                        nowMillis = nowMillis,
                                    )
                                    TourStep.SIGN_IN -> Forges(onSignIn)
                                }
                            }
                        }
                    }
                }
                Footer(
                    last = pager.currentPage == TourStep.SIGN_IN.ordinal,
                    onSkip = onBrowse,
                    onNext = { goTo(pager.currentPage + 1) },
                )
            }
        }
    }
}

private val TourStep.tab: Int
    get() = when (this) {
        TourStep.TRENDING -> R.string.tab_trending
        TourStep.INBOX -> R.string.tab_inbox
        TourStep.FEED -> R.string.tab_feed
        TourStep.SIGN_IN -> R.string.sign_in
    }

/** The step's headline and what it says, on the band. */
@Composable
private fun StepWords(step: TourStep, modifier: Modifier = Modifier) {
    val (title, body) = when (step) {
        TourStep.TRENDING -> R.string.tour_trending_title to R.string.tour_trending_body
        TourStep.INBOX -> R.string.tour_inbox_title to R.string.tour_inbox_body
        TourStep.FEED -> R.string.tour_feed_title to R.string.tour_feed_body
        TourStep.SIGN_IN -> R.string.tour_sign_in_title to R.string.tour_sign_in_body
    }
    Column(modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 18.dp)) {
        Text(stringResource(title), style = Soft.type.title, color = Soft.colors.ink, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(6.dp))
        Text(stringResource(body), style = Soft.type.lead, color = Soft.colors.inkMuted, modifier = Modifier.widthIn(max = SoftTokens.MaxMeasure))
    }
}

/**
 * Where the step's screen is shown: the page's own colours on a sheet rising from the foot of the band, its top corners
 * rounded, the screen inside at its real size. The screen's bars are taken care of here, so it doesn't leave room for them.
 */
@Composable
private fun Window(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val stage = Soft.colors
    // The page under the band's palette: what the app's screens are drawn with.
    val screen = LocalPageColors.current
    Box(
        modifier
            .widthIn(max = SoftTokens.MaxReadingWidth)
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            // A bezel in the band's own tile colour, so the screen reads as a phone's even where its header is ink too.
            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            .background(stage.surface)
            .padding(start = 6.dp, end = 6.dp, top = 6.dp)
            .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
            .consumeWindowInsets(WindowInsets.systemBars),
    ) {
        SoftTheme(screen) { Box(Modifier.fillMaxSize().background(screen.ground)) { content() } }
    }
}

/** The Inbox with the example threads, which swipes and the menu act on here as they would for real. */
@Composable
private fun ExampleInbox(nowMillis: Long) {
    val threads = remember { mutableStateListOf<NotificationThread>().apply { addAll(exampleThreads(nowMillis)) } }
    // Once every thread is cleared, they come back, so the swipe can be tried again.
    LaunchedEffect(threads.isEmpty()) {
        if (threads.isEmpty()) {
            delay(1_200)
            threads.addAll(exampleThreads(nowMillis))
        }
    }
    fun update(thread: NotificationThread, change: (NotificationThread) -> NotificationThread?) {
        val at = threads.indexOfFirst { it.key == thread.key }.takeIf { it >= 0 } ?: return
        change(threads[at])?.let { threads[at] = it } ?: threads.removeAt(at)
    }
    InboxScreen(
        state = exampleInbox(threads.toList()),
        onSelectFilter = {},
        onRefresh = {},
        onOpen = {},
        onMarkRead = { update(it) { thread -> thread.copy(unread = false) } },
        onMarkUnread = { update(it) { thread -> thread.copy(unread = true) } },
        onMarkDone = { update(it) { null } },
        onUnsubscribe = { update(it) { null } },
        onErrorShown = {},
        onActionFailureShown = {},
        nowMillis = nowMillis,
    )
}

/** The forges to sign in to, as the sign-in screen lists them: one tap opens that forge's own sign-in. */
@Composable
private fun Forges(onSignIn: (SignInForge) -> Unit) {
    val colors = Soft.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        for (forge in SignInForge.entries) {
            NavigationRow(stringResource(forge.label), onClick = { onSignIn(forge) }, summary = stringResource(forge.where)) {
                ForgeIcon(forge.icon, size = 18.dp, tint = colors.ink, contentDescription = null)
            }
        }
    }
}

/** Skip and Next, or on the last step the way into the app without an account. */
@Composable
private fun Footer(last: Boolean, onSkip: () -> Unit, onNext: () -> Unit) {
    val screen = LocalPageColors.current
    // On the page's colours, so the screen above runs into it, as the app's own bar sits under its lists.
    SoftTheme(screen) {
        Box(
            Modifier.fillMaxWidth().background(screen.ground).windowInsetsPadding(WindowInsets.navigationBars).sideSafeArea(),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (last) {
                    SoftTonalButton(stringResource(R.string.browse_trending), onSkip)
                } else {
                    SoftTonalButton(stringResource(R.string.tour_skip), onSkip)
                    Spacer(Modifier.weight(1f))
                    SoftButton(stringResource(R.string.tour_next), onNext)
                }
            }
        }
    }
}

/** The page's own colours, kept for the screens shown on the band's. */
private val LocalPageColors = androidx.compose.runtime.staticCompositionLocalOf<fr.arthurbrugiere.forgeline.core.ui.soft.SoftColors> { error("No page colours") }
