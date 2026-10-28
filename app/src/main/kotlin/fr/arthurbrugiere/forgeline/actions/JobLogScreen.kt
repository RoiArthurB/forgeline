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
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.LogLineKind
import fr.arthurbrugiere.forgeline.core.model.LogStep
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RunConclusion
import fr.arthurbrugiere.forgeline.core.model.RunStatus
import fr.arthurbrugiere.forgeline.core.model.RunStep
import fr.arthurbrugiere.forgeline.core.model.jobUrl
import fr.arthurbrugiere.forgeline.core.ui.format.formatBytes
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftIconButton
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
import fr.arthurbrugiere.forgeline.ui.rememberNow
import fr.arthurbrugiere.forgeline.ui.sideSafeArea
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration

@Composable
fun JobLogRoute(route: JobLogRoute, onBack: () -> Unit, onSignIn: () -> Unit) {
    val repo = route.repo
    val viewModel = hiltViewModel<JobLogViewModel, JobLogViewModel.Factory>(key = "${repo.key}/jobs/${route.jobId}") {
        it.create(repo, route.runId, route.jobId, route.jobName)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openUrl = rememberCustomTabOpener()
    // GitHub has no live log: while the job runs its steps are checked again, then the log is read once published.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state.isLive) {
        if (!state.isLive) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(JobLogViewModel.POLL_MILLIS)
                viewModel.poll()
            }
        }
    }
    // The running step's timer ticks every second.
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.isRunning) {
        while (state.isRunning) {
            nowMillis = System.currentTimeMillis()
            delay(1_000)
        }
    }
    JobLogScreen(
        state = state,
        onBack = onBack,
        onRetry = viewModel::load,
        onToggleStep = viewModel::toggleStep,
        onOpenFailed = viewModel::openFailed,
        onRetryStep = viewModel::retryStep,
        onLoadEarlier = viewModel::loadEarlier,
        onToggleGroup = viewModel::toggleGroup,
        onScrolledToStep = viewModel::scrolledToStep,
        onSignIn = onSignIn,
        onOpenInBrowser = { openUrl(repo.jobUrl(route.runId, route.jobId)) },
        nowMillis = nowMillis,
    )
}

/**
 * A job: its steps, folded, and nothing of their log read until one is opened. An opened step shows its last lines,
 * where a failure and its summary are, and its earlier lines when asked: a log of 60,000 lines is never read whole.
 */
