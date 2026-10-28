package fr.arthurbrugiere.forgeline.actions

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.LogEntry
import fr.arthurbrugiere.forgeline.core.model.LogLineKind
import fr.arthurbrugiere.forgeline.core.model.LogStep
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RunConclusion
import fr.arthurbrugiere.forgeline.core.model.RunJob
import fr.arthurbrugiere.forgeline.core.model.RunStatus
import fr.arthurbrugiere.forgeline.core.model.RunStep
import fr.arthurbrugiere.forgeline.repo.Loadable
import fr.arthurbrugiere.forgeline.ui.assertEveryTargetIsAtLeast48dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class JobLogScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val repo = RepoId("octo", "repo")
    private val steps = listOf(
        LogStep("Set up job", 0, 100, RunConclusion.SUCCESS),
        LogStep("Run tests", 100, 90_000, RunConclusion.FAILURE, lines = 405, errors = 2),
        LogStep("Upload reports", 90_000, 90_000, RunConclusion.SKIPPED),
        LogStep("Complete job", 90_000, 90_100, RunConclusion.SUCCESS),
    )
    private val read = StepRead(
        Loadable.Loaded(
            listOf(
                LogEntry.Line("test 399 passed"),
                LogEntry.Group("Error details", listOf(LogEntry.Line("Tests failed", LogLineKind.ERROR))),
                LogEntry.Line("Process completed with exit code 1.", LogLineKind.ERROR),
            ),
        ),
        start = 60_000,
        lines = 300,
        openGroups = setOf(2),
    )
    private val folded = JobLogUiState(repo, 3, "test (1/2)", steps = Loadable.Loaded(steps))
    private val events = mutableListOf<String>()

    private fun setContent(state: JobLogUiState) {
        composeRule.setContent {
            JobLogScreen(
                state = state,
                onBack = {},
                onRetry = { events += "retry" },
                onToggleStep = { events += "step:$it" },
                onOpenFailed = { events += "failed"; 1 },
                onRetryStep = { events += "retry-step:$it" },
                onLoadEarlier = { events += "earlier:$it" },
                onToggleGroup = { step, fromEnd -> events += "group:$step:$fromEnd" },
                onSignIn = { events += "sign-in" },
                onOpenInBrowser = {},
                onScrolledToStep = { events += "scrolled" },
            )
        }
    }

    @Test
    fun steps_come_folded_and_a_tap_opens_one() {
        setContent(folded)

        composeRule.onNodeWithText("test (1/2)").assertIsDisplayed()
        composeRule.onNodeWithText("Set up job").assertIsDisplayed()
        composeRule.onNodeWithText("2 errors").assertIsDisplayed()
        composeRule.onNodeWithText("Process completed with exit code 1.").assertDoesNotExist()
        composeRule.onNodeWithText("Run tests").performClick()

        assertThat(events).containsExactly("step:1")
    }

    @Test
    fun the_header_opens_the_step_the_job_failed_at() {
        setContent(folded)

        composeRule.onNodeWithText("Failed at Run tests").performClick()

        assertThat(events).containsExactly("failed")
    }

    @Test
    fun a_step_opened_by_itself_is_scrolled_to_once() {
        setContent(folded.copy(opened = setOf(1), read = mapOf(1 to read), scrollToStep = 1))

        composeRule.onNodeWithText("Process completed with exit code 1.").assertIsDisplayed()
        assertThat(events).containsExactly("scrolled")
    }

    @Test
    fun a_skipped_step_says_so_and_doesn_t_open() {
        setContent(folded)

        composeRule.onNodeWithText("Skipped").assertIsDisplayed()
        composeRule.onNodeWithText("Upload reports").performClick()

        assertThat(events).isEmpty()
    }

    @Test
    fun an_opened_step_shows_its_last_lines_and_offers_the_earlier_ones() {
        setContent(folded.copy(opened = setOf(1), read = mapOf(1 to read)))

        composeRule.onNodeWithText("Process completed with exit code 1.").assertIsDisplayed()
        composeRule.onNodeWithText("Tests failed").assertIsDisplayed()
        composeRule.onNodeWithText("Jump to an error (2)").assertIsDisplayed()
        composeRule.onNodeWithText("Show 105 earlier lines").performClick()
        composeRule.onNodeWithText("Error details").performClick()

        assertThat(events).containsExactly("earlier:1", "group:1:2").inOrder()
    }

    @Test
    fun read_in_parts_a_step_offers_its_earlier_lines_by_size() {
        setContent(folded.copy(steps = Loadable.Loaded(steps.map { it.copy(lines = null) }), opened = setOf(1), read = mapOf(1 to read)))

        composeRule.onNodeWithText("Show earlier lines", substring = true).assertIsDisplayed()
    }

    @Test
    fun a_step_that_couldnt_be_read_can_be_read_again() {
        setContent(folded.copy(opened = setOf(1), read = mapOf(1 to StepRead(Loadable.Failed(ForgeError.Network)))))

        composeRule.onNodeWithText("Retry").performClick()

        assertThat(events).containsExactly("retry-step:1")
    }

    @Test
    fun a_running_job_shows_its_steps_live() {
        val start = java.time.Instant.parse("2026-09-29T10:49:05Z")
        val job = RunJob(
            3, "test", RunStatus.IN_PROGRESS, null, start, null,
            listOf(
                RunStep(1, "Set up job", RunStatus.COMPLETED, RunConclusion.SUCCESS, start, start.plusSeconds(2)),
                RunStep(2, "Run tests", RunStatus.IN_PROGRESS, null, start.plusSeconds(2), null),
                RunStep(3, "Post checkout", RunStatus.QUEUED, null, null, null),
            ),
        )
        composeRule.setContent {
            JobLogScreen(
                state = JobLogUiState(repo, 3, "test", job = job, steps = Loadable.Idle),
                onBack = {}, onRetry = {}, onToggleStep = {}, onOpenFailed = { null }, onRetryStep = {}, onLoadEarlier = {},
                onToggleGroup = { _, _ -> }, onSignIn = {}, onOpenInBrowser = {},
                nowMillis = start.plusSeconds(92).toEpochMilli(),
            )
        }

        composeRule.onNodeWithText("Running for 1m 32s").assertIsDisplayed()
        composeRule.onNodeWithText("Run tests").assertIsDisplayed()
        composeRule.onNodeWithText("1m 30s").assertIsDisplayed()
        composeRule.onNodeWithText("2s").assertIsDisplayed()
        composeRule.onNodeWithText("Running now", substring = true).assertIsDisplayed()
    }

    @Test
    fun signed_out_the_log_asks_to_sign_in() {
        setContent(JobLogUiState(repo, 3, "test", steps = Loadable.Failed(ForgeError.Unauthorized)))

        composeRule.onNodeWithText("Sign in to read logs").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in").performClick()

        assertThat(events).containsExactly("sign-in")
    }

    @Test
    fun a_failed_log_can_be_retried() {
        setContent(JobLogUiState(repo, 3, "test", steps = Loadable.Failed(ForgeError.Network)))

        composeRule.onNodeWithText("Retry").performClick()

        assertThat(events).containsExactly("retry")
    }

    /** On a screen tall enough to hold it all: a target cut by the edge of a shorter one measures as less than it is. */
    @Test
    @Config(qualifiers = "w411dp-h2400dp-xhdpi")
    fun every_target_is_48dp_at_least() {
        setContent(folded.copy(opened = setOf(1), read = mapOf(1 to read)))
        composeRule.assertEveryTargetIsAtLeast48dp()
    }
}
