package fr.arthurbrugiere.forgeline.actions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.forge.ArtifactArchive
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.markdown.ReadmeContext
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.artifactUrl
import fr.arthurbrugiere.forgeline.core.model.blobBaseUrl
import fr.arthurbrugiere.forgeline.core.model.rawBaseUrl
import fr.arthurbrugiere.forgeline.core.ui.format.formatBytes
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftIconButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSectionTitle
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftStatusBarScrim
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.rowHairline
import fr.arthurbrugiere.forgeline.core.ui.soft.softPressable
import fr.arthurbrugiere.forgeline.navigation.ArtifactRoute
import fr.arthurbrugiere.forgeline.navigation.openForgeLink
import fr.arthurbrugiere.forgeline.ui.LocalOpenCommit
import fr.arthurbrugiere.forgeline.ui.LocalOpenDiscussion
import fr.arthurbrugiere.forgeline.ui.LocalOpenRelease
import fr.arthurbrugiere.forgeline.ui.LocalOpenWiki
import fr.arthurbrugiere.forgeline.ui.Markdown
import fr.arthurbrugiere.forgeline.ui.listBottomPadding
import fr.arthurbrugiere.forgeline.ui.message
import fr.arthurbrugiere.forgeline.ui.rememberCustomTabOpener
import fr.arthurbrugiere.forgeline.ui.sideSafeArea

@Composable
fun ArtifactRoute(
    route: ArtifactRoute,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onOpenRepo: (RepoId) -> Unit,
    onOpenIssue: (IssueRef) -> Unit,
    onOpenUser: (String) -> Unit,
) {
    val repo = route.repo
    val viewModel = hiltViewModel<ArtifactViewModel, ArtifactViewModel.Factory>(key = "${repo.key}/artifacts/${route.artifactId}") {
        it.create(repo, route.runId, route.artifact)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openUrl = rememberCustomTabOpener()
    val openRelease = LocalOpenRelease.current
    val openDiscussion = LocalOpenDiscussion.current
    val openWiki = LocalOpenWiki.current
    val openCommit = LocalOpenCommit.current
    ArtifactScreen(
        state = state,
        onBack = onBack,
        onRetry = viewModel::refresh,
        onSelect = viewModel::select,
        onSignIn = onSignIn,
        onOpenInBrowser = { openUrl(repo.artifactUrl(route.runId, route.artifactId)) },
        onLinkClick = { url ->
            openForgeLink(
                url, repo.forge, onOpenRepo, onOpenIssue, onOpenUser, openUrl,
                onOpenRelease = openRelease, onOpenDiscussion = openDiscussion, onOpenWiki = openWiki, onOpenCommit = openCommit,
            )
        },
    )
}

/**
 * A run's artifact, read for the Markdown files it holds: the report a workflow wrote for people, which the run's page
 * shows as its summary and GitHub's API doesn't give. One file shows at once; several are listed to pick from.
 */
@Composable
fun ArtifactScreen(
    state: ArtifactUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSelect: (String?) -> Unit,
    onSignIn: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Soft.colors
    val repo = state.repo
    val forge = repo.forge.displayName
    val documents = state.documents
    val shown = state.shown
    // Back from a file picked among several lists them again before leaving.
    BackHandler(enabled = state.selected != null && (documents?.size ?: 0) > 1) { onSelect(null) }
    val context = ReadmeContext(rawBaseUrl = repo.rawBaseUrl("HEAD"), blobBaseUrl = repo.blobBaseUrl("HEAD"))
    val listState = rememberLazyListState()
    Box(modifier.fillMaxSize().background(colors.ground)) {
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
                        SoftIconButton(onClick = onOpenInBrowser) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.repo_open_on_forge, forge), tint = Soft.colors.ink)
                        }
                    },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.artifact_of, repo.fullName, formatBytes(state.artifact.sizeBytes)), style = Soft.type.secondary, color = Soft.colors.inkMuted)
                        Text(state.artifact.name, style = Soft.type.detailTitle, color = Soft.colors.ink, modifier = Modifier.semantics { heading() })
                    }
                }
            }
            val error = state.error
            when {
                documents == null && error != null -> item(key = "error") {
                    when {
                        error == ForgeError.Unauthorized -> SoftNotice(
                            stringResource(R.string.artifact_sign_in_title),
                            stringResource(R.string.artifact_sign_in_body, forge),
                            action = stringResource(R.string.sign_in),
                            onAction = onSignIn,
                        )
                        error is ForgeError.Http && error.status == 413 -> SoftNotice(
                            stringResource(R.string.artifact_too_large_title),
                            stringResource(R.string.artifact_too_large_body, formatBytes(state.artifact.sizeBytes), formatBytes(ArtifactArchive.MAX_ARCHIVE_BYTES.toLong())),
                            action = stringResource(R.string.repo_open_on_forge, forge),
                            onAction = onOpenInBrowser,
                        )
                        else -> SoftNotice(
                            stringResource(R.string.artifact_error_title),
                            stringResource(error.message),
                            action = stringResource(R.string.retry),
                            onAction = onRetry,
                        )
                    }
                }
                documents == null -> item(key = "loading") { SoftLoadingRows(stringResource(R.string.artifact_loading), rows = 3) }
                documents.isEmpty() -> item(key = "empty") {
                    SoftNotice(
                        stringResource(R.string.artifact_empty_title),
                        stringResource(R.string.artifact_empty_body),
                        action = stringResource(R.string.repo_open_on_forge, forge),
                        onAction = onOpenInBrowser,
                    )
                }
                shown != null -> item(key = "document-${shown.path}") {
                    Column(Modifier.widthIn(max = SoftTokens.MaxMeasure).fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
                        Text(shown.path, style = Soft.type.meta, color = colors.inkMuted, modifier = Modifier.padding(bottom = 8.dp))
                        Markdown(shown.markdown, context, onLinkClick)
                    }
                }
                else -> {
                    item(key = "documents-title") { SoftSectionTitle(pluralStringResource(R.plurals.artifact_documents, documents.size, documents.size)) }
                    items(documents, key = { "path-${it.path}" }) { document ->
                        Row(
                            Modifier
                                .widthIn(max = SoftTokens.MaxReadingWidth)
                                .fillMaxWidth()
                                .softPressable { onSelect(document.path) }
                                .rowHairline(start = 64.dp)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RowIcon(Icons.Outlined.Description, colors.surface)
                            Spacer(Modifier.width(12.dp))
                            Text(document.path, style = Soft.type.body, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
        SoftStatusBarScrim(scrolled)
    }
}
