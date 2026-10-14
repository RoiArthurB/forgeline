package fr.arthurbrugiere.forgeline.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.text.TextLayoutResult
import com.google.common.truth.Truth.assertWithMessage

/**
 * The text [text] is laid out whole: nothing of it runs past its width or its height, so no word is cut off. With
 * [oneLine], it also keeps to a single line. Read from the text's own layout, which a clipped word passes
 * `assertIsDisplayed` with.
 */
fun ComposeContentTestRule.assertTextFits(text: String, oneLine: Boolean = false) {
    val node = onNode(hasText(text), useUnmergedTree = true).fetchSemanticsNode()
    val layouts = mutableListOf<TextLayoutResult>()
    node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
    val layout = layouts.single()
    // Not didOverflowWidth: it compares with the width the text was offered, which a short word never fills.
    val lines = 0 until layout.lineCount
    assertWithMessage("\"$text\" runs past its width").that(lines.filter { layout.getLineRight(it) > layout.size.width + 0.5f }).isEmpty()
    assertWithMessage("\"$text\" is cut short").that(lines.filter { layout.isLineEllipsized(it) }).isEmpty()
    assertWithMessage("\"$text\" has lines hidden under its last").that(layout.didOverflowHeight).isFalse()
    if (oneLine) assertWithMessage("\"$text\" keeps to one line").that(layout.lineCount).isEqualTo(1)
}
