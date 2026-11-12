package fr.arthurbrugiere.forgeline.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTag
import fr.arthurbrugiere.forgeline.core.ui.theme.ForgelineTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LabelChipTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun a_label_is_the_same_size_as_the_tag_beside_it() {
        // Regression: in an issue's header, labels were taller and rounder than its type's tag.
        composeRule.setContent {
            ForgelineTheme {
                Row {
                    SoftTag("Bug", Modifier.testTag("type"))
                    LabelChip(Label("Bug", "d73a4a"), Modifier.testTag("label"))
                }
            }
        }

        val type = composeRule.onNodeWithTag("type", useUnmergedTree = true).getBoundsInRoot()
        val label = composeRule.onNodeWithTag("label", useUnmergedTree = true).getBoundsInRoot()
        assertThat(label.bottom - label.top).isEqualTo(type.bottom - type.top)
        assertThat(label.right - label.left).isEqualTo(type.right - type.left)
    }
}
