package fr.arthurbrugiere.forgeline.core.markdown

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
            ForgelineTheme(dynamicColor = false) { ForgelineMarkdown(state, onLinkClick = { clicked += it }) }
        }

        composeRule.onNodeWithText("Forgeline").assertIsDisplayed()
        composeRule.onNodeWithText("val x = 1", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("guide", substring = true).performClick()

        assertThat(clicked).containsExactly("https://example.com/guide")
    }
}
