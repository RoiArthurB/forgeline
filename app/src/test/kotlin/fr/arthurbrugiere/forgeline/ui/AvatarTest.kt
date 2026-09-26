package fr.arthurbrugiere.forgeline.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertWithMessage
import fr.arthurbrugiere.forgeline.PHONE
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class AvatarTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun initialOverflows(size: Dp, login: String): Boolean {
        composeRule.setContent { Avatar(url = null, login = login, size = size) }
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(login.take(1).uppercase())
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().didOverflowHeight
    }

    @Test
    fun the_initial_fits_inside_a_small_avatar() {
        // Regression: the fallback initial used a fixed text size and overflowed 20dp avatars.
        assertWithMessage("initial overflows a 20dp avatar").that(initialOverflows(20.dp, "cryppadotta")).isFalse()
    }

    @Test
    fun the_initial_fits_inside_a_large_avatar() {
        assertWithMessage("initial overflows a 56dp avatar").that(initialOverflows(56.dp, "octocat")).isFalse()
    }
}
