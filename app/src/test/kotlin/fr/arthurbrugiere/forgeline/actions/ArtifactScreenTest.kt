package fr.arthurbrugiere.forgeline.actions

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.ArtifactDocument
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RunArtifact
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class ArtifactScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()
    private val repo = RepoId("RoiArthurB", "GAMA-GC-Benchmark")
    private val report = RunArtifact(12520640926, "report", 1_154_768, expired = false)
    private val loaded = ArtifactUiState(repo, 7, report, isLoading = false)

    private fun setContent(state: ArtifactUiState) {
        composeRule.setContent {
            ArtifactScreen(
                state = state,
                onBack = {},
                onRetry = { events += "retry" },
                onSelect = { events += "select:$it" },
                onSignIn = { events += "signin" },
                onOpenInBrowser = { events += "browser" },
                onLinkClick = { events += "link:$it" },
            )
        }
    }

    @Test
    fun a_report_reads_as_markdown_under_the_artifact_s_name() {
        // GAMA-GC-Benchmark's report, its first heading and a row of its first table.
        val markdown = "# GAMA garbage-collector benchmark\n\n| config | sim time |\n|---|---|\n| `serial` | x0.927 |\n"
        setContent(loaded.copy(documents = listOf(ArtifactDocument("report/report.md", markdown))))

        composeRule.onNodeWithText("report").assertIsDisplayed()
        // The Markdown is read off the main thread first.
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("GAMA garbage-collector benchmark").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("GAMA garbage-collector benchmark").assertIsDisplayed()
        composeRule.onNodeWithText("report/report.md").assertIsDisplayed()
    }

    @Test
    fun several_files_are_listed_to_pick_from() {
        setContent(loaded.copy(documents = listOf(ArtifactDocument("README.md", "Read me"), ArtifactDocument("report/report.md", "# Report"))))

        composeRule.onNodeWithText("report/report.md").performClick()

        assertThat(events).containsExactly("select:report/report.md")
    }

    @Test
    fun an_artifact_without_markdown_says_so_and_leads_to_the_forge() {
        setContent(loaded.copy(documents = emptyList()))

        composeRule.onNodeWithText("No Markdown in it").assertIsDisplayed()
        composeRule.onNodeWithText("Open on GitHub").performClick()

        assertThat(events).containsExactly("browser")
    }

    @Test
    fun signed_out_it_asks_to_sign_in() {
        setContent(loaded.copy(error = ForgeError.Unauthorized))

        composeRule.onNodeWithText("GitHub hands artifacts to signed-in accounts only.", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Sign in").performClick()

        assertThat(events).containsExactly("signin")
    }

    @Test
    fun one_too_large_to_read_here_says_how_large() {
        setContent(loaded.copy(artifact = report.copy(sizeBytes = 80L * 1024 * 1024), error = ForgeError.Http(413, "Artifact too large to read")))

        composeRule.onNodeWithText("Too large to read here").assertIsDisplayed()
        composeRule.onNodeWithText("This artifact is 80 MB; Forgeline reads the ones up to 25 MB.").assertExists()
    }
}