@Composable
fun JobLogScreen(
    state: JobLogUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onToggleStep: (Int) -> Unit,
    onOpenFailed: () -> Int?,
    onRetryStep: (Int) -> Unit,
    onLoadEarlier: (Int) -> Unit,
    onToggleGroup: (step: Int, fromEnd: Int) -> Unit,
    onSignIn: () -> Unit,
    onOpenInBrowser: () -> Unit,
    modifier: Modifier = Modifier,
    onScrolledToStep: () -> Unit = {},
    nowMillis: Long = rememberNow(state.job),
) {
    val colors = Soft.colors
    val palette = if (colors.isDark) AnsiDark else AnsiLight
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val rows = state.rows
    val errors = state.errorRows
    val steps = (state.steps as? Loadable.Loaded)?.value.orEmpty()
    // Each tap jumps to the next error, round and round.
    var nextError by rememberSaveable { mutableIntStateOf(0) }
    // A failed job opened on its failure (a setting): brought into view once.
    LaunchedEffect(state.scrollToStep) {
        val index = state.scrollToStep ?: return@LaunchedEffect
        val row = rows.indexOfFirst { it is LogRow.Step && it.step == index }
        if (row >= 0) listState.scrollToItem(row + 1)
        onScrolledToStep()
    }
    Box(modifier.fillMaxSize().background(colors.ground)) {
        SelectionContainer(modifier = Modifier.fillMaxSize()) {
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
                                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.repo_open_on_forge, state.repo.forge.displayName), tint = Soft.colors.ink)
                            }
                        },
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(state.jobName, style = Soft.type.hero, color = Soft.colors.ink, modifier = Modifier.semantics { heading() })
                            state.job?.let { job ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RunStatusIcon(job.status, job.conclusion, size = 28.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Text(statusLine(job.status, job.conclusion, job.startedAt, job.completedAt, nowMillis), style = Soft.type.body, color = Soft.colors.ink)
                                }
                            }
                            state.failedStep?.let { failed ->
                                HeaderAction(stringResource(R.string.run_failed_at, stepTitle(steps[failed]))) {
                                    val index = onOpenFailed() ?: return@HeaderAction
                                    // The header is item 0: steps and their lines follow.
                                    val row = state.rows.indexOfFirst { it is LogRow.Step && it.step == index }
                                    if (row >= 0) scope.launch { listState.animateScrollToItem(row + 1) }
                                }
                            }
                            if (errors.isNotEmpty()) {
                                HeaderAction(pluralStringResource(R.plurals.log_jump_to_error, errors.size, errors.size)) {
                                    val target = errors[nextError % errors.size]
                                    nextError++
                                    // Leave a little context above.
                                    scope.launch { listState.animateScrollToItem((target + 1 - 2).coerceAtLeast(1)) }
                                }
                            }
                        }
                    }
                }
                val job = state.job
                val log = state.steps
                when {
                    state.isRunning && job != null -> {
                        item(key = "live") {
                            Text(
                                stringResource(R.string.log_live, state.repo.forge.displayName),
                                style = Soft.type.secondary,
                                color = colors.inkMuted,
                                modifier = LineModifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            )
                        }
                        items(job.steps, key = { "live-${it.number}" }) { LiveStepRow(it, nowMillis) }
                    }
                    log is Loadable.Failed && state.isLive -> item(key = "publishing") {
                        SoftLoadingRows(stringResource(R.string.log_publishing, state.repo.forge.displayName), rows = 6, leadingDot = false)
                    }
                    log == Loadable.Idle || log == Loadable.Loading -> item(key = "loading") { SoftLoadingRows(stringResource(R.string.log_loading), rows = 6, leadingDot = false) }
                    log is Loadable.Failed -> item(key = "failed") {
                        if (log.error == ForgeError.Unauthorized) {
                            SoftNotice(stringResource(R.string.log_sign_in_title), stringResource(R.string.log_sign_in_body, state.repo.forge.displayName), action = stringResource(R.string.sign_in), onAction = onSignIn)
                        } else {
                            SoftNotice(stringResource(R.string.log_failed), stringResource(log.error.message), action = stringResource(R.string.retry), onAction = onRetry)
                        }
                    }
                    log is Loadable.Loaded -> {
                        if (rows.isEmpty()) {
                            item(key = "empty") { SoftNotice(stringResource(R.string.log_empty_title), stringResource(R.string.log_empty_body)) }
                        }
                        items(rows, key = { it.key }) { row ->
                            when (row) {
                                is LogRow.Step -> StepRow(row, stepTitle(row.logStep), onClick = { onToggleStep(row.step) })
                                is LogRow.Loading -> SoftLoadingRows(stringResource(R.string.log_loading), rows = 3, leadingDot = false)
                                is LogRow.Failed -> SoftNotice(stringResource(R.string.log_failed), stringResource(row.error.message), action = stringResource(R.string.retry), onAction = { onRetryStep(row.step) })
                                is LogRow.Earlier -> EarlierRow(row, onClick = { onLoadEarlier(row.step) })
                                is LogRow.Empty -> Text(
                                    stringResource(R.string.log_step_empty),
                                    style = Soft.type.secondary,
                                    color = colors.inkMuted,
                                    modifier = LineModifier.padding(start = StepIndent, end = 20.dp, top = 4.dp, bottom = 12.dp),
                                )
                                is LogRow.Group -> GroupHeader(row, onClick = { onToggleGroup(row.step, row.fromEnd) })
                                is LogRow.Line -> LogLine(row, palette)
                            }
                        }
                    }
                }
            }
        }
        val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
        SoftStatusBarScrim(scrolled)
    }
}

/** What a step is called: what the runner says before the first step is the job being set up. */
@Composable
private fun stepTitle(step: LogStep): String = step.title ?: stringResource(R.string.log_step_setup)

