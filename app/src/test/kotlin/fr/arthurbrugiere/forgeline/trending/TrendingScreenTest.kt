package fr.arthurbrugiere.forgeline.trending

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import fr.arthurbrugiere.forgeline.core.testing.trendingRepo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class TrendingScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()
    private val paperclip = trendingRepo("paperclipai/paperclip", stars = 85_955, periodStars = 2_109)

    private fun setContent(state: TrendingUiState) {
        composeRule.setContent {
            TrendingScreen(
                state = state,
                onPeriodChange = { events += "period:$it" },
                onRefresh = { events += "refresh" },
                onToggleStar = { events += "star:${it.fullName}" },
                onOpenRepo = { events += "open:${it.fullName}" },
                onErrorShown = { events += "errorShown" },
                onStarFailureShown = { events += "starFailureShown" },
                nowMillis = 10 * 60_000L,
            )
        }
    }

    @Test
    fun shows_each_repo_with_its_stats() {
        setContent(TrendingUiState(items = listOf(TrendingItem(paperclip, starred = null)), updatedAtMillis = 5 * 60_000L))

        composeRule.onNodeWithText("paperclipai / paperclip").assertIsDisplayed()
        composeRule.onNodeWithText("Description of paperclipai/paperclip").assertIsDisplayed()
        composeRule.onNodeWithText("85.9k").assertIsDisplayed()
        composeRule.onNodeWithText("+2,109 today").assertIsDisplayed()
        composeRule.onNodeWithText("Updated 5 minutes ago").assertIsDisplayed()
    }

    @Test
    fun the_gain_label_follows_the_period() {
        setContent(TrendingUiState(period = TrendingPeriod.WEEKLY, items = listOf(TrendingItem(paperclip, null))))

        composeRule.onNodeWithText("+2,109 this week").assertIsDisplayed()
    }

    @Test
    fun selecting_a_period_reports_it() {
        setContent(TrendingUiState(items = listOf(TrendingItem(paperclip, null))))

        composeRule.onNodeWithText("This month").performClick()

        assertThat(events).containsExactly("period:MONTHLY")
    }

    @Test
    fun the_star_button_toggles_and_describes_its_state() {
        setContent(TrendingUiState(items = listOf(TrendingItem(paperclip, starred = true))))

        composeRule.onNode(hasContentDescription("Unstar paperclipai/paperclip")).performClick()

        assertThat(events).containsExactly("star:paperclipai/paperclip")
    }

    @Test
    fun tapping_a_card_opens_the_repo() {
        setContent(TrendingUiState(items = listOf(TrendingItem(paperclip, null))))

        composeRule.onNodeWithText("paperclipai / paperclip").performClick()

        assertThat(events).containsExactly("open:paperclipai/paperclip")
    }

    @Test
    fun without_a_cached_list_an_error_offers_a_retry() {
        setContent(TrendingUiState(error = ForgeError.Network))

        composeRule.onNodeWithText("Couldn't load Trending").assertIsDisplayed()
        composeRule.onNodeWithText("Check your connection and try again.").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()

        assertThat(events).containsExactly("refresh")
    }

    @Test
    fun without_a_cached_list_and_no_error_it_is_loading() {
        setContent(TrendingUiState())

        composeRule.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test
    fun a_failed_refresh_over_a_cached_list_shows_a_snackbar() {
        setContent(TrendingUiState(items = listOf(TrendingItem(paperclip, null)), error = ForgeError.Network))

        composeRule.onNodeWithText("Couldn't refresh. Showing the last saved list.").assertIsDisplayed()
        composeRule.onNodeWithText("paperclipai / paperclip").assertIsDisplayed()
    }

    @Test
    fun a_failed_star_shows_a_snackbar() {
        setContent(TrendingUiState(items = listOf(TrendingItem(paperclip, false)), starFailed = true))

        composeRule.onNodeWithText("Couldn't update the star. Try again.").assertIsDisplayed()
    }
}
