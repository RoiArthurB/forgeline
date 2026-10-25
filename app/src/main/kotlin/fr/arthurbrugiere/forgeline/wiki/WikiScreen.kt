package fr.arthurbrugiere.forgeline.wiki

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.markdown.ReadmeContext
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.wikiBaseUrl
import fr.arthurbrugiere.forgeline.core.model.wikiPageOf
import fr.arthurbrugiere.forgeline.core.model.wikiRawBaseUrl
import fr.arthurbrugiere.forgeline.core.model.wikiTitleOf
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftIconButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSnackbarHost
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftStatusBarScrim
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTonalButton
import fr.arthurbrugiere.forgeline.navigation.WikiRoute
import fr.arthurbrugiere.forgeline.navigation.openForgeLink
import fr.arthurbrugiere.forgeline.ui.LocalBottomBarSpace
import fr.arthurbrugiere.forgeline.ui.LocalOpenDiscussion
import fr.arthurbrugiere.forgeline.ui.LocalOpenRelease
import fr.arthurbrugiere.forgeline.ui.Markdown
import fr.arthurbrugiere.forgeline.ui.SayOnce
import fr.arthurbrugiere.forgeline.ui.ShareLinkButton
import fr.arthurbrugiere.forgeline.ui.listBottomPadding
import fr.arthurbrugiere.forgeline.ui.referenceLinks
import fr.arthurbrugiere.forgeline.ui.rememberCustomTabOpener
import fr.arthurbrugiere.forgeline.ui.sideSafeArea

@Composable
fun WikiRoute(
    route: WikiRoute,
    onBack: () -> Unit,
    onOpenPage: (RepoId, String) -> Unit,
    onOpenRepo: (RepoId) -> Unit,
    onOpenIssue: (IssueRef) -> Unit,
    onOpenUser: (String) -> Unit,
) {
    val repo = route.repo
    val viewModel = hiltViewModel<WikiViewModel, WikiViewModel.Factory>(key = "${repo.key}#wiki-${route.page}") { it.create(repo, route.page) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openUrl = rememberCustomTabOpener()
    val openRelease = LocalOpenRelease.current
    val openDiscussion = LocalOpenDiscussion.current
    WikiScreen(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onOpenRepo = onOpenRepo,
        onOpenPage = { onOpenPage(repo, it) },
        onOpenInBrowser = openUrl,
        onLinkClick = { url -> openForgeLink(url, repo.forge, onOpenRepo, onOpenIssue, onOpenUser, openUrl, onOpenRelease = openRelease, onOpenDiscussion = openDiscussion) },
        onErrorShown = viewModel::errorShown,
    )
}

/**
 * A page of a repository's wiki: its title, its words, then every page of the wiki, folded, where the forge lists
 * them. A link to another page of the same wiki opens it here; any other goes where links go.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WikiScreen(
    state: WikiUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenRepo: (RepoId) -> Unit,
    onOpenPage: (String) -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onLinkClick: (String) -> Unit,
    onErrorShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Soft.colors
    val page = state.page
    val repo = state.repo
    val forge = repo.forge.displayName
    val context = ReadmeContext(rawBaseUrl = repo.wikiRawBaseUrl, blobBaseUrl = repo.wikiBaseUrl, references = repo.referenceLinks())
    val openLink = { url: String -> repo.wikiPageOf(url)?.let(onOpenPage) ?: onLinkClick(url) }
    val snackbar = remember { SnackbarHostState() }
    SayOnce(stringResource(R.string.trending_refresh_failed).takeIf { state.error != null && page != null }, snackbar, onErrorShown)
    var allPages by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    Box(modifier.fillMaxSize().background(colors.ground)) {
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = state.isLoading && page != null,
            onRefresh = onRefresh,
            state = pullState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = state.isLoading && page != null,
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
                    val title = page?.title ?: wikiTitleOf(state.key)
                    SoftHeader(
                        onBack = onBack,
                        backDescription = stringResource(R.string.navigate_up),
                        actions = {
                            ShareLinkButton(state.webUrl, title)
                            SoftIconButton(onClick = { onOpenInBrowser(state.webUrl) }) {
                                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.repo_open_on_forge, forge), tint = Soft.colors.ink)
                            }
                        },
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                stringResource(R.string.wiki_of, repo.fullName),
                                style = Soft.type.secondary,
                                color = Soft.colors.inkMuted,
                                modifier = Modifier.clickable { onOpenRepo(repo) },
                            )
                            Text(title, style = Soft.type.detailTitle, color = Soft.colors.ink, modifier = Modifier.semantics { heading() })
                        }
                    }
                }
                when {
                    page == null && state.error != null -> item(key = "error") {
                        val missing = state.error is ForgeError.Http && state.error.status == 404
                        SoftNotice(
                            stringResource(if (missing) R.string.wiki_missing_title else R.string.wiki_error_title),
                            when {
                                missing -> stringResource(R.string.wiki_missing_body, forge)
                                state.error == ForgeError.Network -> stringResource(R.string.trending_error_offline)
                                else -> stringResource(R.string.sign_in_error_unknown)
                            },
                            action = stringResource(if (missing) R.string.repo_open_on_forge else R.string.retry, forge),
                            onAction = { if (missing) onOpenInBrowser(state.webUrl) else onRefresh() },
                        )
                    }
                    page == null -> item(key = "loading") { SoftLoadingRows(stringResource(R.string.issue_loading), rows = 3) }
                    else -> item(key = "body") {
                        Column(Modifier.widthIn(max = SoftTokens.MaxMeasure).fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
                            Markdown(page.body.ifBlank { stringResource(R.string.wiki_empty) }, context, openLink)
                        }
                    }
                }
                val others = state.pages.orEmpty()
                if (others.isNotEmpty()) {
                    item(key = "pages") {
                        Box(Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp)) {
                            SoftTonalButton(
                                stringResource(if (allPages) R.string.wiki_pages_hide else R.string.wiki_pages_show, others.size),
                                onClick = { allPages = !allPages },
                            )
                        }
                    }
                    if (allPages) {
                        items(others, key = { "page-${it.key}" }) { other ->
                            val here = other.key == state.key
                            Text(
                                other.title,
                                style = Soft.type.body,
                                color = if (here) colors.inkMuted else colors.ink,
                                modifier = Modifier
                                    .widthIn(max = SoftTokens.MaxReadingWidth)
                                    .fillMaxWidth()
                                    .clickable(enabled = !here) { onOpenPage(other.key) }
                                    .heightIn(min = 48.dp)
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                            )
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
