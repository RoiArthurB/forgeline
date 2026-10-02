package fr.arthurbrugiere.forgeline.core.markdown

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.ui.theme.ForgelineTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MarkdownCacheTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ReadmeContext("https://raw.example/main/", "https://blob.example/main/")

    @Before
    fun emptyCache() = MarkdownAstCache.clear()

    private fun waitFor(text: String) =
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun key(markdown: String, context: ReadmeContext = this.context, darkTheme: Boolean = false) = MarkdownCacheKey(markdown, context, darkTheme)

    @Test
    fun going_back_to_a_document_already_parsed_shows_that_document() {
        // Regression: a repository switched from one branch to another and back kept showing the other branch's
        // README. What was already parsed was only handed over when the screen first appeared, never on a later change.
        var markdown by mutableStateOf("README of main")
        composeRule.setContent {
            ForgelineTheme {
                val parsed = rememberReadmeState(markdown, context, darkTheme = false)
                if (parsed == null) Text("parsing") else ForgelineMarkdown(parsed, onLinkClick = {})
            }
        }
        waitFor("README of main")
        markdown = "README of dev"
        waitFor("README of dev")

        markdown = "README of main"

        waitFor("README of main")
        composeRule.onNodeWithText("README of dev", substring = true).assertDoesNotExist()
    }

    @Test
    fun a_document_parsed_once_shows_at_once_the_next_time() {
        var shown by mutableStateOf(true)
        var sawPlaceholder = false
        composeRule.setContent {
            ForgelineTheme {
                if (shown) {
                    val parsed = rememberReadmeState("A comment that scrolls away", context, darkTheme = false)
                    if (parsed == null) sawPlaceholder = true else ForgelineMarkdown(parsed, onLinkClick = {})
                }
            }
        }
        waitFor("A comment that scrolls away")
        assertThat(sawPlaceholder).isTrue()

        // Scrolled out of the list, then back in.
        shown = false
        composeRule.waitForIdle()
        sawPlaceholder = false
        shown = true
        composeRule.waitForIdle()

        composeRule.onNodeWithText("A comment that scrolls away", substring = true).assertExists()
        assertThat(sawPlaceholder).isFalse()
    }

    @Test
    fun the_same_text_read_from_another_place_or_theme_is_parsed_apart() {
        val parsed = parseForgeMarkdown("See [the guide](docs/GUIDE.md)")
        MarkdownAstCache.put(key("See [the guide](docs/GUIDE.md)"), parsed)

        // Relative links resolve against where the text lives, and pictures follow the theme.
        assertThat(MarkdownAstCache.get(key("See [the guide](docs/GUIDE.md)", context.copy(directory = "docs/")))).isNull()
        assertThat(MarkdownAstCache.get(key("See [the guide](docs/GUIDE.md)", context.copy(blobBaseUrl = "https://blob.example/v2/")))).isNull()
        assertThat(MarkdownAstCache.get(key("See [the guide](docs/GUIDE.md)", darkTheme = true))).isNull()
        assertThat(MarkdownAstCache.get(key("See [the guide](docs/GUIDE.md)"))).isSameInstanceAs(parsed)
    }

    @Test
    fun the_documents_read_longest_ago_give_way_once_there_are_too_many() {
        val parsed = parseForgeMarkdown("x")
        repeat(MarkdownAstCache.MAX_ENTRIES) { MarkdownAstCache.put(key("comment $it"), parsed) }
        // Read again: no longer the one read longest ago.
        MarkdownAstCache.get(key("comment 0"))

        MarkdownAstCache.put(key("one more"), parsed)

        assertThat(MarkdownAstCache.get(key("comment 0"))).isNotNull()
        assertThat(MarkdownAstCache.get(key("comment 1"))).isNull()
        assertThat(MarkdownAstCache.get(key("one more"))).isNotNull()
    }

    @Test
    fun long_documents_give_way_by_how_much_text_is_kept_not_only_by_how_many() {
        val parsed = parseForgeMarkdown("x")
        val third = "r".repeat(MarkdownAstCache.MAX_CHARS / 3)
        MarkdownAstCache.put(key("1$third"), parsed)
        MarkdownAstCache.put(key("2$third"), parsed)
        MarkdownAstCache.put(key("3$third"), parsed)

        // Three of them are over the budget together: the first read gives way.
        assertThat(MarkdownAstCache.get(key("1$third"))).isNull()
        assertThat(MarkdownAstCache.get(key("2$third"))).isNotNull()
        assertThat(MarkdownAstCache.get(key("3$third"))).isNotNull()
    }

    @Test
    fun a_document_larger_than_the_whole_budget_is_not_kept_and_pushes_nothing_out() {
        val parsed = parseForgeMarkdown("x")
        MarkdownAstCache.put(key("a comment"), parsed)

        MarkdownAstCache.put(key("r".repeat(MarkdownAstCache.MAX_CHARS + 1)), parsed)

        assertThat(MarkdownAstCache.get(key("r".repeat(MarkdownAstCache.MAX_CHARS + 1)))).isNull()
        assertThat(MarkdownAstCache.get(key("a comment"))).isNotNull()
    }
}
