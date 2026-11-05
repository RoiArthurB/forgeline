package fr.arthurbrugiere.forgeline.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.markdown.ReadmeContext
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.parsePatch
import fr.arthurbrugiere.forgeline.core.testing.changedFile
import fr.arthurbrugiere.forgeline.core.testing.commit
import fr.arthurbrugiere.forgeline.core.testing.notificationThread
import fr.arthurbrugiere.forgeline.core.testing.trendingRepo
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftChipTabs
import fr.arthurbrugiere.forgeline.core.ui.theme.ForgelineTheme
import fr.arthurbrugiere.forgeline.file.FileContent
import fr.arthurbrugiere.forgeline.file.FileScreen
import fr.arthurbrugiere.forgeline.file.FileTarget
import fr.arthurbrugiere.forgeline.file.FileUiState
import fr.arthurbrugiere.forgeline.inbox.InboxScreen
import fr.arthurbrugiere.forgeline.inbox.InboxSection
import fr.arthurbrugiere.forgeline.inbox.InboxUiState
import fr.arthurbrugiere.forgeline.inbox.SectionGroup
import fr.arthurbrugiere.forgeline.pull.BlameBlock
import fr.arthurbrugiere.forgeline.pull.BlameScreen
import fr.arthurbrugiere.forgeline.pull.BlameUiState
import fr.arthurbrugiere.forgeline.pull.FileDiff
import fr.arthurbrugiere.forgeline.pull.fileDiffItems
import fr.arthurbrugiere.forgeline.repo.Loadable
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
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(11)

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

        // Reason and place share a line; at a large font it takes a second one rather than cutting the place short.
        val where = composeRule.onNodeWithText("acme/⁠rocket #42", substring = true, useUnmergedTree = true).textLayout()
        assertThat((0 until where.lineCount).none { where.isLineEllipsized(it) }).isTrue()
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

    @Test
    fun a_file_s_line_numbers_stay_on_one_line() {
        val code = (1..12).joinToString("\n") { "val v$it = $it" }
        setThemedContent {
            FileScreen(
                state = FileUiState(
                    target = FileTarget(RepoId("octo", "repo"), "src/Main.kt", "main"),
                    content = Loadable.Loaded(FileContent.Text(code)),
                    webUrl = "https://github.com/octo/repo/blob/main/src/Main.kt",
                    readmeContext = ReadmeContext("https://raw.example/", "https://blob.example/"),
                ),
                onBack = {}, onRetry = {}, onOpenInBrowser = {}, onLinkClick = {}, onCopy = {},
            )
        }

        assertThat(composeRule.onNodeWithText("10").textLayout().lineCount).isEqualTo(1)
    }

    @Test
    fun a_change_s_four_figure_line_numbers_keep_its_lines_one_line_tall() {
        val file = changedFile("src/Big.kt", patch = "@@ -1234,1 +1234,2 @@\n x\n+y")
        setThemedContent { LazyColumn { fileDiffItems(listOf(FileDiff(file, parsePatch(file.patch!!))), isExpanded = { true }, onToggle = {}) } }

        // A line of code and its numbers share one line's height (18sp, and a 1dp breath above and below); a number
        // broken in two would make the row twice that.
        val oneLine = with(composeRule.density) { 18.sp.toDp() } + 2.dp
        val row = composeRule.onNode(hasContentDescription("Line 1235, added: y")).getUnclippedBoundsInRoot()
        assertThat((row.bottom - row.top).value).isAtMost(oneLine.value + 0.5f)
    }

    @Test
    fun a_blame_s_four_figure_line_numbers_stay_on_one_line() {
        val tools = RepoId("octo", "tools")
        setThemedContent {
            BlameScreen(
                BlameUiState(FileTarget(tools, "src/Main.kt", "main"), Loadable.Loaded(listOf(BlameBlock(commit("1a2b3c4d5e", "Start"), 1234, listOf("x"))))),
                onBack = {}, onRetry = {}, onOpenCommit = {}, onSignIn = {},
                nowMillis = 0L,
            )
        }

        assertThat(composeRule.onNodeWithText("1234").textLayout().lineCount).isEqualTo(1)
    }
}
