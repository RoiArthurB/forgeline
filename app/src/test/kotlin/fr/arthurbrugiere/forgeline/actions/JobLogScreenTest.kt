package fr.arthurbrugiere.forgeline.actions

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.JobLog
import fr.arthurbrugiere.forgeline.core.model.LogEntry
import fr.arthurbrugiere.forgeline.core.model.LogLineKind
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.repo.Loadable
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
    private val log = JobLog(
        listOf(
            LogEntry.Group("Set up job", listOf(LogEntry.Line("Runner version 2.337"))),
            LogEntry.Group("Run pnpm test", listOf(LogEntry.Line("1 failed"), LogEntry.Line("Tests failed", LogLineKind.ERROR))),
            LogEntry.Line("Process completed with exit code 1.", LogLineKind.ERROR),
        ),
    )
    private val events = mutableListOf<String>()

    private fun setContent(state: JobLogUiState) {
        composeRule.setContent {
            JobLogScreen(
                state = state,
                onBack = {},
                onRetry = { events += "retry" },
                onToggleGroup = { events += "toggle:$it" },
                onSignIn = { events += "sign-in" },
                onOpenInBrowser = {},
            )
        }
    }

    @Test
    fun the_log_reads_folded_and_jumps_to_errors() {
        setContent(JobLogUiState(repo, 3, "test (1/2)", Loadable.Loaded(log), openGroups = setOf(1)))

        composeRule.onNodeWithText("test (1/2)").assertIsDisplayed()
        composeRule.onNodeWithText("Runner version 2.337").assertDoesNotExist()
        composeRule.onNodeWithText("Tests failed").assertIsDisplayed()
        composeRule.onNodeWithText("Jump to an error (2)").performClick()
        composeRule.onNodeWithText("Set up job").performClick()

        assertThat(events).containsExactly("toggle:0")
    }

    @Test
    fun signed_out_the_log_asks_to_sign_in() {
        setContent(JobLogUiState(repo, 3, "test", Loadable.Failed(ForgeError.Unauthorized)))

        composeRule.onNodeWithText("Sign in to read logs").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in").performClick()

        assertThat(events).containsExactly("sign-in")
    }

    @Test
    fun a_failed_log_can_be_retried() {
        setContent(JobLogUiState(repo, 3, "test", Loadable.Failed(ForgeError.Network)))

        composeRule.onNodeWithText("Retry").performClick()

        assertThat(events).containsExactly("retry")
    }
}
