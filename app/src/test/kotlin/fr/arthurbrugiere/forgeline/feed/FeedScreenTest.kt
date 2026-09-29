package fr.arthurbrugiere.forgeline.feed

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.FeedAction
import fr.arthurbrugiere.forgeline.core.model.FeedKind
import fr.arthurbrugiere.forgeline.core.model.IssueAction
import fr.arthurbrugiere.forgeline.core.model.PullRequestAction
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewState
import fr.arthurbrugiere.forgeline.core.testing.feedEvent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class FeedScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()

    private fun setContent(state: FeedUiState) {
        composeRule.setContent {
            FeedScreen(
                state = state,
                onRefresh = { events += "refresh" },
                onLoadMore = { events += "more" },
                onOpenRepo = { events += "repo:${it.fullName}" },
                onOpenIssue = { events += "issue:${it.repo.fullName}#${it.number}" },
                onOpenUser = { events += "user:$it" },
                onErrorShown = {},
                nowMillis = Instant.parse("2026-09-27T10:00:00Z").toEpochMilli(),
            )
        }
    }

    private fun state(vararg events: fr.arthurbrugiere.forgeline.core.model.FeedEvent, hasMore: Boolean = false) =
        FeedUiState(items = feedItems(events.toList(), FeedKind.entries.toSet()), syncedAtMillis = 1, hasMore = hasMore)

    @Test
    fun merged_stars_name_everyone() {
        setContent(
            state(
                feedEvent("3", actor = "alice"),
                feedEvent("2", actor = "bob"),
                feedEvent("1", actor = "carol", repo = "octo/tools"),
                feedEvent("0", actor = "dave", repo = "octo/tools"),
            ),
        )

        composeRule.onNodeWithText("alice and bob starred acme/\u2060rocket").assertIsDisplayed()
        composeRule.onNodeWithText("carol and dave starred octo/\u2060tools").assertIsDisplayed()
    }

    @Test
    fun three_or_more_people_are_summed_up() {
        setContent(state(feedEvent("3", actor = "alice"), feedEvent("2", actor = "bob"), feedEvent("1", actor = "carol")))

        composeRule.onNodeWithText("alice and 2 others starred acme/\u2060rocket").assertIsDisplayed()
    }

    @Test
    fun each_kind_of_activity_reads_as_a_sentence() {
        setContent(
            state(
                feedEvent("9", actor = "carol", action = FeedAction.Issue(IssueAction.CLOSED, 42, "Launch fails")),
                feedEvent("8", actor = "bob", action = FeedAction.PullRequest(PullRequestAction.MERGED, 43)),
                feedEvent("7", actor = "bob", action = FeedAction.Reviewed(44, ReviewState.APPROVED)),
                feedEvent("6", actor = "alice", repo = "octo/tools", action = FeedAction.Released("v2.0.0", "Tools 2.0", prerelease = false)),
                feedEvent("5", actor = "carol", repo = "octo/tools", action = FeedAction.Forked(RepoId("carol", "tools"))),
                feedEvent("4", actor = "alice", repo = "alice/idea", action = FeedAction.CreatedRepo("A fresh idea")),
                feedEvent("3", actor = "alice", repo = "alice/dotfiles", action = FeedAction.Pushed("main")),
            ),
        )

        composeRule.onNodeWithText("carol closed an issue in acme/\u2060rocket").assertIsDisplayed()
        composeRule.onNodeWithText("#42 Launch fails", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("bob merged pull request #43 in acme/\u2060rocket").assertIsDisplayed()
        composeRule.onNodeWithText("bob approved pull request #44 in acme/\u2060rocket").assertIsDisplayed()
        composeRule.onNodeWithText("alice released v2.0.0 of octo/\u2060tools").assertIsDisplayed()
        composeRule.onNodeWithText("carol forked octo/\u2060tools to carol/\u2060tools").assertIsDisplayed()
        composeRule.onNodeWithText("alice created alice/\u2060idea").assertIsDisplayed()
        composeRule.onNodeWithText("alice pushed to main in alice/\u2060dotfiles").assertIsDisplayed()
    }

    @Test
    fun rows_open_what_they_are_about() {
        setContent(
            state(
                feedEvent("3", actor = "carol", action = FeedAction.Issue(IssueAction.CLOSED, 42, "Launch fails")),
                feedEvent("2", actor = "carol", repo = "octo/tools", action = FeedAction.Forked(RepoId("carol", "tools"))),
                feedEvent("1", actor = "alice", repo = "octo/tools"),
            ),
        )

        composeRule.onNodeWithText("carol closed an issue in acme/\u2060rocket").performClick()
        composeRule.onNodeWithText("carol forked octo/\u2060tools to carol/\u2060tools").performClick()
        composeRule.onNodeWithText("alice starred octo/\u2060tools").performClick()
        composeRule.onNode(hasContentDescription("alice")).performClick()

        assertThat(events).containsExactly("issue:acme/rocket#42", "repo:carol/tools", "repo:octo/tools", "user:alice").inOrder()
    }

    @Test
    fun reaching_the_end_loads_older_activity() {
        setContent(state(feedEvent("1"), hasMore = true))

        composeRule.waitForIdle()
        assertThat(events).contains("more")
    }

    @Test
    fun an_empty_feed_explains_where_activity_comes_from() {
        setContent(FeedUiState(syncedAtMillis = 1))

        composeRule.onNodeWithText("Quiet for now").assertIsDisplayed()
    }

    @Test
    fun a_failed_first_load_offers_a_retry() {
        setContent(FeedUiState(error = ForgeError.Network))

        composeRule.onNodeWithText("Retry").performClick()
        assertThat(events).containsExactly("refresh")
    }
}
