package fr.arthurbrugiere.forgeline.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.testing.notificationThread
import fr.arthurbrugiere.forgeline.core.testing.trendingRepo
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftChipTabs
import fr.arthurbrugiere.forgeline.core.ui.theme.ForgelineTheme
import fr.arthurbrugiere.forgeline.inbox.InboxScreen
import fr.arthurbrugiere.forgeline.inbox.InboxSection
import fr.arthurbrugiere.forgeline.inbox.InboxUiState
import fr.arthurbrugiere.forgeline.inbox.SectionGroup
import fr.arthurbrugiere.forgeline.trending.TrendingItem
import fr.arthurbrugiere.forgeline.trending.TrendingScreen
import fr.arthurbrugiere.forgeline.trending.TrendingUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The largest system font on a small phone: what the layouts must survive without splitting words or losing a name. */
// Native graphics: text is measured with real font metrics, as in the screenshot tests.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-xhdpi", fontScale = 2.0f)
class LargeFontLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setThemedContent(content: @Composable () -> Unit) = composeRule.setContent { ForgelineTheme(darkTheme = false, content = content) }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    @Test
    fun a_two_digit_rank_stays_on_one_line() {
        val items = (1..12).map { TrendingItem(trendingRepo("owner$it/project$it"), starred = null) }
        setThemedContent {
            TrendingScreen(
                state = TrendingUiState(items = items, updatedAtMillis = 0L),
                onPeriodChange = {}, onRefresh = {}, onToggleStar = {}, onOpenRepo = {}, onErrorShown = {}, onStarFailureShown = {},
                nowMillis = 60_000L,
            )
        }
        composeRule.onNode(hasScrollAction()).performScrollToIndex(11)

        // One line of the rank's figure style (20sp) and its top padding; two lines would be twice that.
        val oneLine = with(composeRule.density) { 20.sp.toDp() } + 4.dp
        val rank = composeRule.onNode(hasContentDescription("Rank 12"), useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertThat((rank.bottom - rank.top).value).isAtMost(oneLine.value)
    }

    private fun inbox(vararg threads: fr.arthurbrugiere.forgeline.core.model.NotificationThread) {
        setThemedContent {
            InboxScreen(
                state = InboxUiState(groups = listOf(SectionGroup(InboxSection.NEEDS_YOU, threads.toList())), syncedAtMillis = 1),
                onSelectFilter = {}, onRefresh = {}, onOpen = {}, onMarkRead = {}, onMarkDone = {}, onUnsubscribe = {},
                onErrorShown = {}, onActionFailureShown = {},
                nowMillis = java.time.Instant.parse("2026-09-27T10:00:00Z").toEpochMilli(),
            )
        }
    }

    @Test
    fun the_filter_switch_shrinks_a_long_word_instead_of_breaking_it() {
        inbox(notificationThread("42"))

        val l = composeRule.onNodeWithText("Participating").textLayout()
        assertThat(l.lineCount).isEqualTo(1)
    }

    @Test
    fun a_notification_keeps_its_repository_whole_beside_its_reason() {
        inbox(notificationThread("42", repo = "acme/rocket", title = "Launch fails on cold start"))

        val where = composeRule.onNodeWithText("acme/⁠rocket #42", useUnmergedTree = true).textLayout()
        assertThat(where.isLineEllipsized(0)).isFalse()
    }

    @Test
    fun a_strip_wider_than_the_screen_opens_with_the_chosen_option_in_view() {
        setThemedContent {
            Box(Modifier.width(320.dp)) {
                SoftChipTabs(options = listOf("README", "Code", "Issues", "Pull requests", "Releases", "Actions"), selected = 5, onSelect = {})
            }
        }
        composeRule.waitForIdle()

        val actions = composeRule.onNodeWithText("Actions").getUnclippedBoundsInRoot()
        assertThat(actions.right.value).isAtMost(320f)
    }
}
