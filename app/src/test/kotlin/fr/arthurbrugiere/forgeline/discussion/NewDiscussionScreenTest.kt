package fr.arthurbrugiere.forgeline.discussion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.DiscussionCategory
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.ui.theme.ForgelineTheme
import fr.arthurbrugiere.forgeline.ui.assertEveryTargetIsAtLeast48dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class NewDiscussionScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()
    private val repo = RepoId("octo", "repo")
    private val read = NewDiscussionUiState(
        repo,
        categories = listOf(DiscussionCategory("DIC_1", "Help", "Ask for help", isAnswerable = true), DiscussionCategory("DIC_2", "Ideas", null, isAnswerable = false)),
        category = "DIC_1",
    )

    private fun setContent(state: NewDiscussionUiState, signedIn: Boolean = true) {
        composeRule.setContent {
            ForgelineTheme {
                NewDiscussionScreen(
                    state = state, signedIn = signedIn,
                    onPickCategory = { events += "category:$it" }, onRetryCategories = { events += "retry" },
                    onTitleChange = { events += "title:$it" }, onBodyChange = { events += "body:$it" },
                    onSend = { events += "send" }, onSignIn = { events += "sign-in" }, onBack = { events += "back" },
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun the_categories_say_what_they_are_for_and_whether_they_take_an_answer_and_one_is_picked() {
        setContent(read)

        composeRule.onNodeWithText("Help").assertIsSelected()
        composeRule.onNodeWithText("Ask for help · Takes an answer").assertIsDisplayed()
        composeRule.onNodeWithText("Ideas").performClick()

        assertThat(events).containsExactly("category:DIC_2")
    }

    @Test
    fun its_title_and_text_are_written_and_it_is_started_once_it_has_a_title() {
        setContent(read)
        composeRule.onNodeWithText("Start discussion").assertIsNotEnabled()

        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("How do I page?")
        composeRule.onAllNodes(hasSetTextAction())[1].performTextInput("I can't find it.")

        assertThat(events).containsAtLeast("title:How do I page?", "body:I can't find it.").inOrder()
    }

    @Test
    fun with_a_title_it_is_sent() {
        setContent(read.copy(title = "How do I page?"))

        composeRule.onNodeWithText("Start discussion").assertIsEnabled().performClick()

        assertThat(events).containsExactly("send")
    }

    @Test
    fun categories_that_couldn_t_be_read_are_offered_again() {
        setContent(NewDiscussionUiState(repo, categoriesError = ForgeError.Network))

        composeRule.onNodeWithText("The categories couldn't be read. Try again.").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()

        assertThat(events).containsExactly("retry")
    }

    @Test
    fun one_refused_says_some_categories_take_only_those_who_look_after_the_repository() {
        setContent(read.copy(title = "Version 3", error = ForgeError.Http(403, "Forbidden")))

        composeRule.onNodeWithText("some only take those who look after the repository", substring = true).assertIsDisplayed()
    }

    @Test
    fun signed_out_it_offers_to_sign_in() {
        setContent(read, signedIn = false)

        composeRule.onNodeWithText("Sign in to GitHub to start a discussion.").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in").performClick()

        assertThat(events).containsExactly("sign-in")
    }

    @Test
    fun every_target_is_large_enough_to_tap() {
        setContent(read)

        composeRule.assertEveryTargetIsAtLeast48dp()
    }
}
