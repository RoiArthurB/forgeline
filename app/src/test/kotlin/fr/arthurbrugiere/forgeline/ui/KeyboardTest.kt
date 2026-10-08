package fr.arthurbrugiere.forgeline.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A place to write at the end of a list, as the comment box is, and a keyboard the test raises by hand. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class KeyboardTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var keyboard by mutableIntStateOf(0)
    private var suggestions by mutableStateOf(false)

    private fun setContent(kept: Boolean = true) = composeRule.setContent {
        val keyboardDp = with(LocalDensity.current) { keyboard.toDp() }
        // The list gives the keyboard its room, as the conversation's does.
        LazyColumn(Modifier.fillMaxSize().padding(bottom = keyboardDp)) {
            item { Box(Modifier.fillMaxWidth().height(1200.dp)) }
            item {
                Column((if (kept) Modifier.staysAboveKeyboard(keyboard) else Modifier).fillMaxWidth()) {
                    BasicTextField("", {}, Modifier.fillMaxWidth().height(60.dp))
                    if (suggestions) Box(Modifier.fillMaxWidth().height(150.dp).testTag("suggestions"))
                    Box(Modifier.fillMaxWidth().height(56.dp).testTag("send"))
                }
            }
            item { Box(Modifier.fillMaxWidth().height(80.dp)) }
        }
    }

    /** Where a node ends, whether or not that is in sight: its bounds are cut to what shows, its place and size are not. */
    private fun bottomOf(tag: String) = composeRule.onNodeWithTag(tag).fetchSemanticsNode().let { it.positionInRoot.y + it.size.height }

    private fun aboveKeyboard() = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.bottom - keyboard

    private fun writeAtTheEnd() {
        composeRule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToIndexCompat(2)
        composeRule.onNode(hasSetTextAction()).performClick()
        composeRule.waitForIdle()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performScrollToIndexCompat(index: Int) =
        performScrollToIndex(index)

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performScrollToIndex(index: Int): androidx.compose.ui.test.SemanticsNodeInteraction {
        val scroll = fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.ScrollToIndex]
        composeRule.runOnUiThread { scroll.action!!.invoke(index) }
        composeRule.waitForIdle()
        return this
    }

    @Test
    fun the_field_and_what_stands_under_it_stay_in_sight_when_the_keyboard_comes_up() {
        // Regression: the comment box stayed under the keyboard that came up for it.
        setContent()
        writeAtTheEnd()

        keyboard = 900
        composeRule.waitForIdle()

        composeRule.onNode(hasSetTextAction()).assertIsDisplayed()
        composeRule.onNodeWithTag("send").assertIsDisplayed()
        assertThat(bottomOf("send")).isAtMost(aboveKeyboard() + 1f)
    }

    @Test
    fun what_comes_under_the_field_while_the_keyboard_is_up_is_brought_in_sight_too() {
        setContent()
        writeAtTheEnd()
        keyboard = 900
        composeRule.waitForIdle()

        suggestions = true
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("suggestions").assertIsDisplayed()
        assertThat(bottomOf("send")).isAtMost(aboveKeyboard() + 1f)
    }

    @Test
    fun without_the_keyboard_nothing_is_moved() {
        setContent()
        composeRule.waitForIdle()
        val before = composeRule.onRoot().fetchSemanticsNode().boundsInRoot

        suggestions = true
        composeRule.waitForIdle()

        // Not focused, no keyboard: the list stays at its top, the field still far below.
        assertThat(composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().all { it.boundsInRoot.top >= before.bottom - 1f }).isTrue()
    }

    @Test
    fun left_to_itself_the_action_under_the_field_ends_up_under_the_keyboard() {
        // What the fix is for: without it, only the field's own cursor line is kept in sight, if that.
        setContent(kept = false)
        writeAtTheEnd()

        keyboard = 900
        composeRule.waitForIdle()

        assertThat(bottomOf("send")).isGreaterThan(aboveKeyboard() + 1f)
    }
}
