package fr.arthurbrugiere.forgeline.configchecks

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Release notes are hand-written: GitHub's generated ones list pull requests, and this repo commits straight to main,
 * so they came out empty. The version code comes from the tag, so F-Droid, which builds from source, can know it.
 */
class ReleaseWorkflowTest {
    private val release = Repo.text(".github/workflows/release.yml")

    private fun version(tag: String): Pair<Int, String> {
        val process = ProcessBuilder("bash", Repo.file("tools/release/version.sh").path, tag).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }

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
    fun the_version_code_is_worked_out_from_the_tag() {
        assertThat(version("v0.12.0")).isEqualTo(0 to "versionName=0.12.0\nversionCode=1200\n")
        assertThat(version("v1.2.3")).isEqualTo(0 to "versionName=1.2.3\nversionCode=10203\n")
        assertThat(version("v12.99.99")).isEqualTo(0 to "versionName=12.99.99\nversionCode=129999\n")
    }

    @Test
    fun every_derived_code_is_above_the_run_numbers_the_first_releases_used() {
        // v0.1.0 to v0.11.0 were coded with the workflow's run number, which had reached 10.
        val (_, output) = version("v0.12.0")
        assertThat(output.lines().first { it.startsWith("versionCode=") }.substringAfter('=').toInt()).isGreaterThan(10)
    }

    @Test
    fun a_tag_the_formula_cannot_code_stops_the_release() {
        for (tag in listOf("v0.12.0-beta1", "0.12.0", "v0.12", "v0.100.0", "v0.1.100", "v0.12.0 ")) {
            assertWithMessage(tag).that(version(tag).first).isNotEqualTo(0)
        }
    }

    @Test
    fun the_release_is_built_and_described_with_the_derived_version() {
        val derive = release.indexOf("tools/release/version.sh \"\$GITHUB_REF_NAME\"")
        assertThat(derive).isAtLeast(0)
        assertThat(derive).isLessThan(release.indexOf("assembleRelease"))
        assertThat(release).contains("-Pforgeline.versionCode=\"\$versionCode\"")
        assertThat(release).doesNotContain("run_number")
        // F-Droid's update check reads it from the latest release: docs/DISTRIBUTION.md.
        assertThat(release).containsMatch("""files: \|\s+forgeline-\*\.apk\s+version\.txt""")
    }

    @Test
    fun f_droids_recipe_can_rewrite_the_version_lines_of_gradle_properties() {
        // Its prebuild step replaces these two lines with the release's version: docs/DISTRIBUTION.md.
        val properties = Repo.text("gradle.properties")
        assertThat(properties).containsMatch("""(?m)^forgeline\.versionName=""")
        assertThat(properties).containsMatch("""(?m)^forgeline\.versionCode=""")
        assertThat(Repo.text("docs/DISTRIBUTION.md")).contains("s/^forgeline.versionCode=.*/forgeline.versionCode=\$\$VERCODE\$\$/")
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
