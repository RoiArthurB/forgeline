package fr.arthurbrugiere.forgeline.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftIconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftCheckRow
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftChoiceSheet
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftIndication
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSnackbarHost
import fr.arthurbrugiere.forgeline.core.ui.theme.ForgelineTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp

/** The shared Soft pieces every screen builds from, checked once here rather than on each screen. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class SoftPiecesTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    @Test
    fun a_press_anywhere_in_the_world_answers_with_the_soft_veil_not_a_ripple() {
        var indication: Any? = null
        var track: Any? = null
        composeRule.setContent {
            ForgelineTheme {
                indication = LocalIndication.current
                track = Soft.colors.track
            }
        }

        // Every clickable that doesn't say otherwise takes it: rows, pills and buttons alike.
        assertThat(indication).isEqualTo(SoftIndication(track as androidx.compose.ui.graphics.Color))
    }

    @Test
    fun a_check_row_is_a_checkbox_that_toggles_from_anywhere_on_it() {
        composeRule.setContent {
            ForgelineTheme {
                var on by remember { mutableStateOf(false) }
                SoftCheckRow("Stars", on, { on = it }, summary = "What they star")
            }
        }

        composeRule.onNode(hasText("Stars") and hasRole(Role.Checkbox)).assertIsOff()
        composeRule.onNodeWithText("What they star").performClick()
        composeRule.onNode(hasText("Stars") and hasRole(Role.Checkbox)).assertIsOn()
        composeRule.assertEveryTargetIsAtLeast48dp()
    }

    @Test
    fun a_choice_sheet_says_which_is_picked_and_closes_on_the_one_tapped() {
        val picked = mutableListOf<Int>()
        var open by mutableStateOf(true)
        composeRule.setContent {
            ForgelineTheme {
                if (open) SoftChoiceSheet("Theme", listOf("System", "Light", "Dark"), 0, onSelect = { picked += it }, onDismiss = { open = false })
            }
        }

        composeRule.onNode(hasText("Theme") and isHeading()).assertIsDisplayed()
        composeRule.onNode(hasText("System") and hasRole(Role.RadioButton)).assertIsSelected()
        composeRule.onNodeWithText("Dark").performClick()

        assertThat(picked).containsExactly(2)
        composeRule.waitUntil(5_000) { !open }
    }

    @Test
    fun the_snackbar_says_its_word_with_its_action() {
        composeRule.setContent {
            ForgelineTheme {
                val state = remember { SnackbarHostState() }
                LaunchedEffect(Unit) { state.showSnackbar("Marked as done", actionLabel = "Undo") }
                SoftSnackbarHost(state)
            }
        }

        composeRule.onNodeWithText("Marked as done").assertIsDisplayed()
        composeRule.onNodeWithText("Undo").assertIsDisplayed()
    }

    /** Regression: a branch's name wrapped after its first "/", and the rest of it was cut off out of sight. */
    @Test
    // Text is measured for real only with native graphics; the default fakes a width of a pixel a letter.
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun a_tag_too_long_for_its_room_keeps_to_one_line_and_says_it_is_cut() {
        composeRule.setContent {
            ForgelineTheme {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(120.dp)) {
                    fr.arthurbrugiere.forgeline.core.ui.soft.SoftTag("fix/blocked-inbox-reason-and-action")
                }
            }
        }

        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        composeRule.onNodeWithText("fix/blocked-inbox-reason-and-action").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertThat(layouts.single().lineCount).isEqualTo(1)
        assertThat(layouts.single().isLineEllipsized(0)).isTrue()
    }

    /** The colour of the icon button [description] near its edge, off the glyph: what a press washes over. */
    /** The largest difference between two colours, channel by channel. */
    private fun channelsApart(a: Int, b: Int): Int =
        listOf(16, 8, 0).maxOf { shift -> kotlin.math.abs((a shr shift and 0xFF) - (b shr shift and 0xFF)) }

    private fun edgeOf(description: String): Int {
        val image = composeRule.onNodeWithContentDescription(description).captureToImage().asAndroidBitmap()
        return image.getPixel(image.width / 2, image.height / 2 - (image.height * 0.38f).toInt())
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun an_icon_button_is_pressed_with_the_soft_veil_and_cannot_be_when_off() {
        val presses = mutableListOf<String>()
        var ground = 0
        var veiled = 0
        composeRule.setContent {
            ForgelineTheme {
                val colors = Soft.colors
                ground = colors.ground.toArgb()
                veiled = colors.track.compositeOver(colors.ground).toArgb()
                androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.background(colors.ground)) {
                    SoftIconButton(onClick = { presses += "search" }) { Icon(Icons.Outlined.Search, contentDescription = "Search") }
                    SoftIconButton(onClick = { presses += "off" }, enabled = false) { Icon(Icons.Outlined.Close, contentDescription = "Off") }
                }
            }
        }

        composeRule.onNodeWithContentDescription("Search").assert(hasRole(Role.Button))
        composeRule.assertEveryTargetIsAtLeast48dp()
        assertThat(edgeOf("Search")).isEqualTo(ground)
        composeRule.onNodeWithContentDescription("Search").performTouchInput { down(center) }
        composeRule.waitForIdle()
        // The veil, as on a row or a pill: not a ripple's spreading circle. Blending rounds each channel its own way.
        assertThat(channelsApart(edgeOf("Search"), veiled)).isAtMost(2)
        composeRule.onNodeWithContentDescription("Search").performTouchInput { up() }

        composeRule.onNodeWithContentDescription("Off").assertIsNotEnabled().performClick()
        assertThat(presses).containsExactly("search")
    }
}
