package fr.arthurbrugiere.forgeline.configchecks

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Release notes are hand-written: GitHub's generated ones list pull requests, and this repo commits straight to main,
 * so they came out empty.
 */
class ReleaseWorkflowTest {
    private val release = Repo.text(".github/workflows/release.yml")

    @Test
    fun the_release_text_comes_from_the_tags_notes_file() {
        assertThat(release).contains("body_path: docs/release-notes/\${{ github.ref_name }}.md")
        assertThat(release).doesNotContain("generate_release_notes: true")
    }

    @Test
    fun a_tag_without_notes_stops_before_building() {
        val check = release.indexOf("docs/release-notes/\${GITHUB_REF_NAME}.md")
        assertThat(check).isAtLeast(0)
        assertThat(check).isLessThan(release.indexOf("assembleRelease"))
    }

    @Test
    fun a_release_republishes_the_landing_page_once_it_is_up() {
        // A release made with the workflow's own token starts no other workflow, so the page has to be asked for.
        val republish = release.indexOf("gh workflow run trending.yml")
        assertThat(republish).isGreaterThan(release.indexOf("action-gh-release"))
        assertThat(release).contains("-f republish=true")
        assertThat(release).containsMatch("""actions:\s*write""")
    }

    @Test
    fun every_notes_file_is_named_after_a_version_tag_and_says_something() {
        val notes = File(Repo.root, "docs/release-notes").listFiles().orEmpty()
        assertThat(notes.map(File::getName)).contains("v0.2.0.md")
        for (file in notes) {
            assertWithMessage(file.name).that(file.name).matches("""v\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?\.md""")
            assertWithMessage(file.name).that(file.readText().trim().length).isGreaterThan(100)
        }
    }
}
