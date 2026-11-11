package fr.arthurbrugiere.forgeline.user

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.testing.userProfile
import fr.arthurbrugiere.forgeline.repo.Loadable
import fr.arthurbrugiere.forgeline.ui.assertEveryTargetIsAtLeast48dp
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

    private fun setContent(state: UserUiState, signedIn: Boolean = true, roomy: Boolean = false) {
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(fr.arthurbrugiere.forgeline.ui.LocalUserSettings provides fr.arthurbrugiere.forgeline.core.model.UserSettings(roomyTargets = roomy)) {
                UserScreen(
                    state = state, signedIn = signedIn, onBack = {},
                    onSelectTab = { events += "tab:$it" }, onRetry = {}, onToggleFollow = { events += "follow" },
                    onSignIn = { events += "signin" }, onOpenRepo = { events += "repo:${it.fullName}" },
                    onOpenUrl = { events += "url:$it" }, onFollowFailureShown = {},
                    onLinkClick = { events += "link:$it" },
                )
            }
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
    fun a_link_in_the_readme_goes_through_the_app() {
        // Regression: a repository linked from a profile README opened in the browser rather than in the app.
        val readme = fr.arthurbrugiere.forgeline.core.model.ProfileReadme(
            RepoId("octocat", "octocat"),
            fr.arthurbrugiere.forgeline.core.model.Readme("README.md", "See [my forge](https://github.com/octocat/Hello-World)."),
        )
        setContent(base.copy(readme = readme, tab = UserTab.README))

        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("my forge", substring = true).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("my forge", substring = true).performClick()

        assertThat(events).contains("link:https://github.com/octocat/Hello-World")
        assertThat(events.filter { it.startsWith("url:") }).isEmpty()
    }

    @Test
    fun a_sourcehut_login_keeps_its_own_mark() {
        val sourceHut = fr.arthurbrugiere.forgeline.core.model.ForgeInstance(fr.arthurbrugiere.forgeline.core.model.ForgeType.SOURCEHUT, "git.sr.ht")
        setContent(UserUiState("~sircmpwn", forge = sourceHut, profile = userProfile("~sircmpwn", name = "Drew"), repos = Loadable.Loaded(emptyList())))

        composeRule.onNodeWithText("~sircmpwn").assertIsDisplayed()
        composeRule.onNodeWithText("@~sircmpwn").assertDoesNotExist()
    }

    @Test
    fun a_repository_whose_stars_the_forge_didn_t_say_reads_none() {
        // Regression: every repository on a Tangled profile read "0 stars".
        val unsaid = repo.copy(stars = 0, starsKnown = false)
        setContent(base.copy(repos = Loadable.Loaded(listOf(unsaid)), tab = UserTab.REPOS))

        composeRule.onNodeWithText("Hello-World", substring = true, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("0 stars", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun a_forge_where_nobody_stars_has_no_starred_tab() {
        val sourceHut = fr.arthurbrugiere.forgeline.core.model.ForgeInstance(fr.arthurbrugiere.forgeline.core.model.ForgeType.SOURCEHUT, "git.sr.ht")
        setContent(UserUiState("~sircmpwn", forge = sourceHut, profile = userProfile("~sircmpwn"), repos = Loadable.Loaded(listOf(repo))))

        composeRule.onNodeWithText("Starred").assertDoesNotExist()
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
    fun with_roomy_links_each_link_is_a_finger_tall_on_its_own_not_only_by_what_compose_lends_it() {
        // Regression: the links were 20dp lines 10dp apart. Compose widens a small target's touch area to 48dp, but two
        // such areas overlap, so a tap between links went to the nearer one and a near miss opened another app.
        setContent(linked.copy(profile = linked.profile!!.copy(website = "octo.example.org")), roomy = true)

        val rows = listOf("octo.example.org", "Email, octocat@example.org", "Matrix, @octocat:matrix.org", "Mastodon, @octocat@fosstodon.org").map { name ->
            composeRule.onNode(androidx.compose.ui.test.hasText(name) or androidx.compose.ui.test.hasContentDescription(name)).fetchSemanticsNode().boundsInRoot
        }
        val density = composeRule.density.density
        rows.forEach { assertThat(it.height / density).isAtLeast(47.5f) }
        rows.zipWithNext().forEach { (above, below) -> assertThat(below.top).isAtLeast(above.bottom - 0.5f) }
    }

    @Test
    fun the_links_keep_their_compact_lines_unless_they_are_asked_to_be_roomy() {
        setContent(linked.copy(profile = linked.profile!!.copy(website = "octo.example.org")))

        val rows = listOf("octo.example.org", "Email, octocat@example.org", "Matrix, @octocat:matrix.org").map { name ->
            composeRule.onNode(androidx.compose.ui.test.hasText(name) or androidx.compose.ui.test.hasContentDescription(name)).fetchSemanticsNode().boundsInRoot
        }
        val density = composeRule.density.density
        rows.forEach { assertThat(it.height / density).isAtMost(30f) }
        // 10dp apart, as before roomy links were offered.
        rows.zipWithNext().forEach { (above, below) -> assertThat((below.top - above.bottom) / density).isWithin(0.5f).of(10f) }
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

    @Test
    fun a_year_of_activity_says_how_much_and_when_the_most() {
        val year = fr.arthurbrugiere.forgeline.core.model.Contributions(
            mapOf(java.time.LocalDate.now().minusDays(3) to 12, java.time.LocalDate.now().minusDays(40) to 1_230),
        )
        setContent(base.copy(contributions = year))

        composeRule.onNodeWithText("1.2k contributions in the last year", useUnmergedTree = true).assertExists()
        composeRule.onNode(androidx.compose.ui.test.hasContentDescription("The busiest day was", substring = true)).assertExists()
    }

    @Test
    fun without_activity_there_is_no_graph() {
        setContent(base)

        composeRule.onNodeWithText("contributions in the last year", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }
}
