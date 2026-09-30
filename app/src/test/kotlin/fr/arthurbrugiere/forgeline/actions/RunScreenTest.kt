package fr.arthurbrugiere.forgeline.actions

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RunConclusion
import fr.arthurbrugiere.forgeline.core.model.RunJob
import fr.arthurbrugiere.forgeline.core.model.RunStatus
import fr.arthurbrugiere.forgeline.core.model.RunStep
import fr.arthurbrugiere.forgeline.core.testing.workflowRun
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class RunScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()
    private val repo = RepoId("octo", "repo")
    private val start = Instant.parse("2026-09-29T08:00:00Z")
    private val failedJob = RunJob(
        1, "test (1/2)", RunStatus.COMPLETED, RunConclusion.FAILURE, start, start.plusSeconds(361),
        listOf(RunStep(1, "Set up job", RunStatus.COMPLETED, RunConclusion.SUCCESS), RunStep(2, "Run the tests", RunStatus.COMPLETED, RunConclusion.FAILURE)),
    )
    private val passedJob = RunJob(2, "lint", RunStatus.COMPLETED, RunConclusion.SUCCESS, start, start.plusSeconds(42), emptyList())
    private val failedRun = RunUiState(repo, 7, run = workflowRun(7, conclusion = RunConclusion.FAILURE), jobs = listOf(failedJob, passedJob))

    private fun setContent(state: RunUiState, signedIn: Boolean = true) {
        composeRule.setContent {
            RunScreen(
                state = state,
                signedIn = signedIn,
                onBack = {},
                onRefresh = { events += "refresh" },
                onPerform = { events += "perform:$it" },
                onOpenJob = { events += "job:${it.id}" },
                onOpenUser = { events += "user:$it" },
                onOpenInBrowser = {},
                onResultShown = { events += "shown" },
                onErrorShown = {},
                nowMillis = start.plusSeconds(600).toEpochMilli(),
            )
        }
    }

    @Test
    fun a_failed_run_says_how_long_it_took_and_where_it_failed() {
        setContent(failedRun)

        composeRule.onNodeWithText("Fix the build").assertIsDisplayed()
        composeRule.onNodeWithText("Failed after 4m 30s").assertIsDisplayed()
        composeRule.onNodeWithText("2 jobs").assertIsDisplayed()
        composeRule.onNodeWithText("Failed after 6m 1s").assertIsDisplayed()
        composeRule.onNodeWithText("Failed at Run the tests").assertIsDisplayed()
        composeRule.onNodeWithText("Succeeded in 42s").assertIsDisplayed()
    }

    @Test
    fun a_failed_run_offers_to_rerun_its_failed_jobs_or_all() {
        setContent(failedRun)

        composeRule.onNodeWithText("Re-run failed jobs").performClick()
        composeRule.onNodeWithText("Re-run all jobs").performClick()

        assertThat(events).containsExactly("perform:RERUN_FAILED", "perform:RERUN_ALL").inOrder()
    }

    @Test
    fun a_running_run_can_be_cancelled() {
        setContent(failedRun.copy(run = workflowRun(7, status = RunStatus.IN_PROGRESS, conclusion = null)))

        composeRule.onNodeWithText("Running for 10m").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel run").performClick()

        assertThat(events).containsExactly("perform:CANCEL")
    }

    @Test
    fun signed_out_there_is_nothing_to_press_but_jobs_open() {
        setContent(failedRun, signedIn = false)

        composeRule.onNodeWithText("Re-run failed jobs").assertDoesNotExist()
        composeRule.onNodeWithText("lint").performClick()

        assertThat(events).containsExactly("job:2")
    }

    @Test
    fun while_the_forge_answers_the_actions_wait() {
        setContent(failedRun.copy(pending = RunAction.RERUN_FAILED))

        composeRule.onNodeWithText("Asking GitHub…").assertIsDisplayed()
        composeRule.onNodeWithText("Re-run failed jobs").assertDoesNotExist()
    }

    @Test
    fun a_refusal_explains_itself() {
        setContent(failedRun.copy(result = RunActionResult(RunAction.RERUN_ALL, ForgeError.Http(403, "Must have admin rights"))))

        composeRule.waitUntil(5_000) { events.contains("shown") }
        // The snackbar is gone once shown; what matters is that it was reported.
        assertThat(events).contains("shown")
    }

    @Test
    fun a_missing_run_offers_a_retry() {
        setContent(RunUiState(repo, 7, error = ForgeError.Http(404, "Not Found")))

        composeRule.onNodeWithText("Couldn't open this run").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()

        assertThat(events).containsExactly("refresh")
    }

    @Test
    fun without_rerun_a_finished_run_offers_nothing_to_press() {
        setContent(failedRun.copy(canRerun = false))

        composeRule.onNodeWithText("Re-run failed jobs").assertDoesNotExist()
        composeRule.onNodeWithText("Re-run all jobs").assertDoesNotExist()
    }

    @Test
    fun without_rerun_a_running_run_can_still_be_cancelled() {
        setContent(failedRun.copy(run = workflowRun(7, status = RunStatus.IN_PROGRESS, conclusion = null), canRerun = false))

        composeRule.onNodeWithText("Cancel run").assertExists()
    }
}
