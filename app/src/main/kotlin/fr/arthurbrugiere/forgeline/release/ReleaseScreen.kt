package fr.arthurbrugiere.forgeline.release

import fr.arthurbrugiere.forgeline.core.model.releaseUrl
import fr.arthurbrugiere.forgeline.ui.ShareLinkButton
import fr.arthurbrugiere.forgeline.issue.referenceLinks
import androidx.compose.foundation.background
import fr.arthurbrugiere.forgeline.ui.rememberNow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.actions.RowIcon
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.markdown.ForgelineMarkdown
import fr.arthurbrugiere.forgeline.core.markdown.ReadmeContext
import fr.arthurbrugiere.forgeline.core.markdown.rememberReadmeState
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.Release
import fr.arthurbrugiere.forgeline.core.model.ReleaseAsset
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.blobBaseUrl
import fr.arthurbrugiere.forgeline.core.model.rawBaseUrl
import fr.arthurbrugiere.forgeline.core.ui.format.compactCount
import fr.arthurbrugiere.forgeline.core.ui.format.formatBytes
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftPill
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftStatusBarScrim
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTag
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.softPressable
import fr.arthurbrugiere.forgeline.issue.emoji
import fr.arthurbrugiere.forgeline.navigation.ReleaseRoute
import fr.arthurbrugiere.forgeline.navigation.openForgeLink
import fr.arthurbrugiere.forgeline.ui.Avatar
import fr.arthurbrugiere.forgeline.ui.LocalBottomBarSpace
import fr.arthurbrugiere.forgeline.ui.Message
import fr.arthurbrugiere.forgeline.ui.listBottomPadding
import fr.arthurbrugiere.forgeline.ui.message
import fr.arthurbrugiere.forgeline.ui.relative
import fr.arthurbrugiere.forgeline.ui.rememberCustomTabOpener
import fr.arthurbrugiere.forgeline.ui.sideSafeArea

