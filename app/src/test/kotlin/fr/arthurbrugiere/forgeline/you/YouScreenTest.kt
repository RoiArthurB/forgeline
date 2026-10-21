package fr.arthurbrugiere.forgeline.you

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.model.Account
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.matches
import fr.arthurbrugiere.forgeline.session.SessionState
import fr.arthurbrugiere.forgeline.ui.assertEveryTargetIsAtLeast48dp
import fr.arthurbrugiere.forgeline.ui.assertTextFits
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
        composeRule.onNodeWithText("GitHub").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in").assertDoesNotExist()
    }

    @Test
    fun one_s_own_work_is_in_the_inbox_not_here() {
        // It had a row here, leading to a page that read as a second inbox.
        val account = Account("id", ForgeInstance.GitHub, ForgeUser("octocat", "The Octocat", null))
        composeRule.setContent { YouScreen(SessionState.SignedIn(account), onSignIn = {}, onOpenSettings = {}) }

        composeRule.onNodeWithText("Your work").assertDoesNotExist()
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
        composeRule.onNodeWithText("Codeberg").assertIsDisplayed()
        composeRule.onNodeWithText("Alice").performClick()
        composeRule.onNodeWithText("Add an account").performClick()

        assertThat(opened).containsExactly(codeberg)
        assertThat(signIn).isTrue()
    }

    @Test
    fun a_sign_in_that_stops_at_public_repositories_says_so_and_offers_to_renew_it() {
        // Regression: private repositories were silently out of reach, on sign-ins that only asked for public ones.
        val account = Account("id", ForgeInstance.GitHub, ForgeUser("octocat", "The Octocat", null))
        var signIn = false
        composeRule.setContent {
            YouScreen(SessionState.SignedIn(account, limited = setOf("id")), onSignIn = { signIn = true }, onOpenSettings = {})
        }

        composeRule.onNodeWithText("Private repositories are out of reach").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in to GitHub again").performClick()

        assertThat(signIn).isTrue()
    }

    @Test
    fun a_sign_in_that_reaches_everything_says_nothing() {
        val account = Account("id", ForgeInstance.GitHub, ForgeUser("octocat", "The Octocat", null))
        composeRule.setContent { YouScreen(SessionState.SignedIn(account), onSignIn = {}, onOpenSettings = {}) }

        composeRule.onNodeWithText("Private repositories are out of reach").assertDoesNotExist()
    }

    @Test
    fun a_sign_in_the_forge_refused_to_renew_names_the_account_and_offers_to_make_it_again() {
        // Regression: the refusal was silent: every sync of that account failed with "something went wrong".
        val account = Account("id", ForgeInstance.Codeberg, ForgeUser("octocat", "The Octocat", null))
        var signIn = false
        composeRule.setContent {
            YouScreen(SessionState.SignedIn(account, ended = setOf("id")), onSignIn = { signIn = true }, onOpenSettings = {})
        }

        composeRule.onNodeWithText("Your sign-in to Codeberg ended").assertIsDisplayed()
        composeRule.onNodeWithText("can no longer use the sign-in of octocat on Codeberg", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Sign in to Codeberg again").performClick()

        assertThat(signIn).isTrue()
    }

    @Test
    fun an_account_signed_in_for_good_has_no_such_notice() {
        val account = Account("id", ForgeInstance.Codeberg, ForgeUser("octocat", "The Octocat", null))
        composeRule.setContent { YouScreen(SessionState.SignedIn(account), onSignIn = {}, onOpenSettings = {}) }

        composeRule.onNodeWithText("ended", substring = true).assertDoesNotExist()
    }

    /** On a screen tall enough to hold it all: a target cut by the edge of a shorter one measures as less than it is. */
    @Test
    @Config(qualifiers = "w411dp-h2400dp-xhdpi")
    fun every_target_is_48dp_at_least() {
        val gitHub = Account("github:github.com:octocat", ForgeInstance.GitHub, ForgeUser("octocat", "The Octocat", null))
        val codeberg = Account("forgejo:codeberg.org:alice", ForgeInstance.Codeberg, ForgeUser("alice", "Alice", null))
        composeRule.setContent { YouScreen(SessionState.SignedIn(gitHub, listOf(gitHub, codeberg)), onSignIn = {}, onOpenSettings = {}, onOpenProfile = {}) }
        composeRule.assertEveryTargetIsAtLeast48dp()
    }

    /** Regression: at twice the text size the forge's name beside the handle was cut to "GitH". */
    @Test
    // Text is measured for real only with native graphics; the default fakes a width of a pixel a letter.
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w360dp-h800dp-xhdpi", fontScale = 2.0f)
    fun at_large_text_the_handle_and_the_forge_are_both_whole() {
        val account = Account("id", ForgeInstance.GitHub, ForgeUser("octocat", "The Octocat", null))
        composeRule.setContent { YouScreen(SessionState.SignedIn(account), onSignIn = {}, onOpenSettings = {}) }

        composeRule.assertTextFits("@octocat")
        composeRule.assertTextFits("GitHub")
    }

    private val me = Account(Account.idFor(ForgeInstance.GitHub, "octocat"), ForgeInstance.GitHub, ForgeUser("octocat", "The Octocat", null))

    private fun repos(query: String = "", owner: String? = null, forges: List<ForgeInstance> = listOf(ForgeInstance.GitHub)): YouReposState {
        val repos = forges.flatMap { forge ->
            listOf("octocat/forgeline", "ACROSS-Lab/GamaWebPlayer", "ACROSS-Lab/EVEIL.Plus", "gama-platform/gama").map { full ->
                val (o, n) = full.split('/')
                fr.arthurbrugiere.forgeline.core.model.RepoSummary(fr.arthurbrugiere.forgeline.core.model.RepoId(o, n, forge), null, null, 0, 0, false, null)
            }
        }
        val all = fr.arthurbrugiere.forgeline.core.model.YourRepos.of(
            forges.map { ForgeUser("octocat", null, null) to it },
            repos,
            forges.flatMap { forge -> listOf("ACROSS-Lab", "gama-platform").map { fr.arthurbrugiere.forgeline.core.model.UserSummary(it, null, true, forge) } },
            1L,
        )
        val filter = all.owners.firstOrNull { it.login == owner }?.let { fr.arthurbrugiere.forgeline.core.model.RepoFilter.Of(it) } ?: fr.arthurbrugiere.forgeline.core.model.RepoFilter.All
        return YouReposState(all, query, filter, all.repos.filter { all.shows(it, filter) && it.matches(query) })
    }

    @Test
    fun every_reachable_repository_is_listed_and_opens() {
        val opened = mutableListOf<String>()
        composeRule.setContent { YouScreen(SessionState.SignedIn(me), onSignIn = {}, onOpenSettings = {}, repos = repos(), onOpenRepo = { opened += it.fullName }) }

        composeRule.onNodeWithText("Find among your 4 repositories").assertIsDisplayed()
        composeRule.onNodeWithText("EVEIL.Plus", substring = true).performClick()

        assertThat(opened).containsExactly("ACROSS-Lab/EVEIL.Plus")
    }

    @Test
    fun typing_searches_and_a_tap_on_an_owner_narrows_to_it_or_back_to_all() {
        val typed = mutableListOf<String>()
        val picked = mutableListOf<String>()
        composeRule.setContent {
            YouScreen(
                SessionState.SignedIn(me), onSignIn = {}, onOpenSettings = {}, repos = repos(),
                onSearch = { typed += it }, onPick = { picked += (it as? fr.arthurbrugiere.forgeline.core.model.RepoFilter.Of)?.owner?.login ?: it.toString() },
            )
        }

        composeRule.onNodeWithText("Find among your 4 repositories").performTextInput("gama")
        composeRule.onNodeWithText("ACROSS-Lab", useUnmergedTree = true).performClick()
        composeRule.onNodeWithText("All", useUnmergedTree = true).performClick()

        assertThat(typed).containsExactly("gama")
        assertThat(picked).containsExactly("ACROSS-Lab", "All").inOrder()
    }

    @Test
    fun a_long_press_on_an_owner_opens_its_page() {
        val opened = mutableListOf<String>()
        composeRule.setContent { YouScreen(SessionState.SignedIn(me), onSignIn = {}, onOpenSettings = {}, repos = repos(), onOpenOwner = { opened += it.login }) }

        composeRule.onNodeWithText("gama-platform", useUnmergedTree = true).performTouchInput { longClick() }

        assertThat(opened).containsExactly("gama-platform")
    }

    @Test
    fun a_narrowed_list_says_how_much_of_it_shows_and_where() {
        composeRule.setContent { YouScreen(SessionState.SignedIn(me), onSignIn = {}, onOpenSettings = {}, repos = repos(query = "gama", owner = "ACROSS-Lab")) }

        composeRule.onNodeWithText("1 of 2 in ACROSS-Lab").assertIsDisplayed()
        composeRule.onNodeWithText("ACROSS-Lab/GamaWebPlayer", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("gama-platform/gama", substring = true).assertDoesNotExist()
    }

    @Test
    fun nothing_matching_says_so() {
        composeRule.setContent { YouScreen(SessionState.SignedIn(me), onSignIn = {}, onOpenSettings = {}, repos = repos(query = "unity")) }

        composeRule.onNodeWithText("None of your repositories matches “unity”.").assertIsDisplayed()
    }

    @Test
    fun owners_and_rows_carry_their_forge_once_several_are_signed_in() {
        val codeberg = Account(Account.idFor(ForgeInstance.Codeberg, "octocat"), ForgeInstance.Codeberg, ForgeUser("octocat", "The Octocat", null))
        composeRule.setContent {
            YouScreen(SessionState.SignedIn(me, listOf(me, codeberg)), onSignIn = {}, onOpenSettings = {}, repos = repos(forges = listOf(ForgeInstance.GitHub, ForgeInstance.Codeberg)))
        }

        // The same owner on two forges: each logo badged with its forge, as rows are elsewhere.
        assertThat(composeRule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Codeberg"), useUnmergedTree = true).fetchSemanticsNodes()).isNotEmpty()
        assertThat(composeRule.onAllNodes(androidx.compose.ui.test.hasContentDescription("GitHub"), useUnmergedTree = true).fetchSemanticsNodes()).isNotEmpty()
    }

    @Test
    fun with_one_forge_nothing_is_badged_and_you_are_called_you() {
        composeRule.setContent { YouScreen(SessionState.SignedIn(me), onSignIn = {}, onOpenSettings = {}, repos = repos()) }

        composeRule.onNodeWithContentDescription("GitHub", useUnmergedTree = true).assertDoesNotExist()
        // The tab's title, and one's own logo among the owners.
        assertThat(composeRule.onAllNodesWithText("You", useUnmergedTree = true).fetchSemanticsNodes()).hasSize(2)
    }

    @Test
    fun settings_open_from_the_header() {
        var opened = false
        composeRule.setContent { YouScreen(SessionState.SignedIn(me), onSignIn = {}, onOpenSettings = { opened = true }, repos = repos()) }

        composeRule.onNodeWithContentDescription("Settings").performClick()

        assertThat(opened).isTrue()
        composeRule.assertEveryTargetIsAtLeast48dp()
    }

    @Test
    fun repositories_others_shared_have_a_logo_of_their_own_that_narrows_to_them() {
        val picked = mutableListOf<fr.arthurbrugiere.forgeline.core.model.RepoFilter>()
        val all = fr.arthurbrugiere.forgeline.core.model.YourRepos.of(
            listOf(ForgeUser("octocat", null, null) to ForgeInstance.GitHub),
            listOf("octocat/forgeline", "benoitgaudou/V_fusion_zada_app").map { full ->
                val (o, n) = full.split('/')
                fr.arthurbrugiere.forgeline.core.model.RepoSummary(fr.arthurbrugiere.forgeline.core.model.RepoId(o, n), null, null, 0, 0, false, null, ownerIsOrganization = false)
            },
            emptyList(),
            1L,
        )
        val shared = YouReposState(all, "", fr.arthurbrugiere.forgeline.core.model.RepoFilter.Shared, all.repos.filter { all.isShared(it) })
        composeRule.setContent { YouScreen(SessionState.SignedIn(me), onSignIn = {}, onOpenSettings = {}, repos = shared, onPick = { picked += it }) }

        composeRule.onNodeWithText("1 of 1 shared with you").assertIsDisplayed()
        composeRule.onNodeWithText("benoitgaudou/V_fusion_zada_app", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("octocat/forgeline", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Shared", useUnmergedTree = true).performClick()
        assertThat(picked).containsExactly(fr.arthurbrugiere.forgeline.core.model.RepoFilter.Shared)
    }

    @Test
    fun a_new_account_with_no_repository_is_told_what_will_be_here() {
        val none = fr.arthurbrugiere.forgeline.core.model.YourRepos.of(listOf(ForgeUser("octocat", null, null) to ForgeInstance.GitHub), emptyList(), emptyList(), 1L)
        composeRule.setContent { YouScreen(SessionState.SignedIn(me), onSignIn = {}, onOpenSettings = {}, repos = YouReposState(none)) }

        composeRule.onNodeWithText("Your repositories, in one list").assertIsDisplayed()
        // Not "can't reach any of its repositories", which was said of everyone at once.
        composeRule.onNodeWithText("You can't reach any of its repositories.").assertDoesNotExist()
    }
}
