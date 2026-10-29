package fr.arthurbrugiere.forgeline.actions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.model.RunConclusion
import fr.arthurbrugiere.forgeline.core.model.RunStatus
import fr.arthurbrugiere.forgeline.core.model.WorkflowRun
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.animationsEnabled
import java.time.Duration
import java.time.Instant

/** An icon on a tile for a row: the file kind, a release, a run's state. */
@Composable
fun RowIcon(icon: ImageVector, background: Color, modifier: Modifier = Modifier, tint: Color = Soft.colors.ink, size: Dp = 36.dp) {
    Box(modifier.size(size).background(background, SoftTokens.TileCorner), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size / 2))
    }
}

/** A run's or job's state as a tile: fresh for success, warm for failure, quiet for the rest. */
@Composable
fun RunStatusIcon(status: RunStatus, conclusion: RunConclusion?, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    val colors = Soft.colors
    when {
        // With the phone's animations off, the spinner stops on a broken arc: a still "running" glyph says it instead.
        status == RunStatus.IN_PROGRESS && !animationsEnabled() -> RowIcon(Icons.Outlined.Sync, colors.fields[1], size = size, modifier = modifier)
        status == RunStatus.IN_PROGRESS -> Box(modifier.size(size).background(colors.fields[1], SoftTokens.TileCorner), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(size / 2), strokeWidth = 2.dp, color = colors.ink, trackColor = colors.fields[1])
        }
        status == RunStatus.QUEUED -> RowIcon(Icons.Outlined.Schedule, colors.surface, modifier, colors.inkMuted, size)
        conclusion == RunConclusion.SUCCESS -> RowIcon(Icons.Outlined.Check, colors.fields[2], size = size, modifier = modifier)
        conclusion.failed -> RowIcon(Icons.Outlined.Close, colors.fields[0], modifier, colors.accent, size)
        else -> RowIcon(Icons.Outlined.Block, colors.surface, modifier, colors.inkMuted, size)
    }
}

@Composable
fun RunStatusIcon(run: WorkflowRun) = RunStatusIcon(run.status, run.conclusion)

val RunConclusion?.failed: Boolean get() = this == RunConclusion.FAILURE || this == RunConclusion.TIMED_OUT

/** A run's tint: fresh when it passed, warm when it failed, cool while it runs. */
@Composable
fun runTint(run: WorkflowRun?): Color {
    val colors = Soft.colors
    return when {
        run == null -> colors.surface
        run.status == RunStatus.IN_PROGRESS || run.status == RunStatus.QUEUED -> colors.fields[1]
        run.conclusion == RunConclusion.SUCCESS -> colors.fields[2]
        run.conclusion.failed -> colors.fields[0]
        else -> colors.surface
    }
}

/** How a run or job stands, with its duration: "Failed after 4m 30s", "Running for 12s", "Queued". */
@Composable
fun statusLine(status: RunStatus, conclusion: RunConclusion?, startedAt: Instant?, endedAt: Instant?, nowMillis: Long): String {
    val running = startedAt?.let { Duration.between(it, Instant.ofEpochMilli(nowMillis)) }
    val took = if (startedAt != null && endedAt != null) Duration.between(startedAt, endedAt) else null
    val resources = LocalResources.current
    fun withDuration(template: Int, plain: Int, duration: Duration?): String =
        if (duration != null && !duration.isNegative) resources.getString(template, formatDuration(duration)) else resources.getString(plain)
    return when (status) {
        RunStatus.QUEUED -> stringResource(R.string.run_queued)
        RunStatus.IN_PROGRESS -> withDuration(R.string.run_running_for, R.string.run_running, running)
        RunStatus.OTHER -> stringResource(R.string.run_waiting)
        RunStatus.COMPLETED -> when (conclusion) {
            RunConclusion.SUCCESS -> withDuration(R.string.run_succeeded_in, R.string.run_succeeded, took)
            RunConclusion.FAILURE -> withDuration(R.string.run_failed_after, R.string.run_failed, took)
            RunConclusion.TIMED_OUT -> withDuration(R.string.run_timed_out_after, R.string.run_timed_out, took)
            RunConclusion.CANCELLED -> withDuration(R.string.run_cancelled_after, R.string.run_cancelled, took)
            RunConclusion.SKIPPED -> stringResource(R.string.run_skipped)
            RunConclusion.ACTION_REQUIRED -> stringResource(R.string.run_action_required)
            RunConclusion.NEUTRAL, RunConclusion.OTHER, null -> withDuration(R.string.run_finished_in, R.string.run_finished, took)
        }
    }
}

/** "12s", "4m 30s", "1h 5m": the two largest units, as CI pages show them. */
fun formatDuration(duration: Duration): String {
    val seconds = duration.seconds.coerceAtLeast(0)
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    val rest = seconds % 60
    return when {
        hours > 0 -> if (minutes > 0) "${hours}h ${minutes}m" else "${hours}h"
        minutes > 0 -> if (rest > 0) "${minutes}m ${rest}s" else "${minutes}m"
        else -> "${rest}s"
    }
}
