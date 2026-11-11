package fr.arthurbrugiere.forgeline.file

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.markdown.CodeHighlighter
import fr.arthurbrugiere.forgeline.core.markdown.ForgelineMarkdown
import fr.arthurbrugiere.forgeline.core.markdown.currentCodeColors
import fr.arthurbrugiere.forgeline.core.markdown.rememberReadmeState
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftIconButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTonalButton
import fr.arthurbrugiere.forgeline.navigation.FileRoute
import fr.arthurbrugiere.forgeline.navigation.ForgeLinks
import fr.arthurbrugiere.forgeline.navigation.IssueRoute
import fr.arthurbrugiere.forgeline.navigation.RepoRoute
import fr.arthurbrugiere.forgeline.navigation.UserRoute
import fr.arthurbrugiere.forgeline.navigation.openForgeLink
import fr.arthurbrugiere.forgeline.repo.Loadable
import fr.arthurbrugiere.forgeline.ui.HeaderRepoLink
import fr.arthurbrugiere.forgeline.ui.ShareLinkButton
import fr.arthurbrugiere.forgeline.ui.ZoomablePicture
import fr.arthurbrugiere.forgeline.ui.lineNumberWidth
import fr.arthurbrugiere.forgeline.ui.listBottomPadding
import fr.arthurbrugiere.forgeline.ui.rememberCustomTabOpener
import fr.arthurbrugiere.forgeline.ui.sideSafeArea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lets tests wait until off-main-thread highlighting has landed. */
const val CODE_HIGHLIGHTED_TAG = "code-highlighted"
const val CODE_PLAIN_TAG = "code-plain"

/** Marks each line a link named, which the file opens on. */
const val CODE_LINKED_LINE_TAG = "code-linked-line"

