package fr.arthurbrugiere.forgeline.core.markdown

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.ui.theme.ForgelineTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ForgelineMarkdownTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun renders_markdown_and_routes_link_taps_to_the_app() {
        val clicked = mutableListOf<String>()
        val state = parseForgeMarkdown("# Forgeline\n\nRead the [guide](https://example.com/guide).\n\n```kotlin\nval x = 1\n```")
        composeRule.setContent {
            ForgelineTheme() { ForgelineMarkdown(state, onLinkClick = { clicked += it }) }
        }

        composeRule.onNodeWithText("Forgeline").assertIsDisplayed()
        composeRule.onNodeWithText("val x = 1", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("guide", substring = true).performClick()

        assertThat(clicked).containsExactly("https://example.com/guide")
    }

    @Test
    fun headings_use_material_headline_sizes_not_display_sizes() {
        // Regression: the renderer's default H1 used the display scale, dwarfing the whole screen.
        var headlineMedium = 0f
        composeRule.setContent {
            ForgelineTheme() {
                headlineMedium = androidx.compose.material3.MaterialTheme.typography.headlineMedium.fontSize.value
                ForgelineMarkdown(parseForgeMarkdown("# Big title"), onLinkClick = {})
            }
        }
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        composeRule.onNodeWithText("Big title")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }

        assertThat(layouts.single().layoutInput.style.fontSize.value).isAtMost(headlineMedium)
    }

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Test
    fun pictures_that_do_not_load_leave_no_tall_gap() {
        // Regression: a picture of unknown size held a 200dp square, so a README's row of badges that failed to load
        // (GAMA's) left a screen and more of nothing between the title and the text.
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val failEverything = coil3.intercept.Interceptor { chain ->
            coil3.request.ErrorResult(null, chain.request, IllegalStateException("no decoder"))
        }
        coil3.SingletonImageLoader.setUnsafe(coil3.ImageLoader.Builder(context).components { add(failEverything) }.build())
        try {
            val badges = (1..7).joinToString("\n") { "[![badge $it](https://badges.example/$it.svg)](https://example.com/$it)" }
            composeRule.setContent {
                ForgelineTheme() { ForgelineMarkdown(parseForgeMarkdown("# Title\n$badges\n\nBody"), onLinkClick = {}) }
            }
            composeRule.waitForIdle()

            val title = composeRule.onNodeWithText("Title").fetchSemanticsNode().boundsInRoot
            val body = composeRule.onNodeWithText("Body").fetchSemanticsNode().boundsInRoot
            val gap = with(composeRule.density) { (body.top - title.bottom).toDp() }
            assertThat(gap.value).isLessThan(120f)
        } finally {
            coil3.SingletonImageLoader.reset()
        }
    }

    private fun withPictures(text: String, opened: MutableList<String>?) = composeRule.setContent {
        ForgelineTheme() {
            if (opened == null) {
                ForgelineMarkdown(parseForgeMarkdown(text), onLinkClick = {})
            } else {
                androidx.compose.runtime.CompositionLocalProvider(LocalOpenPicture provides { url, description -> opened += "$url|$description" }) {
                    ForgelineMarkdown(parseForgeMarkdown(text), onLinkClick = { opened += "link:$it" })
                }
            }
        }
    }

    private fun pictures() = composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag(MARKDOWN_PICTURE_TAG), useUnmergedTree = true)

    @Test
    fun a_picture_opens_larger_when_tapped() {
        // Regression: a screenshot in a README could only be squinted at.
        val opened = mutableListOf<String>()
        withPictures("Before\n\n![The board](https://example.com/board.png)\n\nAfter", opened)
        composeRule.waitForIdle()

        pictures()[0].performClick()

        // What it is said to show comes with it once it has loaded and stands on its own; not before.
        assertThat(opened.single()).startsWith("https://example.com/board.png|")
    }

    @Test
    fun a_picture_set_in_a_line_of_text_opens_too() {
        val opened = mutableListOf<String>()
        withPictures("See ![icon](https://example.com/icon.png) here", opened)
        composeRule.waitForIdle()

        pictures()[0].performClick()

        // A picture in a line of text comes without what it is said to show.
        assertThat(opened).containsExactly("https://example.com/icon.png|null")
    }

    @Test
    fun a_picture_that_is_a_link_keeps_its_link() {
        // A badge leads where its author sent it: it is not a picture to look at.
        val opened = mutableListOf<String>()
        withPictures("[![build](https://example.com/badge.svg)](https://example.com/ci)", opened)
        composeRule.waitForIdle()

        assertThat(pictures().fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun where_nothing_can_show_a_picture_larger_it_is_only_looked_at() {
        withPictures("![The board](https://example.com/board.png)", opened = null)
        composeRule.waitForIdle()

        assertThat(pictures().fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun a_line_holding_a_badge_that_is_a_link_leaves_its_other_pictures_alone() {
        // Which picture of a line was tapped can't be told: none opens, so the badge's link is never taken from it.
        val opened = mutableListOf<String>()
        withPictures("[![build](https://example.com/badge.svg)](https://example.com/ci) ![icon](https://example.com/icon.png)", opened)
        composeRule.waitForIdle()

        assertThat(pictures().fetchSemanticsNodes()).isEmpty()
    }
}
