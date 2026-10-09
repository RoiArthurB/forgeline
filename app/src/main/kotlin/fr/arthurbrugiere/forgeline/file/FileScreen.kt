package fr.arthurbrugiere.forgeline.file

import fr.arthurbrugiere.forgeline.ui.ShareLinkButton
import fr.arthurbrugiere.forgeline.ui.ZoomablePicture
import fr.arthurbrugiere.forgeline.ui.sideSafeArea
import fr.arthurbrugiere.forgeline.navigation.openForgeLink
import android.content.ClipData
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.markdown.CodeHighlighter
import fr.arthurbrugiere.forgeline.core.markdown.ForgelineMarkdown
import fr.arthurbrugiere.forgeline.core.markdown.rememberReadmeState
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.navigation.FileRoute
import fr.arthurbrugiere.forgeline.navigation.ForgeLinks
import fr.arthurbrugiere.forgeline.navigation.RepoRoute
import fr.arthurbrugiere.forgeline.navigation.IssueRoute
import fr.arthurbrugiere.forgeline.navigation.UserRoute
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.repo.Loadable
import fr.arthurbrugiere.forgeline.ui.rememberCustomTabOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.sp
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.ui.listBottomPadding
import androidx.compose.runtime.getValue

/** Lets tests wait until off-main-thread highlighting has landed. */
const val CODE_HIGHLIGHTED_TAG = "code-highlighted"
const val CODE_PLAIN_TAG = "code-plain"

@Composable
fun FileRoute(
    route: FileRoute,
    onBack: () -> Unit,
    onOpenRepo: (RepoId) -> Unit,
    onOpenIssue: (IssueRef) -> Unit,
    onOpenUser: (String) -> Unit,
) {
    val target = FileTarget(route.repo, route.path, route.ref)
    val viewModel = hiltViewModel<FileViewModel, FileViewModel.Factory>(
        key = "${target.id.key}@${target.ref}:${target.path}",
    ) { it.create(target) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openUrl = rememberCustomTabOpener()
    val openDiscussion = fr.arthurbrugiere.forgeline.ui.LocalOpenDiscussion.current
    val openRelease = fr.arthurbrugiere.forgeline.ui.LocalOpenRelease.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    FileScreen(
        state = state,
        onBack = onBack,
        onRetry = viewModel::retry,
        onOpenInBrowser = openUrl,
        onLinkClick = { url -> openForgeLink(url, target.id.forge, onOpenRepo, onOpenIssue, onOpenUser, openUrl, onOpenRelease = openRelease, onOpenDiscussion = openDiscussion) },
        onCopy = { text -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(target.name, text))) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileScreen(
    state: FileUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onLinkClick: (String) -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Soft.colors
    val content = state.content
    Column(modifier.fillMaxSize().background(colors.ground).sideSafeArea(), horizontalAlignment = Alignment.CenterHorizontally) {
        SoftHeader(
            tint = colors.fields[1],
            onBack = onBack,
            backDescription = stringResource(R.string.navigate_up),
            actions = {
                if (content is Loadable.Loaded && content.value is FileContent.Text) {
                    IconButton(onClick = { onCopy(content.value.text) }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.file_copy), tint = colors.ink)
                    }
                }
                ShareLinkButton(state.webUrl, state.target.name)
                IconButton(onClick = { onOpenInBrowser(state.webUrl) }) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.repo_open_on_forge, state.target.id.forge.displayName), tint = colors.ink)
                }
            },
        ) {
            Column {
                Text(state.target.name, style = Soft.type.name, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${state.target.id.fullName} · ${state.target.path}",
                    style = Soft.type.meta,
                    color = colors.inkMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (content) {
                Loadable.Idle, Loadable.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accent, trackColor = colors.surface)
                }
                is Loadable.Failed -> if (content.error is ForgeError.Http && content.error.status == 413) {
                    SoftNotice(
                        stringResource(R.string.file_too_large_title),
                        stringResource(R.string.file_too_large_body),
                        action = stringResource(R.string.repo_open_on_forge, state.target.id.forge.displayName),
                        onAction = { onOpenInBrowser(state.webUrl) },
                    )
                } else {
                    SoftNotice(
                        stringResource(R.string.file_error_title),
                        stringResource(if (content.error == ForgeError.Network) R.string.trending_error_offline else R.string.sign_in_error_unknown),
                        action = stringResource(R.string.retry),
                        onAction = onRetry,
                    )
                }
                is Loadable.Loaded -> when (val file = content.value) {
                    is FileContent.Picture -> Box(Modifier.fillMaxSize().padding(bottom = listBottomPadding())) {
                        ZoomablePicture(
                            file.url, state.target.name,
                            onOpenInBrowser = { onOpenInBrowser(state.webUrl) },
                            browserLabel = stringResource(R.string.repo_open_on_forge, state.target.id.forge.displayName),
                        )
                    }
                    FileContent.Binary -> SoftNotice(
                        stringResource(R.string.file_binary_title),
                        stringResource(R.string.file_binary_body),
                        action = stringResource(R.string.repo_open_on_forge, state.target.id.forge.displayName),
                        onAction = { onOpenInBrowser(state.webUrl) },
                    )
                    is FileContent.Text -> if (CodeHighlighter.isMarkdown(state.target.name)) {
                        MarkdownFile(file.text, state, onLinkClick)
                    } else {
                        CodeFile(file.text, state.target.name)
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownFile(text: String, state: FileUiState, onLinkClick: (String) -> Unit) {
    val colors = Soft.colors
    val parsed = rememberReadmeState(text, state.readmeContext, colors.isDark)
    if (parsed == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accent, trackColor = colors.surface) }
    } else {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = listBottomPadding()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ForgelineMarkdown(parsed, onLinkClick, Modifier.widthIn(max = SoftTokens.MaxReadingWidth).padding(horizontal = 20.dp, vertical = 16.dp))
        }
    }
}

@Composable
private fun CodeFile(text: String, fileName: String) {
    val colors = Soft.colors
    val darkTheme = colors.isDark
    // Highlighting runs off the main thread; plain text shows meanwhile, so nothing waits on it.
    val highlighted by produceState<List<AnnotatedString>?>(initialValue = null, text, fileName, darkTheme) {
        value = withContext(Dispatchers.Default) { CodeHighlighter.lines(CodeHighlighter.highlight(text, fileName, darkTheme)) }
    }
    val lines = highlighted ?: remember(text) { CodeHighlighter.lines(AnnotatedString(text)) }
    val gutter = (lines.size.toString().length * 9 + 20).dp
    val code = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp)
    LazyColumn(
        Modifier.fillMaxSize().testTag(if (highlighted != null) CODE_HIGHLIGHTED_TAG else CODE_PLAIN_TAG),
        contentPadding = PaddingValues(top = 12.dp, bottom = listBottomPadding()),
    ) {
        itemsIndexed(lines) { index, line ->
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp)) {
                Text(
                    "${index + 1}",
                    modifier = Modifier.width(gutter).padding(end = 12.dp),
                    style = code,
                    color = colors.inkMuted,
                    textAlign = TextAlign.End,
                )
                Text(line, style = code, color = colors.ink)
            }
        }
    }
}
