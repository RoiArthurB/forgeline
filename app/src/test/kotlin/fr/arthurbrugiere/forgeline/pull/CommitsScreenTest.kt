package fr.arthurbrugiere.forgeline.pull

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.testing.commit
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
class CommitsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()
    private val tools = RepoId("octo", "tools")

    private fun setContent(state: CommitsUiState) = composeRule.setContent {
        ForgelineTheme {
            CommitsScreen(
                state,
                onBack = { events += "back" },
                onRefresh = { events += "refresh" },
                onLoadMore = { events += "more" },
                onErrorShown = {},
                onOpenCommit = { events += "commit:$it" },
                nowMillis = Instant.parse("2026-09-26T10:00:00Z").toEpochMilli(),
            )
        }
    }

    private val commits = listOf(commit("1a2b3c4d5e", "Retry uploads\n\nOn slow links."), commit("9f8e7d6c5b", "Fix typo", author = "hubot"))

    @Test
    fun a_pull_request_s_commits_say_who_and_when_and_open_on_what_they_changed() {
        setContent(CommitsUiState(CommitsTarget.Pull(IssueRef(tools, 88, isPullRequest = true)), commits = commits, isLoading = false))

        composeRule.onNode(hasText("Commits") and isHeading()).assertIsDisplayed()
        composeRule.onNodeWithText("octo/tools#88").assertIsDisplayed()
        // The first line of the message names the commit; the rest is for its own page.
        composeRule.onNodeWithText("Retry uploads").assertIsDisplayed()
        composeRule.onNodeWithText("octocat · 2 hours ago · 1a2b3c4").assertIsDisplayed()
        composeRule.assertEveryTargetIsAtLeast48dp()
        composeRule.onNodeWithText("Fix typo").performClick()

        assertThat(events).containsExactly("commit:9f8e7d6c5b")
    }

    @Test
    fun a_file_s_history_names_the_file_and_where_it_is_read_from() {
        setContent(CommitsUiState(CommitsTarget.History(tools, ref = "main", path = "src/Upload.kt"), commits = commits, isLoading = false))

        composeRule.onNode(hasText("History") and isHeading()).assertIsDisplayed()
        composeRule.onNodeWithText("src/Upload.kt · main").assertIsDisplayed()
    }

    @Test
    fun a_repository_s_history_names_the_repository() {
        setContent(CommitsUiState(CommitsTarget.History(tools, ref = null, path = null), commits = commits, isLoading = false))

        composeRule.onNodeWithText("octo/tools").assertIsDisplayed()
    }

    @Test
    fun a_history_that_could_not_be_read_offers_to_try_again_and_an_empty_one_says_so() {
        setContent(CommitsUiState(CommitsTarget.History(tools, null, null), error = ForgeError.Network, isLoading = false))

        composeRule.onNodeWithText("Couldn't load the commits").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        assertThat(events).containsExactly("refresh")
    }
}