@Composable
fun FileRoute(
    route: FileRoute,
    onBack: () -> Unit,
    onOpenRepo: (RepoId) -> Unit,
    onOpenIssue: (IssueRef) -> Unit,
    onOpenUser: (String) -> Unit,
    /** Opens the commits that changed the file, newest first. */
    onOpenHistory: (FileTarget) -> Unit = {},
    /** Opens the file with, beside each run of lines, the commit that last changed it. */
    onOpenBlame: (FileTarget) -> Unit = {},
) {
    val target = FileTarget(route.repo, route.path, route.ref)
    val viewModel = hiltViewModel<FileViewModel, FileViewModel.Factory>(
        key = "${target.id.key}@${target.ref}:${target.path}",
    ) { it.create(target) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openUrl = rememberCustomTabOpener()
    val openDiscussion = fr.arthurbrugiere.forgeline.ui.LocalOpenDiscussion.current
    val openWiki = fr.arthurbrugiere.forgeline.ui.LocalOpenWiki.current
    val openCommit = fr.arthurbrugiere.forgeline.ui.LocalOpenCommit.current
    val openFile = fr.arthurbrugiere.forgeline.ui.LocalOpenFile.current
    val openRelease = fr.arthurbrugiere.forgeline.ui.LocalOpenRelease.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    FileScreen(
        state = state,
        onBack = onBack,
        onRetry = viewModel::retry,
        onOpenInBrowser = openUrl,
        onLinkClick = { url -> openForgeLink(url, target.id.forge, onOpenRepo, onOpenIssue, onOpenUser, openUrl, onOpenRelease = openRelease, onOpenDiscussion = openDiscussion, onOpenWiki = openWiki, onOpenCommit = openCommit, onOpenFile = openFile) },
        onCopy = { text -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(target.name, text))) } },
        onOpenHistory = { onOpenHistory(target) },
        onOpenBlame = { onOpenBlame(target) },
        lines = route.lines,
        onOpenRepo = { onOpenRepo(target.id) },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FileScreen(
    state: FileUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onLinkClick: (String) -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenHistory: () -> Unit = {},
    onOpenBlame: () -> Unit = {},
    /** The lines a link named, if it named any: the file opens on them, marked. */
    lines: IntRange? = null,
    /** Opens the file's repository; null where there is no page for it. */
    onOpenRepo: (() -> Unit)? = null,
) {
    val colors = Soft.colors
    val content = state.content
    Column(modifier.fillMaxSize().background(colors.ground).sideSafeArea(), horizontalAlignment = Alignment.CenterHorizontally) {
        SoftHeader(
            onBack = onBack,
            backDescription = stringResource(R.string.navigate_up),
            actions = {
                if (content is Loadable.Loaded && content.value is FileContent.Text) {
                    SoftIconButton(onClick = { onCopy(content.value.text) }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.file_copy), tint = Soft.colors.ink)
                    }
                }
                ShareLinkButton(state.webUrl, state.target.name)
                SoftIconButton(onClick = { onOpenInBrowser(state.webUrl) }) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.repo_open_on_forge, state.target.id.forge.displayName), tint = Soft.colors.ink)
                }
            },
        ) {
            Column {
                Text(state.target.name, style = Soft.type.name, color = Soft.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                // A file reached from a link leads on to its repository.
                if (onOpenRepo != null) {
                    HeaderRepoLink(state.target.id.fullName, onClick = onOpenRepo)
                    Text(state.target.path, style = Soft.type.meta, color = Soft.colors.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                } else {
                    Text(
                        "${state.target.id.fullName} · ${state.target.path}",
                        style = Soft.type.meta,
                        color = Soft.colors.inkMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Where the file comes from: who changed it, and for text, who last left each line.
                FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftTonalButton(stringResource(R.string.history_open), onClick = onOpenHistory)
                    if (state.canBlame && content is Loadable.Loaded && content.value is FileContent.Text) {
                        SoftTonalButton(stringResource(R.string.blame_open), onClick = onOpenBlame)
                    }
                }
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
                    // The whole path, since some languages are told by where a file sits (.github/workflows/*.yml).
                    // A link to lines of a Markdown file means its source (as `?plain=1` does on GitHub): it shows as code.
                    is FileContent.Text -> if (CodeHighlighter.isMarkdown(state.target.path) && lines == null) {
                        MarkdownFile(file.text, state, onLinkClick)
                    } else {
                        CodeFile(file.text, state.target.path, lines)
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
            ForgelineMarkdown(parsed, onLinkClick, Modifier.widthIn(max = SoftTokens.MaxReadingWidth).padding(horizontal = 20.dp, vertical = 16.dp), selectable = true)
        }
    }
}

@Composable
private fun CodeFile(text: String, path: String, linked: IntRange? = null) {
    // The code theme's colors, page included: the Code theme setting may show code light in a dark app, or dark in a light one.
    val colors = currentCodeColors
    val darkTheme = colors.isDark
    // Highlighting runs off the main thread; plain text shows meanwhile, so nothing waits on it.
    val highlighted by produceState<List<AnnotatedString>?>(initialValue = null, text, path, darkTheme) {
        value = withContext(Dispatchers.Default) { CodeHighlighter.lines(CodeHighlighter.highlight(text, path, darkTheme)) }
    }
    val lines = highlighted ?: remember(text) { CodeHighlighter.lines(AnnotatedString(text)) }
    val code = Soft.type.code
    // The numbers' column and the 12dp before the code: 9dp a figure at the usual size, as wide as they are at a larger one.
    val gutter = lineNumberWidth(lines.size, code, floor = (lines.size.toString().length * 9 + 8).dp) + 12.dp
    // Opened on the lines a link named, with two lines before them to read them in.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = linked?.let { (it.first - 3).coerceIn(0, (lines.size - 1).coerceAtLeast(0)) } ?: 0)
    // A long press selects the code, to copy a part of it; the line numbers stay out of what is copied.
    SelectionContainer {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().background(colors.ground).testTag(if (highlighted != null) CODE_HIGHLIGHTED_TAG else CODE_PLAIN_TAG),
            contentPadding = PaddingValues(top = 12.dp, bottom = listBottomPadding()),
        ) {
            itemsIndexed(lines) { index, line ->
                val marked = linked != null && index + 1 in linked
                val mark = if (marked) Modifier.background(colors.accentSoft).testTag(CODE_LINKED_LINE_TAG) else Modifier
                Row(Modifier.fillMaxWidth().then(mark).padding(start = 4.dp, end = 16.dp)) {
                    DisableSelection {
                        Text(
                            "${index + 1}",
                            modifier = Modifier.width(gutter).padding(end = 12.dp),
                            style = code,
                            color = colors.inkMuted,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    Text(line, style = code, color = colors.ink)
                }
            }
        }
    }
}
