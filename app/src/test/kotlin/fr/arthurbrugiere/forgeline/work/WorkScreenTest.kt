package fr.arthurbrugiere.forgeline.work

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.data.work.Work
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueSearchResult
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.WorkKind
import fr.arthurbrugiere.forgeline.core.testing.issueSummary
import fr.arthurbrugiere.forgeline.core.ui.theme.ForgelineTheme
import fr.arthurbrugiere.forgeline.inbox.InboxFilter
import fr.arthurbrugiere.forgeline.inbox.InboxScreen
import fr.arthurbrugiere.forgeline.inbox.InboxUiState
import fr.arthurbrugiere.forgeline.ui.assertEveryTargetIsAtLeast48dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class WorkScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()
    private val opened = mutableListOf<IssueRef>()
    private val tools = RepoId("octo", "tools")
    private val review = IssueSearchResult(tools, issueSummary(4, "Fix the upload", isPullRequest = true))
    private val own = IssueSearchResult(tools, issueSummary(5, "Add a dark theme", isPullRequest = true))
    private val assigned = IssueSearchResult(RepoId("alice", "notes", ForgeInstance.Codeberg), issueSummary(9, "Crash on start"))

    private fun work(
        reviews: List<IssueSearchResult> = listOf(review),
        mine: List<IssueSearchResult> = listOf(own),
        todo: List<IssueSearchResult> = listOf(assigned),
        forges: List<ForgeInstance> = listOf(ForgeInstance.GitHub, ForgeInstance.Codeberg),
        failed: List<ForgeInstance> = emptyList(),
    ) = Work(mapOf(WorkKind.REVIEW_REQUESTED to reviews, WorkKind.OWN_PULL_REQUESTS to mine, WorkKind.ASSIGNED to todo), forges, failed)

    private fun setContent(state: WorkUiState) = composeRule.setContent {
        ForgelineTheme {
            // The work is what the Inbox lists under "Yours".
            InboxScreen(
                state = InboxUiState(filter = InboxFilter.YOURS, syncedAtMillis = 1),
                onSelectFilter = { events += "filter:$it" },
                onRefresh = { events += "refresh-inbox" },
                onOpen = {}, onMarkRead = {}, onMarkDone = {}, onUnsubscribe = {},
                onErrorShown = {}, onActionFailureShown = {},
                work = state,
                onRefreshWork = { events += "refresh" },
                onOpenIssue = { opened += it },
                onWorkErrorShown = { events += "error-shown" },
                nowMillis = Instant.parse("2026-09-26T10:00:00Z").toEpochMilli(),
            )
        }
    }

    @Test
    fun the_work_is_set_under_what_it_is_reviews_first() {
        setContent(WorkUiState(work(), isLoading = false))

        composeRule.onNode(hasText("Inbox") and isHeading()).assertIsDisplayed()
        val reviews = composeRule.onNode(hasText("Waiting for your review") and isHeading()).assertIsDisplayed().fetchSemanticsNode().positionInRoot.y
        val mine = composeRule.onNode(hasText("Your open pull requests") and isHeading()).assertIsDisplayed().fetchSemanticsNode().positionInRoot.y
        val todo = composeRule.onNode(hasText("Assigned to you") and isHeading()).assertIsDisplayed().fetchSemanticsNode().positionInRoot.y
        assertThat(listOf(reviews, mine, todo)).isInOrder()
        composeRule.onNodeWithText("Fix the upload").assertIsDisplayed()
        composeRule.onNodeWithText("Add a dark theme").assertIsDisplayed()
        composeRule.onNodeWithText("Crash on start").assertIsDisplayed()
    }

    @Test
    fun a_conversation_opens_on_its_own_forge_as_what_it_is() {
        setContent(WorkUiState(work(), isLoading = false))

        composeRule.onNodeWithText("Crash on start").performClick()
        composeRule.onNodeWithText("Fix the upload").performClick()

        assertThat(opened).containsExactly(IssueRef(assigned.repo, 9, false), IssueRef(tools, 4, true)).inOrder()
    }

    @Test
    fun with_several_forges_each_conversation_names_its_own() {
        setContent(WorkUiState(work(), isLoading = false))

        composeRule.onNode(hasText("alice/notes", substring = true)).assertIsDisplayed()
        composeRule.onAllNodes(hasText("octo/tools", substring = true)).fetchSemanticsNodes().let { assertThat(it).hasSize(2) }
    }

    @Test
    fun a_kind_with_nothing_has_no_heading() {
        setContent(WorkUiState(work(reviews = emptyList(), todo = emptyList()), isLoading = false))

        composeRule.onNodeWithText("Your open pull requests").assertIsDisplayed()
        composeRule.onNodeWithText("Waiting for your review").assertDoesNotExist()
        composeRule.onNodeWithText("Assigned to you").assertDoesNotExist()
        composeRule.onNodeWithText("Nothing waits on you").assertDoesNotExist()
    }

    @Test
    fun no_work_at_all_is_said() {
        setContent(WorkUiState(work(emptyList(), emptyList(), emptyList()), isLoading = false))

        composeRule.onNodeWithText("Nothing waits on you").assertIsDisplayed()
    }

    @Test
    fun a_forge_that_could_not_be_asked_is_named_rather_than_read_as_nothing_to_do() {
        setContent(WorkUiState(work(emptyList(), emptyList(), emptyList(), failed = listOf(ForgeInstance.Codeberg)), isLoading = false))

        composeRule.onNode(hasText("Codeberg couldn't be asked", substring = true)).assertIsDisplayed()
        composeRule.onNodeWithText("Nothing waits on you").assertDoesNotExist()
    }

    @Test
    fun loading_says_so() {
        setContent(WorkUiState())

        composeRule.onNode(hasContentDescription("Loading your work")).assertExists()
    }

    @Test
    fun work_that_could_not_be_read_offers_to_try_again() {
        setContent(WorkUiState(isLoading = false, error = ForgeError.Network))

        composeRule.onNodeWithText("Couldn't load your work").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()

        assertThat(events).containsExactly("refresh")
    }

    @Test
    fun a_refresh_that_failed_is_said_once_over_what_was_read() {
        setContent(WorkUiState(work(), isLoading = false, error = ForgeError.Network))

        composeRule.waitUntil(5_000) { "error-shown" in events }
        composeRule.onNodeWithText("Fix the upload").assertIsDisplayed()
        composeRule.onNodeWithText("Couldn't load your work").assertDoesNotExist()
    }

    @Test
    fun the_other_filters_are_a_tap_away_and_every_target_is_large_enough() {
        setContent(WorkUiState(work(), isLoading = false))

        composeRule.assertEveryTargetIsAtLeast48dp()
        composeRule.onNodeWithText("Unread").performClick()

        assertThat(events).containsExactly("filter:UNREAD")
    }

    @Test
    fun before_the_forges_were_asked_it_says_loading_not_nothing() {
        composeRule.setContent {
            ForgelineTheme {
                InboxScreen(
                    state = InboxUiState(filter = InboxFilter.YOURS, syncedAtMillis = 1),
                    onSelectFilter = {}, onRefresh = {}, onOpen = {}, onMarkRead = {}, onMarkDone = {}, onUnsubscribe = {},
                    onErrorShown = {}, onActionFailureShown = {},
                )
            }
        }

        composeRule.onNode(hasContentDescription("Loading your work")).assertExists()
        composeRule.onNodeWithText("You're all caught up").assertDoesNotExist()
    }

    @Test
    fun a_gitlab_merge_request_and_issue_open_as_what_they_are() {
        val lab = RepoId("group/sub", "tool", ForgeInstance.GitLab)
        val mr = IssueSearchResult(lab, issueSummary(7, "Merge the fix", isPullRequest = true))
        val issue = IssueSearchResult(lab, issueSummary(7, "The same number, an issue"))
        setContent(WorkUiState(work(reviews = listOf(mr), mine = emptyList(), todo = listOf(issue), forges = listOf(ForgeInstance.GitLab)), isLoading = false))

        composeRule.onNodeWithText("Merge the fix").performClick()
        composeRule.onNodeWithText("The same number, an issue").performClick()

        assertThat(opened).containsExactly(IssueRef(lab, 7, true), IssueRef(lab, 7, false)).inOrder()
    }

    @Test
    fun with_one_forge_alone_rows_do_not_repeat_its_name() {
        setContent(WorkUiState(work(todo = emptyList(), forges = listOf(ForgeInstance.GitHub)), isLoading = false))

        composeRule.onAllNodes(hasText("GitHub", substring = true)).fetchSemanticsNodes().let { assertThat(it).isEmpty() }
    }

    @Test
    fun a_forge_still_to_answer_is_named_under_what_the_others_said() {
        setContent(WorkUiState(work(todo = emptyList()).copy(pending = listOf(ForgeInstance.Codeberg)), isLoading = true))

        composeRule.onNodeWithText("Fix the upload").assertIsDisplayed()
        composeRule.onNodeWithText("Still asking Codeberg…").assertIsDisplayed()
    }

    @Test
    fun nothing_yet_from_the_first_forges_is_not_called_nothing_while_one_is_still_asked() {
        setContent(WorkUiState(work(emptyList(), emptyList(), emptyList()).copy(pending = listOf(ForgeInstance.Codeberg)), isLoading = true))

        composeRule.onNodeWithText("Still asking Codeberg…").assertIsDisplayed()
        composeRule.onNodeWithText("Nothing waits on you").assertDoesNotExist()
    }
}
