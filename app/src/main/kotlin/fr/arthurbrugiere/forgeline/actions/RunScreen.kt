package fr.arthurbrugiere.forgeline.actions

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import fr.arthurbrugiere.forgeline.session.signedInOn
import fr.arthurbrugiere.forgeline.core.model.runUrl
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
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
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RunConclusion
import fr.arthurbrugiere.forgeline.core.model.RunJob
import fr.arthurbrugiere.forgeline.core.model.RunStatus
import fr.arthurbrugiere.forgeline.core.model.WorkflowRun
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSectionTitle
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftStatusBarScrim
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTag
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTonalButton
import fr.arthurbrugiere.forgeline.core.ui.soft.softPressable
import fr.arthurbrugiere.forgeline.navigation.RunRoute
import fr.arthurbrugiere.forgeline.session.SessionState
import fr.arthurbrugiere.forgeline.ui.LocalBottomBarSpace
import fr.arthurbrugiere.forgeline.ui.listBottomPadding
import fr.arthurbrugiere.forgeline.ui.message
import fr.arthurbrugiere.forgeline.ui.relative
import fr.arthurbrugiere.forgeline.ui.rememberCustomTabOpener
import kotlinx.coroutines.delay

@Composable
fun RunRoute(
    route: RunRoute,
    session: SessionState,
    onBack: () -> Unit,
    onOpenJob: (RepoId, RunJob) -> Unit,
    onOpenUser: (String) -> Unit,
) {
    val repo = route.repo
    val viewModel = hiltViewModel<RunViewModel, RunViewModel.Factory>(key = "${repo.key}/runs/${route.runId}") { it.create(repo, route.runId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openUrl = rememberCustomTabOpener()
    // A run that is still going is checked again while it is on screen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state.run != null && !state.isFinished) {
        if (state.run == null || state.isFinished) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(RunViewModel.POLL_MILLIS)
                viewModel.poll()
            }
        }
    }
    RunScreen(
        state = state,
        signedIn = session.signedInOn(repo.forge),
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onPerform = viewModel::perform,
        onOpenJob = { onOpenJob(repo, it) },
        onOpenUser = onOpenUser,
        // Forgejo's run pages go by the run's number, known once the run is.
        onOpenInBrowser = { openUrl(state.run?.webUrl ?: repo.runUrl(route.runId)) },
        onResultShown = viewModel::resultShown,
        onErrorShown = viewModel::errorShown,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunScreen(
    state: RunUiState,
    signedIn: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onPerform: (RunAction) -> Unit,
    onOpenJob: (RunJob) -> Unit,
    onOpenUser: (String) -> Unit,
    onOpenInBrowser: () -> Unit,
    onResultShown: () -> Unit,
    onErrorShown: () -> Unit,
    modifier: Modifier = Modifier,
    nowMillis: Long = System.currentTimeMillis(),
) {
    val colors = Soft.colors
    val snackbar = remember { SnackbarHostState() }
    val run = state.run
    val result = state.result
    val resultText = result?.let { actionResultText(it, state.repo.forge.displayName) }
    LaunchedEffect(result) {
        if (resultText != null) {
            snackbar.showSnackbar(resultText)
            onResultShown()
        }
    }
    val refreshFailed = stringResource(R.string.trending_refresh_failed)
    LaunchedEffect(state.error, run != null) {
        if (state.error != null && run != null) {
            snackbar.showSnackbar(refreshFailed)
            onErrorShown()
        }
    }
    val listState = rememberLazyListState()
    Box(modifier.fillMaxSize().background(colors.ground)) {
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = state.isRefreshing && run != null,
            onRefresh = onRefresh,
            state = pullState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = state.isRefreshing && run != null,
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
                modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
            ) {
                item(key = "header") {
                    SoftHeader(
                        tint = runTint(run),
                        onBack = onBack,
                        backDescription = stringResource(R.string.navigate_up),
                        actions = {
                            IconButton(onClick = onOpenInBrowser) {
                                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.repo_open_on_forge, state.repo.forge.displayName), tint = colors.ink)
                            }
                        },
                    ) {
                        if (run != null) {
                            RunHeader(run, state.pending, signedIn, nowMillis, onPerform, onOpenUser, canRerun = state.canRerun, forgeName = state.repo.forge.displayName)
                        } else {
                            Text(state.repo.fullName, style = Soft.type.title, color = colors.ink)
                        }
                    }
                }
                val jobs = state.jobs
                when {
                    run == null && state.error != null -> item(key = "error") {
                        SoftNotice(
                            stringResource(R.string.run_error_title),
                            stringResource(state.error.message),
                            action = stringResource(R.string.retry),
                            onAction = onRefresh,
                        )
                    }
                    state.jobsUnlisted -> item(key = "jobs-unlisted") {
                        SoftNotice(
                            stringResource(R.string.run_jobs_unlisted_title),
                            stringResource(R.string.run_jobs_unlisted_body),
                            action = stringResource(R.string.repo_open_on_forge, state.repo.forge.displayName),
                            onAction = onOpenInBrowser,
                        )
                    }
                    jobs == null -> item(key = "loading") { SoftLoadingRows(stringResource(R.string.run_loading), rows = 4) }
                    else -> {
                        item(key = "jobs-title") {
                            SoftSectionTitle(pluralStringResource(R.plurals.run_jobs, jobs.size, jobs.size))
                        }
                        items(jobs, key = { "job-${it.id}" }) { JobRow(it, nowMillis, onClick = { onOpenJob(it) }) }
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

@Composable
private fun RunHeader(
    run: WorkflowRun,
    pending: RunAction?,
    signedIn: Boolean,
    nowMillis: Long,
    onPerform: (RunAction) -> Unit,
    onOpenUser: (String) -> Unit,
    canRerun: Boolean = true,
    forgeName: String = "GitHub",
) {
    val colors = Soft.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(run.workflowName, style = Soft.type.secondary, color = colors.inkMuted)
        Text(run.title, style = Soft.type.title, color = colors.ink, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
        Row(verticalAlignment = Alignment.CenterVertically) {
            RunStatusIcon(run.status, run.conclusion, size = 28.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                statusLine(run.status, run.conclusion, run.startedAt ?: run.createdAt, run.updatedAt, nowMillis),
                style = Soft.type.body,
                color = colors.ink,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            run.branch?.let { SoftTag(it, background = colors.ground) }
            SoftTag(run.event, background = colors.ground)
            SoftTag("#${run.runNumber}", background = colors.ground)
            if (run.attempt > 1) SoftTag(stringResource(R.string.run_attempt, run.attempt), background = colors.ground)
        }
        Text(
            listOfNotNull(run.actor?.login?.let { stringResource(R.string.run_by, it) }, relative(run.createdAt, nowMillis)).joinToString(" · "),
            style = Soft.type.meta,
            color = colors.inkMuted,
            modifier = run.actor?.let { actor -> Modifier.softPressable { onOpenUser(actor.login) } } ?: Modifier,
        )
        if (signedIn) RunActions(run, pending, onPerform, canRerun, forgeName)
    }
}

/** What can be done to the run now: cancel it while it goes, re-run it once it's done. */
@Composable
private fun RunActions(run: WorkflowRun, pending: RunAction?, onPerform: (RunAction) -> Unit, canRerun: Boolean, forgeName: String) {
    val colors = Soft.colors
    if (pending != null) {
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.accent, trackColor = colors.ground)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.run_asking_forge, forgeName), style = Soft.type.body, color = colors.ink)
        }
        return
    }
    FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            run.status != RunStatus.COMPLETED -> SoftTonalButton(stringResource(R.string.run_cancel), onClick = { onPerform(RunAction.CANCEL) })
            !canRerun -> Unit
            run.conclusion.failed || run.conclusion == RunConclusion.CANCELLED -> {
                SoftButton(stringResource(R.string.run_rerun_failed), onClick = { onPerform(RunAction.RERUN_FAILED) })
                SoftTonalButton(stringResource(R.string.run_rerun_all), onClick = { onPerform(RunAction.RERUN_ALL) })
            }
            else -> SoftTonalButton(stringResource(R.string.run_rerun), onClick = { onPerform(RunAction.RERUN_ALL) })
        }
    }
}

@Composable
private fun JobRow(job: RunJob, nowMillis: Long, onClick: () -> Unit) {
    val colors = Soft.colors
    val failedStep = job.steps.firstOrNull { it.conclusion.failed }
    Row(
        Modifier
            .widthIn(max = SoftTokens.MaxReadingWidth)
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .softPressable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RunStatusIcon(job.status, job.conclusion)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(job.name, style = Soft.type.body, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                statusLine(job.status, job.conclusion, job.startedAt, job.completedAt, nowMillis),
                style = Soft.type.meta,
                color = colors.inkMuted,
            )
            if (failedStep != null) {
                Text(
                    stringResource(R.string.run_failed_at, failedStep.name),
                    style = Soft.type.meta,
                    color = colors.accent,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun actionResultText(result: RunActionResult, forgeName: String): String {
    val error = result.error
    return when {
        error is ForgeError.Http && (error.status == 403 || error.status == 404) -> stringResource(R.string.run_action_no_access, forgeName)
        error != null -> stringResource(error.message)
        result.action == RunAction.CANCEL -> stringResource(R.string.run_cancel_requested)
        else -> stringResource(R.string.run_rerun_requested)
    }
}
