package fr.arthurbrugiere.forgeline.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.ui.theme.ForgelineTheme
import fr.arthurbrugiere.forgeline.navigation.PictureRoute
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class PictureScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()

    private fun setContent(route: PictureRoute) = composeRule.setContent {
        ForgelineTheme { PictureScreen(route, onBack = { events += "back" }, onOpenInBrowser = { events += "browser:$it" }) }
    }

    @Test
    fun a_picture_is_headed_by_what_it_is_said_to_show() {
        setContent(PictureRoute("https://example.com/shots/board.png?raw=true", "The board, with three cards"))

        composeRule.onNodeWithText("The board, with three cards").assertIsDisplayed()
    }

    @Test
    fun a_picture_said_to_show_nothing_is_headed_by_its_file_name() {
        setContent(PictureRoute("https://example.com/shots/board.png?raw=true#top", ""))

        composeRule.onNodeWithText("board.png").assertIsDisplayed()
    }

    @Test
    fun it_goes_back_and_opens_in_the_browser() {
        setContent(PictureRoute("https://example.com/a.png"))

        composeRule.onNode(hasContentDescription("Open in the browser")).performClick()
        composeRule.onNode(hasContentDescription("Navigate up")).performClick()

        assertThat(events).containsExactly("browser:https://example.com/a.png", "back").inOrder()
    }

    @Test
    fun every_target_is_large_enough_to_tap() {
        setContent(PictureRoute("https://example.com/a.png"))

        composeRule.assertEveryTargetIsAtLeast48dp()
    }
}
