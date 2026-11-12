package fr.arthurbrugiere.forgeline.user

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.model.Contributions
import fr.arthurbrugiere.forgeline.core.ui.theme.ForgelineTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContributionGraphTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun graphHeight(): Float {
        composeRule.setContent {
            ForgelineTheme { ContributionGraph(Contributions(mapOf(LocalDate.parse("2026-11-02") to 3)), today = LocalDate.parse("2026-11-12")) }
        }
        val bounds = composeRule.onNodeWithTag(CONTRIBUTION_GRAPH_TAG, useUnmergedTree = true).getBoundsInRoot()
        return (bounds.bottom - bounds.top).value
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xhdpi", fontScale = 2.0f)
    fun at_large_text_the_month_names_keep_above_the_squares() {
        // Regression: the names' row was 18dp whatever the text size, and at 2x they drew over the first squares.
        val squares = 11 * 7 + 3 * 6

        assertThat(graphHeight()).isGreaterThan(squares + 18f)
    }

    @Test
    fun a_month_named_at_the_right_edge_is_pulled_back_whole() {
        // Regression: a month begun in the last week was cut at the edge, "Nov" reading "N".
        assertThat(monthLabelX(at = 728f, width = 30f, end = 739f, previousEnd = 650f, gap = 3f)).isEqualTo(709f)
        // Far from the edge, it starts over its week.
        assertThat(monthLabelX(at = 100f, width = 30f, end = 739f, previousEnd = 60f, gap = 3f)).isEqualTo(100f)
    }

    @Test
    fun a_month_whose_name_would_run_into_the_last_one_goes_unnamed() {
        assertThat(monthLabelX(at = 728f, width = 30f, end = 739f, previousEnd = 720f, gap = 3f)).isNull()
    }
}
