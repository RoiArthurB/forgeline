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
}
