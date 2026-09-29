package fr.arthurbrugiere.forgeline.issue

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.PullRequestInfo
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewState
import fr.arthurbrugiere.forgeline.core.model.StateChange
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import fr.arthurbrugiere.forgeline.core.testing.comment
import fr.arthurbrugiere.forgeline.core.testing.issueDetails
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class IssueScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()
    private val ref = IssueRef(RepoId("octo", "repo"), 7)
    private val at = Instant.parse("2026-09-26T09:00:00Z")

    private fun setContent(state: IssueUiState) {
        composeRule.setContent {
            IssueScreen(
                state = state,
                onBack = {}, onRefresh = { events += "refresh" }, onLoadMore = { events += "more" },
                onOpenIssue = { events += "issue:${it.repo.fullName}#${it.number}" },
                onOpenRepo = { events += "repo:${it.fullName}" },
                onOpenUser = { events += "user:$it" },
                onOpenInBrowser = { events += "browser:$it" },
                onLinkClick = { events += "link:$it" },
                onErrorShown = {},
                nowMillis = at.toEpochMilli(),
            )
        }
    }

    private fun waitFor(text: String) =
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun shows_the_issue_and_its_comments() {
        setContent(IssueUiState(ref, issueDetails(ref, "Crash on start"), listOf(comment(1, "Same here", login = "hubot"))))

        // The header field names the conversation, its number after the title.
        composeRule.onNodeWithText("Crash on start - #7").assertIsDisplayed()
        composeRule.onNodeWithText("Open").assertIsDisplayed()
        waitFor("Same here")
        composeRule.onNodeWithText("hubot").assertIsDisplayed()
    }

    @Test
    fun a_merged_pull_request_shows_branches_and_stats() {
        val pr = issueDetails(ref, "Fix it", IssueState.MERGED)
            .copy(pullRequest = PullRequestInfo(false, true, "main", "fix/it", 12, 3, 2, 1))
        setContent(IssueUiState(ref, pr))

        composeRule.onNodeWithText("Merged").assertIsDisplayed()
        composeRule.onNodeWithText("main ← fix/it").assertIsDisplayed()
        composeRule.onNodeWithText("+12 −3 · 2 files · 1 commit").assertIsDisplayed()
    }

    @Test
    fun timeline_events_read_as_sentences() {
        val items = listOf(
            TimelineItem.Review(1, ForgeUser("rev", null, null), ReviewState.APPROVED, null, at),
            TimelineItem.StateChanged(StateChange.CLOSED, ForgeUser("maint", null, null), "completed", at),
            TimelineItem.Renamed("Old", "New", ForgeUser("maint", null, null), at),
        )
        setContent(IssueUiState(ref, issueDetails(ref), items))

        composeRule.onNodeWithText("rev approved these changes", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("maint closed this as completed", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("maint changed the title from “Old” to “New”").assertIsDisplayed()
    }

    @Test
    fun the_top_bar_names_the_conversation_before_its_number() {
        setContent(IssueUiState(ref, issueDetails(ref, "Crash on start")))

        composeRule.onNodeWithText("Crash on start - #7").assertIsDisplayed()
    }

    @Test
    fun the_repository_it_belongs_to_opens_from_the_header() {
        setContent(IssueUiState(ref, issueDetails(ref, "Crash on start")))

        composeRule.onNodeWithText("octo/repo").performClick()

        assertThat(events).containsExactly("repo:octo/repo")
    }

    @Test
    fun before_the_conversation_loads_the_top_bar_shows_its_number() {
        setContent(IssueUiState(ref))

        composeRule.onNodeWithText("#7").assertIsDisplayed()
    }

    @Test
    fun label_events_show_the_label() {
        // Regression: the label chip sat next to a full-width line and was squeezed to zero width.
        val items = listOf(
            TimelineItem.Labeled(true, Label("bug", "d73a4a"), ForgeUser("maint", null, null), at),
            TimelineItem.Labeled(false, Label("wontfix", "ffffff"), ForgeUser("maint", null, null), at),
        )
        setContent(IssueUiState(ref, issueDetails(ref), items))

        waitFor("maint removed")
        for ((line, label) in listOf("maint added" to "bug", "maint removed" to "wontfix")) {
            val text = composeRule.onNodeWithText(line).fetchSemanticsNode().boundsInRoot
            val chip = composeRule.onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertThat(chip.width).isGreaterThan(0f)
            assertThat(chip.left).isAtLeast(text.right)
        }
    }

    @Test
    fun reactions_are_shown_with_their_counts() {
        setContent(IssueUiState(ref, issueDetails(ref).copy(reactions = mapOf(Reaction.THUMBS_UP to 3, Reaction.ROCKET to 1))))

        composeRule.onNodeWithText("👍 3").assertIsDisplayed()
        composeRule.onNodeWithText("🚀 1").assertIsDisplayed()
    }

    @Test
    fun cross_references_and_authors_are_navigable() {
        val source = IssueRef(RepoId("octo", "other"), 42)
        val items = listOf(TimelineItem.CrossReferenced(source, "Related bug", false, ForgeUser("bob", null, null), at))
        setContent(IssueUiState(ref, issueDetails(ref), items))

        composeRule.onNodeWithText("bob mentioned this in #42 Related bug").performClick()
        composeRule.onNodeWithText("octocat opened this", substring = true).performClick()

        assertThat(events).containsExactly("issue:octo/other#42", "user:octocat").inOrder()
    }

    @Test
    fun long_conversations_load_more_on_demand() {
        setContent(IssueUiState(ref, issueDetails(ref), nextPage = 2))

        composeRule.onNodeWithText("Load more").performClick()

        assertThat(events).containsExactly("more")
    }

    @Test
    fun without_anything_to_show_an_error_offers_a_retry() {
        setContent(IssueUiState(ref, error = ForgeError.Network))

        composeRule.onNodeWithText("Couldn't open this conversation").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()

        assertThat(events).containsExactly("refresh")
    }
}