@Composable
fun ReleaseRoute(
    route: ReleaseRoute,
    onBack: () -> Unit,
    onOpenRepo: (RepoId) -> Unit,
    onOpenIssue: (IssueRef) -> Unit,
    onOpenUser: (String) -> Unit,
    onOpenRelease: (RepoId, String) -> Unit,
) {
    val repo = route.repo
    val viewModel = hiltViewModel<ReleaseViewModel, ReleaseViewModel.Factory>(key = "release:${repo.key}@${route.tag}") { it.create(repo, route.tag) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openUrl = rememberCustomTabOpener()
    val openDiscussion = fr.arthurbrugiere.forgeline.ui.LocalOpenDiscussion.current
    ReleaseScreen(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onOpenRepo = { onOpenRepo(repo) },
        onOpenUser = onOpenUser,
        // A file to download is the browser's to fetch and keep.
        onDownload = openUrl,
        onOpenInBrowser = openUrl,
        onLinkClick = { url -> openForgeLink(url, repo.forge, onOpenRepo, onOpenIssue, onOpenUser, openUrl, onOpenRelease = onOpenRelease, onOpenDiscussion = openDiscussion) },
        onErrorShown = viewModel::errorShown,
    )
}

/**
 * One release: what it is called and where it stands, its notes set to be read, then what it ships (the files
 * published with it, and the source at its tag), each one tap from downloading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleaseScreen(
    state: ReleaseUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenRepo: () -> Unit,
    onOpenUser: (String) -> Unit,
    onDownload: (String) -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onLinkClick: (String) -> Unit,
    onErrorShown: () -> Unit,
    modifier: Modifier = Modifier,
    nowMillis: Long = rememberNow(state.release),
) {
    val colors = Soft.colors
    val release = state.release
    val snackbar = remember { SnackbarHostState() }
    val refreshFailed = stringResource(R.string.trending_refresh_failed)
    LaunchedEffect(state.error, release != null) {
        if (state.error != null && release != null) {
            snackbar.showSnackbar(refreshFailed)
            onErrorShown()
        }
    }
    // Relative links in the notes resolve against the repository at this tag.
    val context = ReadmeContext(rawBaseUrl = state.repo.rawBaseUrl(state.tag), blobBaseUrl = state.repo.blobBaseUrl(state.tag), references = state.repo.referenceLinks())
    val webUrl = release?.webUrl ?: state.repo.releaseUrl(state.tag)
    val listState = rememberLazyListState()
    Box(modifier.fillMaxSize().background(colors.ground)) {
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = state.isRefreshing && release != null,
            onRefresh = onRefresh,
            state = pullState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = state.isRefreshing && release != null,
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
                        tint = colors.fields[0],
                        onBack = onBack,
                        backDescription = stringResource(R.string.navigate_up),
                        actions = {
                            ShareLinkButton(webUrl, "${state.repo.fullName} ${state.tag}")
                            IconButton(onClick = { onOpenInBrowser(webUrl) }) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.OpenInNew,
                                    contentDescription = stringResource(R.string.repo_open_on_forge, state.repo.forge.displayName),
                                    tint = colors.ink,
                                )
                            }
                        },
                    ) {
                        Header(state, nowMillis, onOpenRepo, onOpenUser)
                    }
                }
                when {
                    release == null && state.error != null -> item(key = "error") {
                        SoftNotice(
                            stringResource(R.string.release_error_title),
                            stringResource(if (state.error is ForgeError.Http && state.error.status == 404) R.string.release_error_not_found else state.error.message),
                            action = stringResource(R.string.retry),
                            onAction = onRefresh,
                        )
                    }
                    release == null -> item(key = "loading") { SoftLoadingRows(stringResource(R.string.release_loading), rows = 3, leadingDot = false) }
                    else -> {
                        item(key = "notes") {
                            val notes = release.body
                            if (notes == null) Message(stringResource(R.string.release_no_notes)) else Notes(notes, context, onLinkClick)
                        }
                        if (release.reactions.isNotEmpty()) {
                            item(key = "reactions") {
                                FlowRow(
                                    Section.padding(horizontal = 20.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    release.reactions.forEach { (reaction, count) -> SoftTag("${reaction.emoji} $count") }
                                }
                            }
                        }
                        if (release.assets.isNotEmpty()) {
                            item(key = "assets-title") {
                                SectionTitle(pluralStringResource(R.plurals.release_assets, release.assets.size, release.assets.size))
                            }
                            items(release.assets, key = { "asset-${it.name}" }) { asset -> AssetRow(asset, onClick = { onDownload(asset.url) }) }
                        }
                        val zip = release.zipUrl
                        val tar = release.tarUrl
                        if (zip != null || tar != null) {
                            item(key = "source-title") { SectionTitle(stringResource(R.string.release_source)) }
                            zip?.let { url -> item(key = "source-zip") { DownloadRow(Icons.Outlined.FolderZip, stringResource(R.string.release_source_zip), null) { onDownload(url) } } }
                            tar?.let { url -> item(key = "source-tar") { DownloadRow(Icons.Outlined.FolderZip, stringResource(R.string.release_source_tar), null) { onDownload(url) } } }
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

private val Section = Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth()

@Composable
private fun Header(state: ReleaseUiState, nowMillis: Long, onOpenRepo: () -> Unit, onOpenUser: (String) -> Unit) {
    val colors = Soft.colors
    val release = state.release
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // The repository it belongs to, one tap away.
        Row(
            Modifier
                .clip(SoftTokens.Pill)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.issue_open_repo), onClick = onOpenRepo)
                .heightIn(min = 48.dp)
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                state.repo.fullName,
                style = Soft.type.secondary,
                color = colors.inkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(18.dp))
        }
        Text(release?.name ?: state.tag, style = Soft.type.hero, color = colors.ink, modifier = Modifier.semantics { heading() })
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            SoftPill(state.tag, Icons.Outlined.Sell, colors.ground, monospace = true)
            if (release?.isLatest == true) SoftTag(stringResource(R.string.release_latest), background = colors.ground)
            if (release?.isPrerelease == true) SoftTag(stringResource(R.string.repo_prerelease), background = colors.ground)
        }
        if (release == null) return@Column
        val author = release.author
        val published = release.publishedAt?.let { relative(it, nowMillis) }
        if (author != null || published != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = author != null) { author?.let { onOpenUser(it.login) } },
            ) {
                if (author != null) Avatar(author.avatarUrl, author.login, size = 24.dp, placeholderColor = colors.ground, placeholderContentColor = colors.inkMuted)
                Text(
                    when {
                        author != null && published != null -> stringResource(R.string.release_published_by, author.login, published)
                        author != null -> author.login
                        else -> stringResource(R.string.release_published, published.orEmpty())
                    },
                    style = Soft.type.secondary,
                    color = colors.inkMuted,
                )
            }
        }
    }
}

@Composable
private fun Notes(markdown: String, context: ReadmeContext, onLinkClick: (String) -> Unit) {
    val parsed = rememberReadmeState(markdown, context, Soft.colors.isDark)
    if (parsed == null) {
        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Soft.colors.accent, trackColor = Soft.colors.surface)
        }
    } else {
        ForgelineMarkdown(parsed, onLinkClick, Section.padding(horizontal = 20.dp, vertical = 12.dp))
    }
}

/** A quiet word above a group of rows. */
@Composable
private fun SectionTitle(text: String) {
    Text(text, style = Soft.type.label, color = Soft.colors.inkMuted, modifier = Section.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp).semantics { heading() })
}

@Composable
private fun AssetRow(asset: ReleaseAsset, onClick: () -> Unit) {
    val size = formatBytes(asset.sizeBytes)
    DownloadRow(
        Icons.AutoMirrored.Outlined.InsertDriveFile,
        asset.name,
        asset.downloads?.let { pluralStringResource(R.plurals.release_asset_meta, it, size, compactCount(it)) } ?: size,
        onClick,
    )
}

/** Something to download: what it is, how big and how wanted, and the glyph that says a tap fetches it. */
@Composable
private fun DownloadRow(icon: ImageVector, name: String, meta: String?, onClick: () -> Unit) {
    val colors = Soft.colors
    Row(
        Section
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .softPressable(role = Role.Button, onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon, colors.surface)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = Soft.type.body, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (meta != null) Text(meta, style = Soft.type.meta, color = colors.inkMuted)
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Outlined.FileDownload, contentDescription = stringResource(R.string.release_download), tint = colors.inkMuted, modifier = Modifier.size(20.dp))
    }
}
