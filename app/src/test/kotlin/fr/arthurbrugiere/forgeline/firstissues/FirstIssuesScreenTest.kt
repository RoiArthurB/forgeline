package fr.arthurbrugiere.forgeline.firstissues

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.FirstIssue
import fr.arthurbrugiere.forgeline.core.model.FirstIssueSource
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.testing.issueSummary
import fr.arthurbrugiere.forgeline.ui.assertEveryTargetIsAtLeast48dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class FirstIssuesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()
    private val starred = FirstIssue(RepoId("me", "tool"), issueSummary(1, "Fix a typo"), FirstIssueSource.STARRED)
    private val trending = FirstIssue(RepoId("hot", "thing"), issueSummary(2, "Add a test"), FirstIssueSource.TRENDING)

    private fun setContent(state: FirstIssuesUiState) = composeRule.setContent {
        FirstIssuesScreen(
            state,
            onBack = { events += "back" },
            onOpenIssue = { events += "issue:${it.repo.fullName}#${it.number}:${it.isPullRequest}" },
            onSelectSource = { events += "source:$it" },
            onRefresh = { events += "refresh" },
            onErrorShown = { events += "error-shown" },
            nowMillis = java.time.Instant.parse("2026-10-28T10:00:00Z").toEpochMilli(),
        )
    }

    @Test
    fun an_issue_opens_as_an_issue_and_both_sources_can_be_told_apart() {
        setContent(FirstIssuesUiState(listOf(starred, trending)))

        composeRule.onNodeWithText("Your stars").performClick()
        composeRule.onNodeWithText("Fix a typo").performClick()

        assertThat(events).containsExactly("source:STARRED", "issue:me/tool#1:false").inOrder()
    }

    @Test
    fun one_source_alone_offers_no_choice() {
        setContent(FirstIssuesUiState(listOf(starred)))

        composeRule.onNodeWithText("Your stars").assertDoesNotExist()
        composeRule.onNodeWithText("Fix a typo").assertIsDisplayed()
    }

    @Test
    fun nothing_found_says_where_to_look_and_a_failure_offers_another_try() {
        setContent(FirstIssuesUiState(emptyList()))
        composeRule.onNodeWithText("No first issue right now").assertIsDisplayed()
    }

    @Test
    fun a_failure_with_nothing_kept_offers_another_try() {
        setContent(FirstIssuesUiState(null, error = ForgeError.Network))

        composeRule.onNodeWithText("Retry").performClick()

        assertThat(events).containsExactly("refresh")
    }

    @Test
    @Config(qualifiers = "w411dp-h2400dp-xhdpi")
    fun every_target_is_48dp_at_least() {
        setContent(FirstIssuesUiState(listOf(starred, trending)))
        composeRule.assertEveryTargetIsAtLeast48dp()
    }
}
