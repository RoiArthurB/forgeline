package fr.arthurbrugiere.forgeline.file

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
            )
        }
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
}
