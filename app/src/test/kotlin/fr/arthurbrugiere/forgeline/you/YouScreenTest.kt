package fr.arthurbrugiere.forgeline.you

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.model.Account
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.session.SessionState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class YouScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun signed_in_shows_the_account() {
        val account = Account("id", ForgeInstance.GitHub, ForgeUser("octocat", "The Octocat", null))
        composeRule.setContent { YouScreen(SessionState.SignedIn(account), onSignIn = {}, onOpenSettings = {}) }

        composeRule.onNodeWithText("The Octocat").assertIsDisplayed()
        composeRule.onNodeWithText("@octocat").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("GitHub").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in").assertDoesNotExist()
    }

    @Test
    fun signed_out_invites_to_sign_in() {
        var signIn = false
        composeRule.setContent { YouScreen(SessionState.SignedOut, onSignIn = { signIn = true }, onOpenSettings = {}) }

        composeRule.onNodeWithText("Sign in").performClick()

        assertThat(signIn).isTrue()
    }

    @Test
    fun every_account_is_there_and_opens_its_own_profile() {
        val gitHub = Account("github:github.com:octocat", ForgeInstance.GitHub, ForgeUser("octocat", "The Octocat", null))
        val codeberg = Account("forgejo:codeberg.org:alice", ForgeInstance.Codeberg, ForgeUser("alice", "Alice", null))
        val opened = mutableListOf<Account>()
        var signIn = false
        composeRule.setContent {
            YouScreen(SessionState.SignedIn(gitHub, listOf(gitHub, codeberg)), onSignIn = { signIn = true }, onOpenSettings = {}, onOpenProfile = { opened += it })
        }

        composeRule.onNodeWithText("The Octocat").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Codeberg").assertIsDisplayed()
        composeRule.onNodeWithText("Alice").performClick()
        composeRule.onNodeWithText("Add an account").performClick()

        assertThat(opened).containsExactly(codeberg)
        assertThat(signIn).isTrue()
    }
}
