package fr.arthurbrugiere.forgeline.actions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.LogLineKind
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftStatusBarScrim
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.softPressable
import fr.arthurbrugiere.forgeline.navigation.JobLogRoute
import fr.arthurbrugiere.forgeline.repo.Loadable
import fr.arthurbrugiere.forgeline.ui.listBottomPadding
import fr.arthurbrugiere.forgeline.ui.message
import fr.arthurbrugiere.forgeline.ui.rememberCustomTabOpener
import kotlinx.coroutines.launch

@Composable
fun JobLogRoute(route: JobLogRoute, onBack: () -> Unit, onSignIn: () -> Unit) {
    val repo = RepoId(route.owner, route.name)
    val viewModel = hiltViewModel<JobLogViewModel, JobLogViewModel.Factory>(key = "${repo.fullName}/jobs/${route.jobId}") {
        it.create(repo, route.jobId, route.jobName)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openUrl = rememberCustomTabOpener()
    JobLogScreen(
        state = state,
        onBack = onBack,
        onRetry = viewModel::load,
        onToggleGroup = viewModel::toggleGroup,
        onSignIn = onSignIn,
        onOpenInBrowser = { openUrl("https://github.com/${repo.fullName}/actions/runs/${route.runId}/job/${route.jobId}") },
    )
}

@Composable
fun JobLogScreen(
    state: JobLogUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onToggleGroup: (Int) -> Unit,
    onSignIn: () -> Unit,
    onOpenInBrowser: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Soft.colors
    val palette = if (colors.isDark) AnsiDark else AnsiLight
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val rows = state.rows
    val errors = state.errorRows
    // Each tap jumps to the next error, round and round.
    var nextError by rememberSaveable { mutableIntStateOf(0) }
    Box(modifier.fillMaxSize().background(colors.ground)) {
        LazyColumn(
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(bottom = listBottomPadding()),
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
        ) {
            item(key = "header") {
                SoftHeader(
                    tint = if (errors.isNotEmpty()) colors.fields[0] else colors.fields[1],
                    onBack = onBack,
                    backDescription = stringResource(R.string.navigate_up),
                    actions = {
                        IconButton(onClick = onOpenInBrowser) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.repo_open_on_forge), tint = colors.ink)
                        }
                    },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(state.jobName, style = Soft.type.title.copy(fontSize = 26.sp, lineHeight = 30.sp), color = colors.ink)
                        if (errors.isNotEmpty()) {
                            Row(
                                Modifier
                                    .clip(SoftTokens.Pill)
                                    .background(colors.ground)
                                    .softPressable(role = Role.Button) {
                                        val target = errors[nextError % errors.size]
                                        nextError++
                                        // The header is item 0: rows start at 1. Leave a little context above.
                                        scope.launch { listState.animateScrollToItem((target + 1 - 2).coerceAtLeast(1)) }
                                    }
                                    .heightIn(min = 48.dp)
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(pluralStringResource(R.plurals.log_jump_to_error, errors.size, errors.size), style = Soft.type.control, color = colors.ink)
                            }
                        }
                    }
                }
            }
            when (val log = state.log) {
                Loadable.Idle, Loadable.Loading -> item(key = "loading") { SoftLoadingRows(stringResource(R.string.log_loading), rows = 6, leadingDot = false) }
                is Loadable.Failed -> item(key = "failed") {
                    if (log.error == ForgeError.Unauthorized) {
                        SoftNotice(stringResource(R.string.log_sign_in_title), stringResource(R.string.log_sign_in_body), action = stringResource(R.string.sign_in), onAction = onSignIn)
                    } else {
                        SoftNotice(stringResource(R.string.log_failed), stringResource(log.error.message), action = stringResource(R.string.retry), onAction = onRetry)
                    }
                }
                is Loadable.Loaded -> {
                    if (rows.isEmpty()) {
                        item(key = "empty") { SoftNotice(stringResource(R.string.log_empty_title), stringResource(R.string.log_empty_body)) }
                    }
                    items(rows, key = { it.key }) { row ->
                        when (row) {
                            is LogRow.Header -> GroupHeader(row, onClick = { onToggleGroup(row.entry) })
                            is LogRow.Line -> LogLine(row, palette)
                        }
                    }
                }
            }
        }
        val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
        SoftStatusBarScrim(scrolled)
    }
}

private val LogText = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp)

private val LineModifier = Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth()

@Composable
private fun GroupHeader(row: LogRow.Header, onClick: () -> Unit) {
    val colors = Soft.colors
    Row(
        LineModifier
            .padding(horizontal = 8.dp)
            .softPressable(role = Role.Button, onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (row.open) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = colors.inkMuted,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(row.title, style = LogText.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = colors.ink, maxLines = 2, modifier = Modifier.weight(1f))
        if (!row.open) Text(row.lines.toString(), style = Soft.type.meta, color = colors.inkMuted)
    }
}

@Composable
private fun LogLine(row: LogRow.Line, palette: AnsiPalette) {
    val colors = Soft.colors
    val line = row.line
    val text = remember(line.text, palette) { ansiAnnotated(line.text, palette) }
    val tint = when (line.kind) {
        LogLineKind.ERROR -> colors.accent
        LogLineKind.WARNING -> palette.colors[3]
        LogLineKind.COMMAND, LogLineKind.NOTICE -> palette.colors[4]
        LogLineKind.DEBUG -> colors.inkMuted
        LogLineKind.PLAIN -> colors.ink
    }
    SelectionContainer(
        LineModifier
            .padding(horizontal = 8.dp)
            .then(if (line.kind == LogLineKind.ERROR) Modifier.background(colors.fields[0]) else Modifier)
            .padding(start = if (row.inGroup != null) 32.dp else 8.dp, end = 8.dp, top = 1.dp, bottom = 1.dp),
    ) {
        Text(text, style = LogText, color = tint)
    }
}
