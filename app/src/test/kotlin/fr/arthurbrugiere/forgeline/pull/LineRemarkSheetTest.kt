package fr.arthurbrugiere.forgeline.pull

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.model.DiffLine
import fr.arthurbrugiere.forgeline.core.model.DiffLineKind
import fr.arthurbrugiere.forgeline.core.ui.theme.ForgelineTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class LineRemarkSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val added = mutableListOf<String>()

    private val starts = mutableListOf<DiffLine?>()

    private fun setContent(line: DiffLine?, above: List<DiffLine> = emptyList()) = composeRule.setContent {
        ForgelineTheme {
            LineRemarkSheet(
                LineTarget("src/Upload.kt", line, above),
                onAdd = { body, start ->
                    added += body
                    starts += start
                },
                onDismiss = {},
            )
        }
    }

    @Test
    fun a_suggested_change_starts_from_the_line_as_it_is() {
        setContent(DiffLine(DiffLineKind.ADDED, "    retry { send(file) }", oldNumber = null, newNumber = 3))

        composeRule.onNode(hasSetTextAction()).performTextInput("Three times:")
        composeRule.onNodeWithText("Suggest a change").performClick()
        composeRule.onNodeWithText("Add to the review").performClick()

        // It goes after what was written, on a line of its own; the line itself is there to be rewritten.
        assertThat(added).containsExactly("Three times:\n```suggestion\n    retry { send(file) }\n```\n")
    }

    @Test
    fun a_line_the_change_takes_away_can_t_be_suggested_over() {
        setContent(DiffLine(DiffLineKind.REMOVED, "    send(file)", oldNumber = 2, newNumber = null))

        composeRule.onNodeWithText("Upload.kt, line 2").assertIsDisplayed()
        composeRule.onNodeWithText("Suggest a change").assertDoesNotExist()
    }

    private val first = DiffLine(DiffLineKind.CONTEXT, "fun upload() {", oldNumber = 1, newNumber = 1)
    private val second = DiffLine(DiffLineKind.ADDED, "    retry {", oldNumber = null, newNumber = 2)
    private val third = DiffLine(DiffLineKind.ADDED, "        send(file)", oldNumber = null, newNumber = 3)

    @Test
    fun a_remark_reaches_up_over_the_lines_above_and_its_suggestion_holds_them_all() {
        // The lines above, nearest first.
        setContent(third, above = listOf(second, first))

        composeRule.onNodeWithText("One more line above").performClick()
        composeRule.onNodeWithText("One more line above").performClick()
        composeRule.onNodeWithText("Lines 1 to 3").assertIsDisplayed()
        composeRule.onNodeWithText("One line fewer").performClick()
        composeRule.onNodeWithText("Lines 2 to 3").assertIsDisplayed()
        composeRule.onNodeWithText("Suggest a change").performClick()
        composeRule.onNodeWithText("Add to the review").performClick()

        assertThat(added).containsExactly("```suggestion\n    retry {\n        send(file)\n```\n")
        assertThat(starts).containsExactly(second)
    }

    @Test
    fun a_remark_on_the_whole_file_says_so_and_suggests_nothing() {
        setContent(null)

        composeRule.onNodeWithText("Upload.kt, the whole file").assertIsDisplayed()
        composeRule.onNodeWithText("Suggest a change").assertDoesNotExist()
        composeRule.onNodeWithText("One more line above").assertDoesNotExist()
    }
}
