package fr.arthurbrugiere.forgeline.wiki

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.WikiPage
import fr.arthurbrugiere.forgeline.core.model.WikiPageSummary
import fr.arthurbrugiere.forgeline.core.ui.theme.ForgelineTheme
import fr.arthurbrugiere.forgeline.ui.assertEveryTargetIsAtLeast48dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class WikiScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()
    private val repo = RepoId("octo", "repo")
    private val home = WikiPage("Home", "Home", "Welcome to the wiki.", "https://github.com/octo/repo/wiki/Home")
    private val pages = listOf(WikiPageSummary("Home", "Home"), WikiPageSummary("Install", "Install"), WikiPageSummary("FAQ", "FAQ"))
    private val loaded = WikiUiState(repo, "Home", home, pages, isLoading = false)

    private fun setContent(state: WikiUiState, roomy: Boolean = false) {
        composeRule.setContent {
            ForgelineTheme {
                androidx.compose.runtime.CompositionLocalProvider(fr.arthurbrugiere.forgeline.ui.LocalUserSettings provides fr.arthurbrugiere.forgeline.core.model.UserSettings(roomyTargets = roomy)) {
                    WikiScreen(
                        state = state,
                        onBack = { events += "back" }, onRefresh = { events += "refresh" }, onOpenRepo = { events += "repo:${it.fullName}" },
                        onOpenPage = { events += "page:$it" }, onOpenInBrowser = { events += "browser:$it" }, onLinkClick = { events += "link:$it" },
                        onErrorShown = { events += "error-shown" },
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun reach(text: String) = composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))

    private fun shown(text: String) = composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun a_page_is_headed_by_its_wiki_and_its_title_and_reads_its_words() {
        setContent(loaded)

        composeRule.onNodeWithText("Wiki of octo/repo").assertIsDisplayed()
        composeRule.waitUntil(5_000) { shown("Welcome to the wiki.") }
        composeRule.onNodeWithText("Wiki of octo/repo").performClick()
        assertThat(events).containsExactly("repo:octo/repo")
    }

    @Test
    fun its_repository_line_keeps_its_own_height_unless_links_are_asked_to_be_roomy() {
        setContent(loaded)
        composeRule.onNodeWithText("Wiki of octo/repo").getBoundsInRoot().let { com.google.common.truth.Truth.assertThat((it.bottom - it.top).value).isAtMost(30f) }
    }

    @Test
    fun with_roomy_links_its_repository_line_is_a_finger_tall() {
        setContent(loaded, roomy = true)
        composeRule.onNodeWithText("Wiki of octo/repo").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun its_repository_and_its_pages_are_said_to_open() {
        // Regression: the wiki's repository was a 19dp line TalkBack heard as plain text, and so were the other pages.
        setContent(loaded)

        composeRule.onNodeWithText("Wiki of octo/repo").assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button))
        reach("All pages (3)")
        composeRule.onNodeWithText("All pages (3)").performClick()
        reach("FAQ")
        composeRule.onNodeWithText("FAQ").assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button))
    }

    @Test
    fun the_other_pages_wait_folded_and_open_from_the_list() {
        setContent(loaded)

        reach("All pages (3)")
        assertThat(shown("FAQ")).isFalse()
        composeRule.onNodeWithText("All pages (3)").performClick()
        reach("FAQ")
        composeRule.onNodeWithText("FAQ").performClick()
        // The page shown is not a link to itself.
        composeRule.onAllNodesWithText("Home")[1].performClick()

        assertThat(events).containsExactly("page:FAQ")
        composeRule.assertEveryTargetIsAtLeast48dp()
    }

    @Test
    fun a_wiki_whose_forge_lists_no_pages_offers_no_list() {
        setContent(loaded.copy(pages = null))

        composeRule.waitUntil(5_000) { shown("Welcome to the wiki.") }
        assertThat(shown("All pages")).isFalse()
    }

    @Test
    fun a_page_that_is_not_there_says_so_and_offers_the_forge() {
        setContent(WikiUiState(repo, "Written-in-AsciiDoc", isLoading = false, error = ForgeError.Http(404, "Not Found")))

        composeRule.onNodeWithText("Written in AsciiDoc").assertIsDisplayed()
        composeRule.onNodeWithText("No such page").assertIsDisplayed()
        composeRule.onNodeWithText("Open on GitHub").performClick()

        assertThat(events).containsExactly("browser:https://github.com/octo/repo/wiki/Written-in-AsciiDoc")
    }

    @Test
    fun a_page_that_couldn_t_be_read_offline_can_be_asked_for_again() {
        setContent(WikiUiState(repo, "Home", isLoading = false, error = ForgeError.Network))

        composeRule.onNodeWithText("Couldn’t load this page").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()

        assertThat(events).containsExactly("refresh")
    }
}
