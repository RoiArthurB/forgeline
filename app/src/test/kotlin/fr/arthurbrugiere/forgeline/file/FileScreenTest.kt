package fr.arthurbrugiere.forgeline.file

import fr.arthurbrugiere.forgeline.ui.PICTURE_TAG
import coil3.asImage
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.markdown.ReadmeContext
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.repo.Loadable
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class FileScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()

    private fun state(path: String, content: Loadable<FileContent>) = FileUiState(
        target = FileTarget(RepoId("octo", "repo"), path, "main"),
        content = content,
        webUrl = "https://github.com/octo/repo/blob/main/$path",
        readmeContext = ReadmeContext("https://raw.example/", "https://blob.example/", "docs/"),
    )

    private fun setContent(state: FileUiState) {
        composeRule.setContent {
            FileScreen(
                state = state,
                onBack = { events += "back" },
                onRetry = { events += "retry" },
                onOpenInBrowser = { events += "browser:$it" },
                onLinkClick = { events += "link:$it" },
                onCopy = { events += "copy:${it.length}" },
                onOpenHistory = { events += "history" },
                onOpenBlame = { events += "blame" },
            )
        }
    }

    @Test
    fun a_file_leads_to_its_history_and_where_the_forge_can_tell_to_its_blame() {
        setContent(state("src/Main.kt", Loadable.Loaded(FileContent.Text("fun main() {}\n"))).copy(canBlame = true))

        composeRule.onNodeWithText("History").performClick()
        composeRule.onNodeWithText("Blame").performClick()

        assertThat(events).containsExactly("history", "blame").inOrder()
    }

    @Test
    fun blame_is_not_offered_where_the_forge_has_none_nor_for_a_file_that_is_not_text() {
        // Forgejo's API serves no blame: the button would lead to nothing.
        setContent(state("src/Main.kt", Loadable.Loaded(FileContent.Text("fun main() {}\n"))))
        composeRule.onNodeWithText("History").assertExists()
        composeRule.onNodeWithText("Blame").assertDoesNotExist()
    }

    @Test
    fun a_file_that_is_not_text_has_a_history_and_no_blame() {
        setContent(state("logo.bin", Loadable.Loaded(FileContent.Binary)).copy(canBlame = true))

        composeRule.onNodeWithText("History").assertExists()
        composeRule.onNodeWithText("Blame").assertDoesNotExist()
    }

    @Test
    fun shows_code_with_line_numbers_then_highlights_it() {
        setContent(state("src/Main.kt", Loadable.Loaded(FileContent.Text("fun main() {\n    println(1)\n}\n"))))

        composeRule.onNodeWithText("fun main() {").assertIsDisplayed()
        composeRule.onNodeWithText("3").assertIsDisplayed()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasTestTag(CODE_HIGHLIGHTED_TAG)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun markdown_files_render_as_documents() {
        setContent(state("docs/GUIDE.md", Loadable.Loaded(FileContent.Text("# Guide\n\nSee [setup](setup.md)."))))

        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Guide")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("setup", substring = true).performClick()

        assertThat(events).containsExactly("link:https://blob.example/docs/setup.md")
    }

    @Test
    fun binary_files_offer_the_web_instead() {
        setContent(state("logo.png", Loadable.Loaded(FileContent.Binary)))

        composeRule.onNodeWithText("Binary file").assertIsDisplayed()
        composeRule.onNodeWithText("Open on GitHub").performClick()

        assertThat(events).containsExactly("browser:https://github.com/octo/repo/blob/main/logo.png")
    }

    @Test
    fun too_large_files_say_so() {
        setContent(state("big.json", Loadable.Failed(ForgeError.Http(413, "File too large to preview"))))

        composeRule.onNodeWithText("Too large to preview").assertIsDisplayed()
    }

    @Test
    fun other_failures_can_be_retried() {
        setContent(state("a.kt", Loadable.Failed(ForgeError.Network)))

        composeRule.onNodeWithText("Retry").performClick()

        assertThat(events).containsExactly("retry")
    }

    @Test
    fun text_files_can_be_copied() {
        setContent(state("a.kt", Loadable.Loaded(FileContent.Text("hello"))))

        composeRule.onNode(hasContentDescription("Copy file")).performClick()

        assertThat(events).containsExactly("copy:5")
    }

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    private fun withPictures(loads: Boolean, block: () -> Unit) {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val answer = coil3.intercept.Interceptor { chain ->
            if (loads) {
                coil3.request.SuccessResult(android.graphics.Bitmap.createBitmap(40, 30, android.graphics.Bitmap.Config.ARGB_8888).asImage(), chain.request, coil3.decode.DataSource.NETWORK)
            } else {
                coil3.request.ErrorResult(null, chain.request, IllegalStateException("404"))
            }
        }
        coil3.SingletonImageLoader.setUnsafe(coil3.ImageLoader.Builder(context).components { add(answer) }.build())
        try {
            block()
        } finally {
            coil3.SingletonImageLoader.reset()
        }
    }

    private val picture = state("docs/logo.png", Loadable.Loaded(FileContent.Picture("https://raw.example/docs/logo.png")))

    private fun zoom() = composeRule.onNodeWithTag(PICTURE_TAG).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription]

    @Test
    fun a_picture_is_shown_and_a_double_tap_brings_it_closer_then_back() = withPictures(loads = true) {
        // Regression: a picture in the code only said "Binary file".
        setContent(picture)
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(PICTURE_TAG).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("Binary file").assertDoesNotExist()
        assertThat(zoom()).isEqualTo("Shown at 100%")

        composeRule.onNodeWithTag(PICTURE_TAG).performTouchInput { doubleClick() }
        composeRule.waitForIdle()
        assertThat(zoom()).isEqualTo("Shown at 250%")

        composeRule.onNodeWithTag(PICTURE_TAG).performTouchInput { doubleClick() }
        composeRule.waitForIdle()
        assertThat(zoom()).isEqualTo("Shown at 100%")
    }

    @Test
    fun two_fingers_bring_a_picture_closer_up_to_a_limit_and_never_smaller_than_whole() = withPictures(loads = true) {
        setContent(picture)
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(PICTURE_TAG).fetchSemanticsNodes().isNotEmpty() }

        composeRule.onNodeWithTag(PICTURE_TAG).performTouchInput { pinch(center - Offset(20f, 0f), center - Offset(400f, 0f), center + Offset(20f, 0f), center + Offset(400f, 0f)) }
        composeRule.waitForIdle()
        assertThat(zoom()).isEqualTo("Shown at 600%")

        composeRule.onNodeWithTag(PICTURE_TAG).performTouchInput { pinch(center - Offset(400f, 0f), center - Offset(5f, 0f), center + Offset(400f, 0f), center + Offset(5f, 0f)) }
        composeRule.waitForIdle()
        assertThat(zoom()).isEqualTo("Shown at 100%")
    }

    @Test
    fun a_picture_names_the_file_to_screen_readers_and_is_not_offered_to_copy() = withPictures(loads = true) {
        setContent(picture)

        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasContentDescription("logo.png")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNode(hasContentDescription("Copy file contents")).assertDoesNotExist()
    }

    @Test
    fun a_picture_that_can_t_be_loaded_says_so_and_offers_the_forge() = withPictures(loads = false) {
        setContent(picture)

        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Couldn't load the picture").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("Open on GitHub").performClick()

        assertThat(events).containsExactly("browser:https://github.com/octo/repo/blob/main/docs/logo.png")
    }

    @Test
    fun a_file_is_shared_by_its_page_under_its_name() {
        setContent(state("src/Main.kt", Loadable.Loaded(FileContent.Text("fun main() {}"))))

        composeRule.onNode(androidx.compose.ui.test.hasContentDescription("Share link")).performClick()

        com.google.common.truth.Truth.assertThat(fr.arthurbrugiere.forgeline.ui.sharedLink()).isEqualTo("https://github.com/octo/repo/blob/main/src/Main.kt" to "Main.kt")
    }
}
