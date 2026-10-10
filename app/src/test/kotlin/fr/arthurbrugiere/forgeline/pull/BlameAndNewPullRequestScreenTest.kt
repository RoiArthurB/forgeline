package fr.arthurbrugiere.forgeline.pull

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.testing.commit
import fr.arthurbrugiere.forgeline.core.ui.theme.ForgelineTheme
import fr.arthurbrugiere.forgeline.file.FileTarget
import fr.arthurbrugiere.forgeline.repo.Loadable
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class BlameAndNewPullRequestScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()
    private val tools = RepoId("octo", "tools")
    private val file = FileTarget(tools, "src/Main.kt", "main")

    private fun blame(blocks: Loadable<List<BlameBlock>>) = composeRule.setContent {
        ForgelineTheme {
            BlameScreen(
                BlameUiState(file, blocks),
                onBack = { events += "back" }, onRetry = { events += "retry" }, onOpenCommit = { events += "commit:$it" }, onSignIn = { events += "sign-in" },
                nowMillis = Instant.parse("2026-09-26T10:00:00Z").toEpochMilli(),
            )
        }
    }

    @Test
    fun each_run_of_lines_reads_under_the_commit_that_left_it() {
        blame(
            Loadable.Loaded(
                listOf(
                    BlameBlock(commit("1a2b3c4d5e", "Start the project"), 1, listOf("package x", "")),
                    BlameBlock(commit("9f8e7d6c5b", "Add main", author = "hubot"), 3, listOf("fun main() {}")),
                ),
            ),
        )

        composeRule.onNode(hasText("Blame") and isHeading()).assertIsDisplayed()
        composeRule.onNodeWithText("src/Main.kt · main").assertIsDisplayed()
        composeRule.onNodeWithText("Start the project").assertIsDisplayed()
        composeRule.onNodeWithText("hubot · 2 hours ago · 9f8e7d6").assertIsDisplayed()
        composeRule.onNodeWithText("fun main() {}").assertIsDisplayed()
        // Numbered as in the file, across the commits.
        composeRule.onNodeWithText("3").assertIsDisplayed()
        composeRule.onNodeWithText("Add main").performClick()

        assertThat(events).containsExactly("commit:9f8e7d6c5b")
    }

    @Test
    fun signed_out_on_github_blame_asks_to_sign_in() {
        blame(Loadable.Failed(ForgeError.Unauthorized))

        composeRule.onNodeWithText("Sign in to see who changed what").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in").performClick()

        assertThat(events).containsExactly("sign-in")
    }

    @Test
    fun a_blame_that_could_not_be_read_offers_to_try_again() {
        blame(Loadable.Failed(ForgeError.Network))

        composeRule.onNodeWithText("Couldn't load the blame").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()

        assertThat(events).containsExactly("retry")
    }

    private fun form(state: NewPullRequestUiState) = composeRule.setContent {
        ForgelineTheme {
            NewPullRequestScreen(
                state,
                onBack = { events += "back" }, onRetry = { events += "retry" },
                onBaseChange = { events += "base:$it" }, onHeadChange = { events += "head:$it" },
                onTitleChange = { events += "title:$it" }, onBodyChange = { events += "body:$it" },
                onDraftChange = { events += "draft:$it" }, onSend = { events += "send" },
            )
        }
    }

    private val branches = Loadable.Loaded(listOf("main", "fix/slow-uploads", "release"))

    @Test
    fun the_form_says_where_the_change_goes_and_asks_where_it_comes_from() {
        form(NewPullRequestUiState(tools, branches, base = "main"))

        composeRule.onNode(hasText("New pull request") and isHeading()).assertIsDisplayed()
        composeRule.onNodeWithText("Open pull request").performScrollTo().assertIsNotEnabled()
        // The pill is read out by what it chooses, and where it stands.
        composeRule.onNode(androidx.compose.ui.test.hasContentDescription("Branch that holds the change")).performScrollTo().performClick()
        composeRule.onNodeWithText("fix/slow-uploads").performClick()
        composeRule.onNodeWithText("Open as a draft").performScrollTo().performClick()
        composeRule.onNodeWithText("Title").performScrollTo().performTextInput("R")

        assertThat(events).containsExactly("head:fix/slow-uploads", "draft:true", "title:R").inOrder()
    }

    @Test
    fun a_form_filled_in_is_sent() {
        form(NewPullRequestUiState(tools, branches, base = "main", head = "release", title = "Release 2"))

        composeRule.onNodeWithText("Open pull request").performScrollTo().assertIsEnabled().performClick()

        assertThat(events).containsExactly("send")
    }

    @Test
    fun the_same_branch_twice_and_a_refusal_are_said() {
        form(NewPullRequestUiState(tools, branches, base = "main", head = "main", title = "T", error = ForgeError.Http(422, "No commits between main and main")))

        composeRule.onNodeWithText("A pull request goes from one branch into another.").assertIsDisplayed()
        composeRule.onNodeWithText("The forge refused the pull request: No commits between main and main").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Open pull request").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun a_repository_with_one_branch_says_there_is_nothing_to_open_one_from() {
        form(NewPullRequestUiState(tools, Loadable.Loaded(listOf("main")), base = "main"))

        composeRule.onNodeWithText("Nothing to open a pull request from").assertIsDisplayed()
        composeRule.onNodeWithText("Open pull request").assertDoesNotExist()
    }

    @Test
    fun branches_that_could_not_be_listed_offer_to_try_again() {
        form(NewPullRequestUiState(tools, Loadable.Failed(ForgeError.Network)))

        composeRule.onNodeWithText("Couldn't load the branches").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()

        assertThat(events).containsExactly("retry")
    }
}
