package fr.arthurbrugiere.forgeline

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.LineComment
import fr.arthurbrugiere.forgeline.core.model.MergeMethod
import fr.arthurbrugiere.forgeline.core.model.ReviewVerdict
import fr.arthurbrugiere.forgeline.core.testing.FakePullRequestApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/** The whole way through a pull request, signed in: from the Inbox to its change, a review of it, and its merge. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE, application = HiltTestApplication::class)
class PullRequestFlowTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var accounts: AccountRepository

    @Inject lateinit var pulls: FakePullRequestApi

    @After
    fun signOut() = runBlocking { accounts.accounts.first().forEach { accounts.signOut(it.id) } }

    private fun waitFor(text: String) =
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForDescription(text: String) =
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasContentDescription(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    /** Signs in, then opens the pull request a review is asked for, from the Inbox's own-work filter. */
    private fun openPullRequest() {
        hiltRule.inject()
        runBlocking { accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", "The Octocat", null), "tok") }
        waitFor("Yours")
        composeRule.onNodeWithText("Yours").performClick()
        waitFor("Retry uploads on slow links")
        composeRule.onNodeWithText("Retry uploads on slow links").performClick()
        waitFor("2 files changed")
    }

    @Test
    fun a_pull_request_leads_to_its_change_where_a_line_is_remarked_on_and_a_review_sent() {
        openPullRequest()
        // What ran on it is said beside its numbers.
        waitFor("1 of 2 checks failed")

        composeRule.onNodeWithText("2 files changed").performClick()
        waitFor("Files changed")
        composeRule.onNodeWithText("src/Upload.kt").assertIsDisplayed()
        waitForDescription("Line 2, added: retry { send(file) }")
        composeRule.onNodeWithContentDescription("Line 2, added: retry { send(file) }").performClick()
        waitFor("Add to the review")
        composeRule.onNode(hasSetTextAction()).performTextInput("Why not three times?")
        composeRule.onNodeWithText("Add to the review").performClick()
        waitFor("Finish review (1 remark)")

        composeRule.onNodeWithText("Finish review (1 remark)").performClick()
        waitFor("Send review")
        composeRule.onNodeWithText("Request changes").performClick()
        composeRule.onNodeWithText("Send review").performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(5_000) { pulls.reviews.isNotEmpty() }

        assertThat(pulls.reviews.single()).isEqualTo(
            FakePullRequestApi.SentReview(
                ReviewVerdict.REQUEST_CHANGES, "",
                listOf(LineComment("src/Upload.kt", oldLine = null, newLine = 2, body = "Why not three times?")),
            ),
        )
        // The remark went with the review: nothing is left waiting.
        waitFor("Review sent")
        composeRule.onNodeWithText("Why not three times?").assertDoesNotExist()
    }

    @Test
    fun a_pull_request_s_commits_each_open_on_what_they_changed() {
        openPullRequest()

        composeRule.onNodeWithText("2 commits").performClick()
        waitFor("Say so in the docs")
        composeRule.onNodeWithText("Say so in the docs").performClick()

        // The commit's own page: its one file, not the pull request's two.
        waitFor("docs/retry.md")
        waitForDescription("Line 1, added: Retries three times.")
        composeRule.onNodeWithText("src/Upload.kt").assertDoesNotExist()
        composeRule.onNode(hasContentDescription("Navigate up")).performClick()
        waitFor("Retry the upload")
    }

    @Test
    fun someone_who_can_merge_merges_from_the_conversation() {
        openPullRequest()
        waitFor("Merge")

        composeRule.onNodeWithText("Merge").performClick()
        waitFor("Merge pull request")
        composeRule.onNodeWithText("Squash and merge").performClick()
        composeRule.onNodeWithText("Confirm merge").performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(5_000) { pulls.calls.any { it.startsWith("merge:") } }

        assertThat(pulls.calls).contains("merge:octo/tools#88:${MergeMethod.SQUASH}")
        // The sheet closes on what it did.
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Merge pull request")).fetchSemanticsNodes().isEmpty() }
    }
}