@Composable
private fun HeaderAction(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(SoftTokens.Pill)
            .background(Soft.colors.ground)
            .softPressable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = Soft.colors.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = Soft.type.control, color = Soft.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A step of a finished job: how it ended, and what opening it shows. A step that wrote nothing doesn't open. */
@Composable
private fun StepRow(row: LogRow.Step, title: String, onClick: () -> Unit) {
    val colors = Soft.colors
    val step = row.logStep
    val opens = !step.isEmpty
    Row(
        LineModifier
            .padding(horizontal = 8.dp)
            .then(if (opens) Modifier.softPressable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RunStatusIcon(RunStatus.COMPLETED, step.conclusion, size = 28.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            style = Soft.type.body,
            color = if (opens) colors.ink else colors.inkMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val errors = step.errors ?: 0
        val meta = when {
            errors > 0 -> pluralStringResource(R.plurals.log_step_errors, errors, errors)
            step.conclusion == RunConclusion.SKIPPED -> stringResource(R.string.log_step_skipped)
            else -> row.took?.takeUnless { it.isNegative }?.let(::formatDuration)
        }
        if (meta != null) {
            Spacer(Modifier.width(8.dp))
            Text(meta, style = Soft.type.meta, color = if (errors > 0) colors.accent else colors.inkMuted)
        }
        if (opens) {
            Spacer(Modifier.width(4.dp))
            Icon(
                if (row.open) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = colors.inkMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Above the lines read of a step: the lines before them, read when asked. */
@Composable
private fun EarlierRow(row: LogRow.Earlier, onClick: () -> Unit) {
    val colors = Soft.colors
    val text = when {
        row.error != null -> stringResource(R.string.log_earlier_failed)
        row.lines != null -> pluralStringResource(R.plurals.log_earlier_lines, row.lines, row.lines)
        else -> stringResource(R.string.log_earlier_size, formatBytes(row.bytes))
    }
    Row(
        LineModifier
            .padding(start = StepIndent - 12.dp, end = 8.dp)
            .softPressable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.ExpandLess, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (row.loading) stringResource(R.string.log_loading) else text, style = Soft.type.control, color = colors.accent)
    }
}

/** A step of a running job: its state, and how long it took or has been going. */
@Composable
private fun LiveStepRow(step: RunStep, nowMillis: Long) {
    val colors = Soft.colors
    val current = step.status == RunStatus.IN_PROGRESS
    Row(
        LineModifier
            .padding(horizontal = 8.dp)
            .then(if (current) Modifier.background(colors.surface, SoftTokens.RowCorner) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RunStatusIcon(step.status, step.conclusion, size = 28.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            step.name,
            style = Soft.type.body,
            color = if (step.status == RunStatus.QUEUED) colors.inkMuted else colors.ink,
            modifier = Modifier.weight(1f),
        )
        val took = step.startedAt?.let { start ->
            val end = step.completedAt ?: if (current) java.time.Instant.ofEpochMilli(nowMillis) else null
            end?.let { formatDuration(Duration.between(start, it)) }
        }
        if (took != null) Text(took, style = Soft.type.meta, color = colors.inkMuted)
    }
}

private val LineModifier = Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth()

/** How far a step's lines sit in from its row: under its title, past its status. */
private val StepIndent = 28.dp

@Composable
private fun GroupHeader(row: LogRow.Group, onClick: () -> Unit) {
    val colors = Soft.colors
    Row(
        LineModifier
            .padding(start = StepIndent - 8.dp, end = 8.dp)
            .softPressable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
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
        Text(row.title, style = Soft.type.codeDense.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = colors.ink, maxLines = 2, modifier = Modifier.weight(1f))
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
    Text(
        text,
        style = Soft.type.codeDense,
        color = tint,
        modifier = LineModifier
            .padding(start = StepIndent - 8.dp, end = 8.dp)
            .then(if (line.kind == LogLineKind.ERROR) Modifier.background(colors.fields[0]) else Modifier)
            .padding(start = if (row.inGroup != null) 32.dp else 8.dp, end = 8.dp, top = 1.dp, bottom = 1.dp),
    )
}
