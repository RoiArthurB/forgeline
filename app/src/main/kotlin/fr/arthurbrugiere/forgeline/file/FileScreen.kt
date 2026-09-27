package fr.arthurbrugiere.forgeline.file

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
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
import fr.arthurbrugiere.forgeline.repo.Loadable
import fr.arthurbrugiere.forgeline.ui.EmptyState
import fr.arthurbrugiere.forgeline.ui.rememberCustomTabOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lets tests wait until off-main-thread highlighting has landed. */
const val CODE_HIGHLIGHTED_TAG = "code-highlighted"
const val CODE_PLAIN_TAG = "code-plain"

@Composable
fun FileRoute(route: FileRoute, onBack: () -> Unit, onOpenRepo: (RepoId) -> Unit) {
    val target = FileTarget(RepoId(route.owner, route.name), route.path, route.ref)
    val viewModel = hiltViewModel<FileViewModel, FileViewModel.Factory>(
        key = "${target.id.fullName}@${target.ref}:${target.path}",
    ) { it.create(target) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openUrl = rememberCustomTabOpener()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    FileScreen(
        state = state,
        onBack = onBack,
        onRetry = viewModel::retry,
        onOpenInBrowser = openUrl,
        onLinkClick = { url ->
            when (val destination = ForgeLinks.routeFor(url)) {
                is RepoRoute -> onOpenRepo(RepoId(destination.owner, destination.name))
                else -> if (!url.startsWith("#")) openUrl(url)
            }
        },
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
    val content = state.content
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.target.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${state.target.id.fullName} · ${state.target.path}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.navigate_up))
                    }
                },
                actions = {
                    if (content is Loadable.Loaded && content.value is FileContent.Text) {
                        IconButton(onClick = { onCopy((content.value as FileContent.Text).text) }) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.file_copy))
                        }
                    }
                    IconButton(onClick = { onOpenInBrowser(state.webUrl) }) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.repo_open_on_forge))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (content) {
                Loadable.Idle, Loadable.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is Loadable.Failed -> if (content.error is ForgeError.Http && content.error.status == 413) {
                    EmptyState(
                        icon = Icons.Outlined.Description,
                        title = stringResource(R.string.file_too_large_title),
                        body = stringResource(R.string.file_too_large_body),
                        actionLabel = stringResource(R.string.repo_open_on_forge),
                        onAction = { onOpenInBrowser(state.webUrl) },
                    )
                } else {
                    EmptyState(
                        icon = Icons.Outlined.CloudOff,
                        title = stringResource(R.string.file_error_title),
                        body = stringResource(if (content.error == ForgeError.Network) R.string.trending_error_offline else R.string.sign_in_error_unknown),
                        actionLabel = stringResource(R.string.retry),
                        onAction = onRetry,
                    )
                }
                is Loadable.Loaded -> when (val file = content.value) {
                    FileContent.Binary -> EmptyState(
                        icon = Icons.Outlined.Description,
                        title = stringResource(R.string.file_binary_title),
                        body = stringResource(R.string.file_binary_body),
                        actionLabel = stringResource(R.string.repo_open_on_forge),
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
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val parsed = rememberReadmeState(text, state.readmeContext, darkTheme)
    if (parsed == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ForgelineMarkdown(parsed, onLinkClick, Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun CodeFile(text: String, fileName: String) {
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // Highlighting runs off the main thread; plain text shows meanwhile, so nothing waits on it.
    val highlighted by produceState<List<AnnotatedString>?>(initialValue = null, text, fileName, darkTheme) {
        value = withContext(Dispatchers.Default) { CodeHighlighter.lines(CodeHighlighter.highlight(text, fileName, darkTheme)) }
    }
    val lines = highlighted ?: remember(text) { CodeHighlighter.lines(AnnotatedString(text)) }
    val gutter = (lines.size.toString().length * 9 + 12).dp
    LazyColumn(
        Modifier.fillMaxSize().testTag(if (highlighted != null) CODE_HIGHLIGHTED_TAG else CODE_PLAIN_TAG),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        itemsIndexed(lines) { index, line ->
            Row(Modifier.fillMaxWidth().padding(end = 12.dp)) {
                Text(
                    "${index + 1}",
                    modifier = Modifier.width(gutter).padding(end = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.End,
                )
                Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
