package fr.arthurbrugiere.forgeline.pull

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.CheckReport
import fr.arthurbrugiere.forgeline.core.model.CheckState
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.ui.theme.ForgelineTheme
import fr.arthurbrugiere.forgeline.ui.assertEveryTargetIsAtLeast48dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class CheckScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()
    private val repo = RepoId("octo", "tools")
    private val report = CheckReport(
        name = "Code Health Review (main)",
        state = CheckState.FAILURE,
        app = "Code Health",
        title = "PR Check",
        summary = "## Quality Gate Failed\n\nSee the [rules](https://health.example/rules).",
        text = "Complex Method in Zip.kt",
        completedAt = Instant.parse("2026-09-30T10:00:00Z"),
        detailsUrl = "https://health.example/results/7",
        url = "https://github.com/octo/tools/runs/4242",
    )

    private fun setContent(state: CheckUiState) {
        composeRule.setContent {
            ForgelineTheme {
                CheckScreen(
                    state = state,
                    onBack = { events += "back" },
                    onRefresh = { events += "refresh" },
                    onOpenUrl = { events += "url:$it" },
                    onLinkClick = { events += "link:$it" },
                    onErrorShown = { events += "error-shown" },
                    nowMillis = Instant.parse("2026-10-02T10:00:00Z").toEpochMilli(),
                )
            }
        }
    }

    private fun shown(change: (CheckReport) -> CheckReport = { it }) = CheckUiState(repo, 4242, "health", change(report))

    private fun waitFor(text: String) =
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun reach(text: String) = composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))

    @Test
    fun says_what_ran_where_it_stands_and_what_it_wrote() {
        setContent(shown())

        composeRule.onNode(hasText("Code Health Review (main)") and isHeading()).assertIsDisplayed()
        composeRule.onNodeWithText("Code Health").assertIsDisplayed()
        composeRule.onNodeWithText("Failed · 2 days ago").assertIsDisplayed()
        composeRule.onNodeWithText("PR Check").assertIsDisplayed()
        waitFor("Quality Gate Failed")
        reach("Complex Method in Zip.kt")
        composeRule.onNodeWithText("Complex Method in Zip.kt").assertIsDisplayed()
        composeRule.assertEveryTargetIsAtLeast48dp()
    }

    @Test
    fun the_service_s_own_page_and_the_forge_s_are_each_a_tap_away() {
        setContent(shown())
        waitFor("Quality Gate Failed")

        reach("Open on Code Health")
        composeRule.onNodeWithText("Open on Code Health").performClick()
        composeRule.onNodeWithContentDescription("Open on GitHub").performClick()
        composeRule.onNodeWithContentDescription("Navigate up").performClick()

        assertThat(events).containsExactly("url:https://health.example/results/7", "url:https://github.com/octo/tools/runs/4242", "back").inOrder()
    }

    @Test
    fun a_check_that_wrote_nothing_says_so() {
        setContent(shown { it.copy(state = CheckState.SUCCESS, app = null, title = null, summary = null, text = null, completedAt = null, detailsUrl = null) })

        composeRule.onNodeWithText("This check left no report here.").assertIsDisplayed()
        composeRule.onNodeWithText("Passed").assertIsDisplayed()
        // Without a name for what ran it, the repository says where this is.
        composeRule.onNodeWithText("octo/tools").assertIsDisplayed()
        composeRule.onNode(hasText("Open on", substring = true)).assertDoesNotExist()
    }

    @Test
    fun the_name_it_was_tapped_by_shows_while_the_report_loads() {
        setContent(CheckUiState(repo, 4242, "health", isRefreshing = true))

        composeRule.onNode(hasText("health") and isHeading()).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open on GitHub").performClick()

        // Before the forge says where the check's page is, it is where GitHub keeps them.
        assertThat(events).containsExactly("url:https://github.com/octo/tools/runs/4242")
    }

    @Test
    fun a_report_that_cannot_be_read_offers_to_try_again() {
        setContent(CheckUiState(repo, 4242, "health", error = ForgeError.Network))

        composeRule.onNodeWithText("Couldn't load the report").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()

        assertThat(events).containsExactly("refresh")
    }
}
