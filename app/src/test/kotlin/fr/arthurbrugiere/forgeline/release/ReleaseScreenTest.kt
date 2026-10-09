package fr.arthurbrugiere.forgeline.release

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.Release
import fr.arthurbrugiere.forgeline.core.model.ReleaseAsset
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.ui.assertEveryTargetIsAtLeast48dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class ReleaseScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()
    private val repo = RepoId("octo", "tools")
    private val release = Release(
        tag = "v2.0.0",
        name = "Tools 2.0",
        body = "Faster everything.\n\nSee the [guide](docs/GUIDE.md).",
        publishedAt = Instant.parse("2026-09-30T10:00:00Z"),
        isPrerelease = false,
        author = ForgeUser("octocat", null, null),
        assets = listOf(
            ReleaseAsset("tools-2.0.0.apk", 6_081_740, 1_532, "https://github.com/octo/tools/releases/download/v2.0.0/tools-2.0.0.apk"),
            ReleaseAsset("checksums.txt", 512, 1, "https://github.com/octo/tools/releases/download/v2.0.0/checksums.txt"),
        ),
        zipUrl = "https://github.com/octo/tools/archive/refs/tags/v2.0.0.zip",
        tarUrl = "https://github.com/octo/tools/archive/refs/tags/v2.0.0.tar.gz",
        webUrl = "https://github.com/octo/tools/releases/tag/v2.0.0",
        isLatest = true,
    )

    private fun setContent(state: ReleaseUiState) {
        composeRule.setContent {
            ReleaseScreen(
                state = state,
                onBack = { events += "back" },
                onRefresh = { events += "refresh" },
                onOpenRepo = { events += "repo" },
                onOpenUser = { events += "user:$it" },
                onDownload = { events += "download:$it" },
                onOpenInBrowser = { events += "browser:$it" },
                onLinkClick = { events += "link:$it" },
                onErrorShown = { events += "error-shown" },
                nowMillis = Instant.parse("2026-10-02T10:00:00Z").toEpochMilli(),
            )
        }
    }

    private fun shown(change: (Release) -> Release = { it }) = ReleaseUiState(repo, "v2.0.0", change(release))

    private fun waitFor(text: String) =
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun reach(text: String) = composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))

    @Test
    fun shares_the_address_of_the_release() {
        setContent(shown())

        composeRule.onNodeWithContentDescription("Share link").performClick()

        assertThat(fr.arthurbrugiere.forgeline.ui.sharedLink()).isEqualTo("https://github.com/octo/tools/releases/tag/v2.0.0" to "octo/tools v2.0.0")
    }

    @Test
    fun says_what_the_release_is_and_where_it_stands() {
        setContent(shown())

        composeRule.onNode(hasText("Tools 2.0") and isHeading()).assertIsDisplayed()
        composeRule.onNodeWithText("octo/tools").assertIsDisplayed()
        composeRule.onNodeWithText("v2.0.0").assertIsDisplayed()
        composeRule.onNodeWithText("Latest").assertIsDisplayed()
        composeRule.onNodeWithText("Pre-release").assertDoesNotExist()
        composeRule.onNodeWithText("octocat released this 2 days ago").assertIsDisplayed()
    }

    @Test
    fun a_pre_release_says_so_and_a_release_without_a_name_goes_by_its_tag() {
        setContent(shown { it.copy(name = null, isPrerelease = true, isLatest = false) })

        composeRule.onNode(hasText("v2.0.0") and isHeading()).assertIsDisplayed()
        composeRule.onNodeWithText("Pre-release").assertIsDisplayed()
        composeRule.onNodeWithText("Latest").assertDoesNotExist()
    }

    @Test
    fun its_notes_are_rendered_and_their_links_resolve_at_its_tag() {
        setContent(shown())
        waitFor("Faster everything.")

        composeRule.onNodeWithText("guide", substring = true).performClick()

        assertThat(events).containsExactly("link:https://github.com/octo/tools/blob/v2.0.0/docs/GUIDE.md")
    }

    @Test
    fun a_release_without_notes_says_so() {
        setContent(shown { it.copy(body = null) })

        composeRule.onNodeWithText("No release notes.").assertIsDisplayed()
    }

    @Test
    fun its_files_say_their_size_and_how_often_they_were_downloaded() {
        setContent(shown())
        reach("checksums.txt")

        composeRule.onNode(hasText("2 files") and isHeading()).assertExists()
        composeRule.onNodeWithText("5.8 MB · 1.5k downloads").assertExists()
        composeRule.onNodeWithText("512 B · 1 download").assertExists()
    }

    @Test
    fun a_file_downloads_from_its_own_address() {
        setContent(shown())
        reach("tools-2.0.0.apk")

        composeRule.onNodeWithText("tools-2.0.0.apk").performClick()

        assertThat(events).containsExactly("download:https://github.com/octo/tools/releases/download/v2.0.0/tools-2.0.0.apk")
    }

    @Test
    fun a_forge_that_doesn_t_count_downloads_only_says_the_size() {
        setContent(shown { it.copy(assets = listOf(ReleaseAsset("notes.pdf", 2_329, null, "https://example.org/notes.pdf"))) })
        reach("notes.pdf")

        composeRule.onNode(hasText("1 file") and isHeading()).assertExists()
        composeRule.onNodeWithText("2.3 KB").assertExists()
    }

    @Test
    fun the_source_at_its_tag_downloads_as_a_zip_or_a_tarball() {
        setContent(shown())
        reach("Source code (tar.gz)")

        composeRule.onNodeWithText("Source code (zip)").performClick()
        composeRule.onNodeWithText("Source code (tar.gz)").performClick()

        assertThat(events).containsExactly(
            "download:https://github.com/octo/tools/archive/refs/tags/v2.0.0.zip", "download:https://github.com/octo/tools/archive/refs/tags/v2.0.0.tar.gz",
        ).inOrder()
    }

    @Test
    fun a_release_without_files_or_archives_shows_neither_section() {
        setContent(shown { it.copy(assets = emptyList(), zipUrl = null, tarUrl = null) })
        waitFor("Faster everything.")

        composeRule.onNodeWithText("Source code").assertDoesNotExist()
        composeRule.onNodeWithText("files", substring = true).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Download").assertDoesNotExist()
    }

    @Test
    fun reactions_show_under_the_notes() {
        setContent(shown { it.copy(reactions = mapOf(Reaction.HOORAY to 12, Reaction.ROCKET to 3)) })

        reach("🎉 12")
        composeRule.onNodeWithText("🚀 3").assertExists()
    }

    @Test
    fun the_repository_the_author_and_the_forge_s_page_are_one_tap_away() {
        setContent(shown())

        composeRule.onNodeWithText("octo/tools").performClick()
        composeRule.onNodeWithText("octocat released this 2 days ago").performClick()
        composeRule.onNodeWithContentDescription("Open on GitHub").performClick()
        composeRule.onNodeWithContentDescription("Navigate up").performClick()

        assertThat(events).containsExactly("repo", "user:octocat", "browser:https://github.com/octo/tools/releases/tag/v2.0.0", "back").inOrder()
    }

    @Test
    fun before_it_has_loaded_it_goes_by_its_tag_and_opens_where_its_page_should_be() {
        val onCodeberg = RepoId("octo", "tools", ForgeInstance.Codeberg)
        setContent(ReleaseUiState(onCodeberg, "v2.0.0", isRefreshing = true))

        composeRule.onNode(hasText("v2.0.0") and isHeading()).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Loading this release").assertExists()
        composeRule.onNodeWithContentDescription("Open on Codeberg").performClick()

        assertThat(events).containsExactly("browser:https://codeberg.org/octo/tools/releases/tag/v2.0.0")
    }

    @Test
    fun a_tag_without_a_release_says_so_and_can_be_retried() {
        setContent(ReleaseUiState(repo, "nope", error = ForgeError.Http(404, "Not Found")))

        composeRule.onNodeWithText("Couldn't open this release").assertIsDisplayed()
        composeRule.onNodeWithText("No release is published under this tag.").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()

        assertThat(events).containsExactly("refresh")
    }

    @Test
    fun a_failed_refresh_keeps_the_release_and_says_so_once() {
        setContent(shown().copy(error = ForgeError.Network))

        composeRule.onNode(hasText("Tools 2.0") and isHeading()).assertIsDisplayed()
        composeRule.waitUntil(5_000) { "error-shown" in events }
    }

    @Test
    fun every_target_is_large_enough_to_press() {
        setContent(shown())
        waitFor("Faster everything.")

        composeRule.assertEveryTargetIsAtLeast48dp()
    }
}
