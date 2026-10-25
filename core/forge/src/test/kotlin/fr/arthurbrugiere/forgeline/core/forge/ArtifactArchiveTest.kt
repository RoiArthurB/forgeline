package fr.arthurbrugiere.forgeline.core.forge

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.model.ArtifactDocument
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArtifactArchiveTest {
    private fun zip(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            for ((path, text) in files) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
    }.toByteArray()

    @Test
    fun an_artifact_s_markdown_files_are_read_and_the_rest_left() {
        // As RoiArthurB/GAMA-GC-Benchmark's "report" artifact holds them (2026-10-23): the report, its data, every result.
        val archive = zip(
            "all-results/gha-1-ants-r0.jsonl" to "{}",
            "report/summary.csv" to "config,sim time",
            "report/report.md" to "# GAMA garbage-collector benchmark",
            "notes/README.MD" to "Notes",
            "docs/" to "",
        )

        val documents = ArtifactArchive.markdownIn(archive.inputStream())

        // The shallowest first, as a reader looks for the main one.
        assertThat(documents).containsExactly(
            ArtifactDocument("notes/README.MD", "Notes"),
            ArtifactDocument("report/report.md", "# GAMA garbage-collector benchmark"),
        ).inOrder()
    }

    @Test
    fun a_file_too_long_to_read_on_a_phone_is_left_out_and_so_are_files_past_the_most_kept() {
        val archive = zip(
            "huge.md" to "x".repeat(ArtifactArchive.MAX_FILE_BYTES + 1),
            *(1..ArtifactArchive.MAX_FILES + 5).map { "page$it.md" to "Page $it" }.toTypedArray(),
        )

        val documents = ArtifactArchive.markdownIn(archive.inputStream())

        assertThat(documents.map { it.path }).doesNotContain("huge.md")
        assertThat(documents).hasSize(ArtifactArchive.MAX_FILES)
    }

    @Test
    fun what_isn_t_an_archive_reads_as_none() {
        assertThat(ArtifactArchive.markdownIn("not a zip".byteInputStream())).isEmpty()
    }
}
