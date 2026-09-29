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
import fr.arthurbrugiere.forgeline.core.model.FeedPreviews
import fr.arthurbrugiere.forgeline.core.model.IssueAction
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.PullRequestAction
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoPreview
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

    private fun setContent(state: FeedUiState, previews: FeedPreviews = FeedPreviews()) {
        composeRule.setContent {
            FeedScreen(
                state = state.copy(previews = previews),
                onRefresh = { events += "refresh" },
                onLoadMore = { events += "more" },
                onOpenRepo = { events += "repo:${it.fullName}" },
                onOpenIssue = { events += "issue:${it.repo.fullName}#${it.number}" },
                onOpenUser = { events += "user:$it" },
                onErrorShown = {},
                nowMillis = Instant.parse("2026-09-27T10:00:00Z").toEpochMilli(),
                zone = java.time.ZoneOffset.UTC,
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

        composeRule.onNodeWithText("alice and bob starred ·\u00A01\u00A0hr.\u00A0ago", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("carol and dave starred", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("acme/\u2060rocket").assertIsDisplayed()
        composeRule.onNodeWithText("octo/\u2060tools").assertIsDisplayed()
    }

    @Test
    fun many_stars_are_counted() {
        setContent(state(feedEvent("3", actor = "alice"), feedEvent("2", actor = "bob"), feedEvent("1", actor = "carol")))

        composeRule.onNodeWithText("alice and 2 others starred", substring = true).assertIsDisplayed()
    }

    @Test
    fun each_event_leads_with_its_kind_and_object() {
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
            FeedPreviews(pullTitles = mapOf(IssueRef(RepoId("acme", "rocket"), 43) to "Retry the fuel pump")),
        )

        composeRule.onNodeWithText("carol closed an issue in acme/\u2060rocket", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Closed").assertIsDisplayed()
        composeRule.onNodeWithText("Launch fails").assertIsDisplayed()
        composeRule.onNodeWithText("bob merged a pull request in acme/\u2060rocket", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Merged").assertIsDisplayed()
        composeRule.onNodeWithText("#43").assertIsDisplayed()
        composeRule.onNodeWithText("Retry the fuel pump").assertIsDisplayed()
        composeRule.onNodeWithText("Approved").assertIsDisplayed()
        composeRule.onNodeWithText("v2.0.0").assertIsDisplayed()
        composeRule.onNodeWithText("Tools 2.0").assertIsDisplayed()
        composeRule.onNodeWithText("carol forked to carol/\u2060tools", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("A fresh idea").assertIsDisplayed()
        composeRule.onNodeWithText("main").assertIsDisplayed()
    }

    @Test
    fun starred_repos_show_their_preview() {
        setContent(
            state(feedEvent("1")),
            FeedPreviews(repos = mapOf(RepoId("acme", "rocket") to RepoPreview("Tiny satellites, in Rust.", "Rust", 12_400))),
        )

        composeRule.onNodeWithText("Tiny satellites, in Rust.").assertIsDisplayed()
        composeRule.onNodeWithText("Rust").assertIsDisplayed()
        composeRule.onNodeWithText("12.4k", substring = true).assertIsDisplayed()
    }

    @Test
    fun events_are_grouped_by_day() {
        setContent(
            state(
                feedEvent("3", createdAt = "2026-09-27T09:00:00Z"),
                feedEvent("2", actor = "bob", repo = "octo/tools", createdAt = "2026-09-20T09:00:00Z"),
            ),
        )

        composeRule.onNodeWithText("Today").assertIsDisplayed()
        composeRule.onNodeWithText("Earlier").assertIsDisplayed()
    }

    @Test
    fun visible_rows_ask_for_their_previews() {
        val visible = mutableListOf<String>()
        composeRule.setContent {
            FeedScreen(
                state = state(feedEvent("2", action = FeedAction.PullRequest(PullRequestAction.OPENED, 7)), feedEvent("1", repo = "octo/tools")),
                onRefresh = {}, onLoadMore = {}, onOpenRepo = {}, onOpenIssue = {}, onOpenUser = {}, onErrorShown = {},
                onVisible = { items -> visible += items.map { it.key } },
                nowMillis = Instant.parse("2026-09-27T10:00:00Z").toEpochMilli(),
                zone = java.time.ZoneOffset.UTC,
            )
        }

        composeRule.waitForIdle()
        assertThat(visible).containsAtLeast("2", "1")
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

        composeRule.onNodeWithText("Launch fails").performClick()
        composeRule.onNodeWithText("carol forked to carol/\u2060tools", substring = true).performClick()
        composeRule.onNodeWithText("alice starred", substring = true).performClick()
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
