package fr.arthurbrugiere.forgeline.trending

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import androidx.compose.ui.test.assertIsDisplayed
import com.google.common.truth.Truth.assertWithMessage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
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

        composeRule.onNode(hasContentDescription("Rank 1"), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("paperclipai", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("paperclip", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Description of paperclipai/paperclip", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("85.9k stars", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNode(hasContentDescription("+2,109 today"), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Updated 5 minutes ago").assertIsDisplayed()
    }

    @Test
    fun the_gain_label_follows_the_period() {
        setContent(TrendingUiState(period = TrendingPeriod.WEEKLY, items = listOf(TrendingItem(paperclip, null))))

        composeRule.onNode(hasContentDescription("+2,109 this week"), useUnmergedTree = true).assertIsDisplayed()
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
    fun tapping_a_row_opens_the_repo() {
        setContent(TrendingUiState(items = listOf(TrendingItem(paperclip, null))))

        composeRule.onNodeWithText("paperclip", useUnmergedTree = true).performClick()

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
        composeRule.onNode(hasContentDescription("Loading trending repositories")).assertIsDisplayed()
    }

    @Test
    fun a_failed_refresh_over_a_cached_list_shows_a_snackbar() {
        setContent(TrendingUiState(items = listOf(TrendingItem(paperclip, null)), error = ForgeError.Network))

        composeRule.onNodeWithText("Couldn't refresh. Showing the last saved list.").assertIsDisplayed()
        composeRule.onNodeWithText("paperclip", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun the_last_browse_is_flagged_and_can_be_resumed() {
        val repos = (1..30).map { TrendingItem(trendingRepo("owner/repo$it"), null) }
        setContent(TrendingUiState(items = repos, resumeAt = 20))

        composeRule.onNodeWithText("Where you left off").assertDoesNotExist()
        composeRule.onNodeWithText("Continue where you left off").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Where you left off").assertIsDisplayed()
        // There: the way back has done its job.
        composeRule.onNodeWithText("Continue where you left off").assertDoesNotExist()
    }

    @Test
    fun a_mark_already_on_screen_needs_no_way_back() {
        val repos = (1..5).map { TrendingItem(trendingRepo("owner/repo$it"), null) }
        setContent(TrendingUiState(items = repos, resumeAt = 1))

        composeRule.onNodeWithText("Where you left off").assertIsDisplayed()
        composeRule.onNodeWithText("Continue where you left off").assertDoesNotExist()
    }

    @Test
    fun a_fully_read_list_has_no_flag() {
        val repos = (1..3).map { TrendingItem(trendingRepo("owner/repo$it"), null) }
        setContent(TrendingUiState(items = repos, resumeAt = 2))

        composeRule.onNodeWithText("Where you left off").assertDoesNotExist()
        composeRule.onNodeWithText("Continue where you left off").assertDoesNotExist()
    }

    @Test
    fun a_failed_star_shows_a_snackbar() {
        setContent(TrendingUiState(items = listOf(TrendingItem(paperclip, false)), starFailed = true))

        composeRule.onNodeWithText("Couldn't update the star. Try again.").assertIsDisplayed()
    }

    @Test
    fun a_fetched_but_empty_list_says_so_and_offers_a_refresh() {
        setContent(TrendingUiState(updatedAtMillis = 5 * 60_000L))

        composeRule.onNode(hasContentDescription("Loading trending repositories")).assertDoesNotExist()
        composeRule.onNodeWithText("Nothing trending here yet").assertIsDisplayed()
        composeRule.onNodeWithText("Refresh").performClick()

        assertThat(events).containsExactly("refresh")
    }

    // Robolectric measures text far narrower than a device, so this can't prove a fit. It checks the period labels
    // aren't clipped and every stat is on screen; crowding itself (the language once vanished and the stats ran
    // together at 2x) is only visible with real text metrics, so the trending_font_* screenshot goldens guard it.
    @Test
    @Config(qualifiers = "w360dp-h800dp-xhdpi", fontScale = 2.0f)
    fun labels_and_stats_stay_whole_at_large_font_scales_on_a_small_phone() {
        val repo = paperclip.copy(language = "TypeScript", forks = 4_210)
        setContent(TrendingUiState(items = listOf(TrendingItem(repo, starred = true))))

        for (stat in listOf("TypeScript", "85.9k stars", "4.2k forks")) {
            composeRule.onNodeWithText(stat, useUnmergedTree = true).assertIsDisplayed()
        }
        // The switch grows to its tallest label and keeps all three centred on one line.
        val centres = listOf("Today", "This week", "This month").map { label ->
            composeRule.onNodeWithText(label, useUnmergedTree = true).getBoundsInRoot().let { (it.top + it.bottom) / 2 }
        }
        centres.forEach { assertThat(it.value).isWithin(1f).of(centres.first().value) }
        for (label in listOf("Today", "This week", "This month")) {
            val node = composeRule.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            assertWithMessage("$label is cut off").that(layouts.single().hasVisualOverflow).isFalse()
        }
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun descriptions_keep_a_comfortable_line_length_on_wide_screens() {
        setContent(TrendingUiState(items = listOf(TrendingItem(paperclip.copy(description = "word ".repeat(80)), null))))

        val bounds = composeRule.onNodeWithTag(DESCRIPTION_TAG, useUnmergedTree = true).getBoundsInRoot()
        assertThat((bounds.right - bounds.left).value).isAtMost(MaxMeasure.value)
    }

    @Test
    fun descriptions_keep_three_lines_normally_and_all_of_their_text_when_fonts_are_enlarged() {
        assertThat(descriptionMaxLines(1f)).isEqualTo(3)
        assertThat(descriptionMaxLines(1.3f)).isEqualTo(Int.MAX_VALUE)
        assertThat(descriptionMaxLines(2f)).isEqualTo(Int.MAX_VALUE)
    }

    @Test
    fun a_page_mixing_forges_names_each_rows_forge() {
        val zig = trendingRepo("ziglang/zig", forge = ForgeInstance.Codeberg).copy(language = "Zig", languageColor = null)
        setContent(TrendingUiState(items = listOf(TrendingItem(paperclip, null), TrendingItem(zig, null)), showForge = true))

        // A logo and its name, so a row never depends on a 14dp mark alone. The name is written, so the logo is silent.
        composeRule.onNodeWithText("Codeberg").assertExists()
        composeRule.onNodeWithText("GitHub").assertExists()
        composeRule.onNodeWithContentDescription("Codeberg").assertDoesNotExist()
    }

    @Test
    fun one_forge_alone_names_none() {
        setContent(TrendingUiState(items = listOf(TrendingItem(paperclip, null))))

        composeRule.onNodeWithText("GitHub").assertDoesNotExist()
    }

    @Test
    fun the_same_name_on_two_forges_is_two_rows() {
        // Regression: rows were keyed by owner/name, and a repository trending on both forges crashed the list.
        val onCodeberg = paperclip.copy(id = paperclip.id.copy(forge = ForgeInstance.Codeberg))
        setContent(TrendingUiState(items = listOf(TrendingItem(paperclip, null), TrendingItem(onCodeberg, null)), showForge = true))

        composeRule.onNodeWithText("Codeberg").assertExists()
        composeRule.onNodeWithText("GitHub").assertExists()
    }

    @Test
    fun one_forge_can_be_picked_when_several_trend() {
        val picked = mutableListOf<ForgeInstance?>()
        composeRule.setContent {
            TrendingScreen(
                state = TrendingUiState(items = listOf(TrendingItem(paperclip, null)), forges = listOf(ForgeInstance.GitHub, ForgeInstance.Codeberg)),
                onPeriodChange = {}, onRefresh = {}, onToggleStar = {}, onOpenRepo = {}, onErrorShown = {}, onStarFailureShown = {},
                onSelectForge = { picked += it },
            )
        }

        composeRule.onNodeWithText("All forges").assertIsSelected()
        // The chips name their forge next to the logo.
        composeRule.onNodeWithText("GitHub").assertIsDisplayed()
        composeRule.onNodeWithText("Codeberg").performClick()

        assertThat(picked).containsExactly(ForgeInstance.Codeberg)
    }
}
