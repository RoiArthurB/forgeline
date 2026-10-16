package fr.arthurbrugiere.forgeline.user

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.testing.userProfile
import fr.arthurbrugiere.forgeline.repo.Loadable
import org.junit.Rule
import fr.arthurbrugiere.forgeline.ui.assertEveryTargetIsAtLeast48dp
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.hasText
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
                onOpenUrl = { events += "url:$it" }, onFollowFailureShown = {},
            )
        }
    }

    private val linked = base.copy(
        profile = base.profile!!.copy(
            links = listOfNotNull(
                fr.arthurbrugiere.forgeline.core.model.ProfileLink.email("octocat@example.org"),
                fr.arthurbrugiere.forgeline.core.model.ProfileLink.of("https://matrix.to/#/@octocat:matrix.org"),
                fr.arthurbrugiere.forgeline.core.model.ProfileLink.of("https://fosstodon.org/@octocat"),
            ),
        ),
    )

    @Test
    fun a_readme_is_offered_first_and_shown_in_its_tab() {
        val readme = fr.arthurbrugiere.forgeline.core.model.ProfileReadme(
            RepoId("octocat", "octocat"),
            fr.arthurbrugiere.forgeline.core.model.Readme("README.md", "Hi, I build forges for phones."),
        )
        setContent(base.copy(readme = readme, tab = UserTab.README))

        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Hi, I build forges for phones.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("README").assertIsDisplayed()
        // The repositories wait behind their own tab.
        composeRule.onNodeWithText("octocat/Hello-World", substring = true).assertDoesNotExist()
    }

    @Test
    fun without_a_readme_there_is_no_readme_tab() {
        setContent(base)

        composeRule.onNodeWithText("README").assertDoesNotExist()
    }

    @Test
    fun each_link_shows_how_people_write_it_and_opens_where_it_points() {
        setContent(linked)

        composeRule.onNodeWithText("@octocat@fosstodon.org").assertIsDisplayed()
        composeRule.onNodeWithText("@octocat:matrix.org").performClick()
        composeRule.onNodeWithText("octocat@example.org").performClick()

        assertThat(events).containsExactly("url:https://matrix.to/#/@octocat:matrix.org", "url:mailto:octocat@example.org").inOrder()
    }

    @Test
    fun a_link_says_its_service_to_screen_readers_since_only_its_logo_shows_it() {
        setContent(linked)

        composeRule.onNode(androidx.compose.ui.test.hasContentDescription("Mastodon, @octocat@fosstodon.org")).assertExists()
        composeRule.assertEveryTargetIsAtLeast48dp()
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

    @Test
    fun the_header_meets_touch_targets_and_names_its_hero() {
        setContent(base.copy(profile = base.profile!!.copy(website = "octo.example")))

        composeRule.assertEveryTargetIsAtLeast48dp()
        composeRule.onNode(hasText("The Octocat") and isHeading()).assertIsDisplayed()
    }

    @Test
    fun a_profile_is_shared_by_its_address_on_its_own_forge() {
        setContent(base)
        composeRule.onNode(androidx.compose.ui.test.hasContentDescription("Share link")).performClick()
        com.google.common.truth.Truth.assertThat(fr.arthurbrugiere.forgeline.ui.sharedLink()).isEqualTo("https://github.com/octocat" to "octocat")
    }

    @Test
    fun a_profile_on_a_self_hosted_server_is_shared_by_its_address_there() {
        setContent(base.copy(forge = fr.arthurbrugiere.forgeline.core.model.ForgeInstance(fr.arthurbrugiere.forgeline.core.model.ForgeType.FORGEJO, "git.example.org")))

        composeRule.onNode(androidx.compose.ui.test.hasContentDescription("Share link")).performClick()

        com.google.common.truth.Truth.assertThat(fr.arthurbrugiere.forgeline.ui.sharedLink()?.first).isEqualTo("https://git.example.org/octocat")
    }
}
