package fr.arthurbrugiere.forgeline.wiki

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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

    private fun setContent(state: WikiUiState) {
        composeRule.setContent {
            ForgelineTheme {
                WikiScreen(
                    state = state,
                    onBack = { events += "back" }, onRefresh = { events += "refresh" }, onOpenRepo = { events += "repo:${it.fullName}" },
                    onOpenPage = { events += "page:$it" }, onOpenInBrowser = { events += "browser:$it" }, onLinkClick = { events += "link:$it" },
                    onErrorShown = { events += "error-shown" },
                )
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
