package fr.arthurbrugiere.forgeline.user

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.testing.userProfile
import fr.arthurbrugiere.forgeline.repo.Loadable
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class UserScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()
    private val repo = RepoSummary(RepoId("octocat", "Hello-World"), "My first repo", "Kotlin", 2_500, 10, false, null)
    private val base = UserUiState("octocat", profile = userProfile("octocat", name = "The Octocat"), repos = Loadable.Loaded(listOf(repo)))

    private fun setContent(state: UserUiState, signedIn: Boolean = true) {
        composeRule.setContent {
            UserScreen(
                state = state, signedIn = signedIn, onBack = {},
                onSelectTab = { events += "tab:$it" }, onRetry = {}, onToggleFollow = { events += "follow" },
                onSignIn = { events += "signin" }, onOpenRepo = { events += "repo:${it.fullName}" },
                onOpenUrl = {}, onFollowFailureShown = {},
            )
        }
    }

    @Test
    fun shows_the_profile_and_repositories() {
        setContent(base)

        composeRule.onNodeWithText("The Octocat").assertIsDisplayed()
        composeRule.onNodeWithText("@octocat").assertIsDisplayed()
        composeRule.onNodeWithText("10 followers · 2 following").assertIsDisplayed()
        composeRule.onNodeWithText("octocat/Hello-World").performClick()

        assertThat(events).containsExactly("repo:octocat/Hello-World")
    }

    @Test
    fun the_follow_button_reflects_and_toggles_the_state() {
        setContent(base.copy(following = true))

        composeRule.onNodeWithText("Following").performClick()

        assertThat(events).containsExactly("follow")
    }

    @Test
    fun signed_out_following_leads_to_sign_in() {
        setContent(base, signedIn = false)

        composeRule.onNodeWithText("Follow").performClick()

        assertThat(events).containsExactly("signin")
    }

    @Test
    fun the_own_profile_has_no_follow_button() {
        setContent(base.copy(following = null))

        composeRule.onNodeWithText("Follow").assertDoesNotExist()
        composeRule.onNodeWithText("Following").assertDoesNotExist()
    }

    @Test
    fun tabs_switch_between_repositories_and_stars() {
        setContent(base)

        composeRule.onNodeWithText("Starred").performClick()

        assertThat(events).containsExactly("tab:STARRED")
    }
}
