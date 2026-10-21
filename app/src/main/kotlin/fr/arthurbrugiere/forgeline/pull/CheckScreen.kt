package fr.arthurbrugiere.forgeline.pull

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.markdown.ForgelineMarkdown
import fr.arthurbrugiere.forgeline.core.markdown.ReadmeContext
import fr.arthurbrugiere.forgeline.core.markdown.rememberReadmeState
import fr.arthurbrugiere.forgeline.core.model.CheckReport
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.blobBaseUrl
import fr.arthurbrugiere.forgeline.core.model.rawBaseUrl
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftIconButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSnackbarHost
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftStatusBarScrim
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTonalButton
import fr.arthurbrugiere.forgeline.navigation.CheckRoute
import fr.arthurbrugiere.forgeline.navigation.openForgeLink
import fr.arthurbrugiere.forgeline.ui.LocalBottomBarSpace
import fr.arthurbrugiere.forgeline.ui.Message
import fr.arthurbrugiere.forgeline.ui.listBottomPadding
import fr.arthurbrugiere.forgeline.ui.message
import fr.arthurbrugiere.forgeline.ui.referenceLinks
import fr.arthurbrugiere.forgeline.ui.relative
import fr.arthurbrugiere.forgeline.ui.rememberCustomTabOpener
import fr.arthurbrugiere.forgeline.ui.rememberNow
import fr.arthurbrugiere.forgeline.ui.sideSafeArea

@Composable
fun CheckRoute(
    route: CheckRoute,
    onBack: () -> Unit,
    onOpenRepo: (RepoId) -> Unit,
    onOpenIssue: (IssueRef) -> Unit,
    onOpenUser: (String) -> Unit,
) {
    val repo = route.repo
    val viewModel = hiltViewModel<CheckViewModel, CheckViewModel.Factory>(key = "check:${repo.key}/${route.checkId}") { it.create(repo, route.checkId, route.checkName) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openUrl = rememberCustomTabOpener()
    CheckScreen(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onOpenUrl = openUrl,
        onLinkClick = { url -> openForgeLink(url, repo.forge, onOpenRepo, onOpenIssue, onOpenUser, openUrl) },
        onErrorShown = viewModel::errorShown,
    )
}

/**
 * One check of a pull request: where it stands, then what it wrote about what it found, set to be read. The service
 * that ran it keeps more on its own page, one tap away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckScreen(
    state: CheckUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onLinkClick: (String) -> Unit,
    onErrorShown: () -> Unit,
    modifier: Modifier = Modifier,
    nowMillis: Long = rememberNow(state.report),
) {
    val colors = Soft.colors
    val report = state.report
    val snackbar = remember { SnackbarHostState() }
    val refreshFailed = stringResource(R.string.trending_refresh_failed)
    LaunchedEffect(state.error, report != null) {
        if (state.error != null && report != null) {
            snackbar.showSnackbar(refreshFailed)
            onErrorShown()
        }
    }
    // A report rarely links into the repository; what does is read against its default branch.
    val context = ReadmeContext(rawBaseUrl = state.repo.rawBaseUrl("HEAD"), blobBaseUrl = state.repo.blobBaseUrl("HEAD"), references = state.repo.referenceLinks())
    val webUrl = report?.url ?: "${state.repo.webUrl}/runs/${state.checkId}"
    val listState = rememberLazyListState()
    Box(modifier.fillMaxSize().background(colors.ground)) {
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = state.isRefreshing && report != null,
            onRefresh = onRefresh,
            state = pullState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = state.isRefreshing && report != null,
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
                        onBack = onBack,
                        backDescription = stringResource(R.string.navigate_up),
                        actions = {
                            SoftIconButton(onClick = { onOpenUrl(webUrl) }) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.OpenInNew,
                                    contentDescription = stringResource(R.string.repo_open_on_forge, state.repo.forge.displayName),
                                    tint = Soft.colors.ink,
                                )
                            }
                        },
                    ) {
                        Header(state, nowMillis)
                    }
                }
                when {
                    report == null && state.error != null -> item(key = "error") {
                        SoftNotice(
                            stringResource(R.string.check_error_title),
                            stringResource(state.error.message),
                            action = stringResource(R.string.retry),
                            onAction = onRefresh,
                        )
                    }
                    report == null -> item(key = "loading") { SoftLoadingRows(stringResource(R.string.check_loading), rows = 3, leadingDot = false) }
                    else -> {
                        if (report.summary == null && report.text == null) {
                            item(key = "empty") { Message(stringResource(R.string.check_no_report)) }
                        }
                        report.summary?.let { summary -> item(key = "summary") { Report(summary, context, onLinkClick) } }
                        report.text?.let { text -> item(key = "text") { Report(text, context, onLinkClick) } }
                        report.detailsUrl?.let { url ->
                            item(key = "details") {
                                Box(Section.padding(horizontal = 20.dp, vertical = 12.dp)) {
                                    SoftTonalButton(stringResource(R.string.check_open_details, report.app ?: report.name), onClick = { onOpenUrl(url) })
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

private val Section = Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth()

@Composable
private fun Header(state: CheckUiState, nowMillis: Long) {
    val colors = Soft.colors
    val report = state.report
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(report?.app ?: state.repo.fullName, style = Soft.type.secondary, color = colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(report?.name ?: state.name, style = Soft.type.hero, color = colors.ink, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
        if (report == null) return@Column
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).background(colors.surface, SoftTokens.TileCorner), contentAlignment = Alignment.Center) {
                Icon(report.state.icon, contentDescription = null, tint = colors.ink, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(standing(report, nowMillis), style = Soft.type.body, color = colors.ink)
        }
        report.title?.let { Text(it, style = Soft.type.secondary, color = colors.ink, maxLines = 3, overflow = TextOverflow.Ellipsis) }
    }
}

/** "Failed · 3 days ago": where the check stands and, once it is over, since when. */
@Composable
private fun standing(report: CheckReport, nowMillis: Long): String =
    listOfNotNull(stringResource(report.state.label), report.completedAt?.let { relative(it, nowMillis) }).joinToString(" · ")

@Composable
private fun Report(markdown: String, context: ReadmeContext, onLinkClick: (String) -> Unit) {
    val parsed = rememberReadmeState(markdown, context, Soft.colors.isDark)
    if (parsed == null) {
        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Soft.colors.accent, trackColor = Soft.colors.surface)
        }
    } else {
        ForgelineMarkdown(parsed, onLinkClick, Section.padding(horizontal = 20.dp, vertical = 12.dp))
    }
}
