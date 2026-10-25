package fr.arthurbrugiere.forgeline.core.forge

import fr.arthurbrugiere.forgeline.core.model.ArtifactDocument
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Reads the Markdown files out of a run's artifact as it downloads: a report a workflow wrote for people to read, which
 * GitHub's API can't give as the run's summary. Holds the files it keeps, never the archive.
 */
object ArtifactArchive {
    /** The longest file kept: a long report is a few hundred kilobytes (GAMA-GC-Benchmark's, 62 KB). */
    const val MAX_FILE_BYTES = 512 * 1024

    /** The most files kept, the shallowest first. */
    const val MAX_FILES = 20

    /** The largest archive downloaded to look for them: a report's is about a megabyte (GAMA-GC-Benchmark's, 1.1 MB). */
    const val MAX_ARCHIVE_BYTES = 25 * 1024 * 1024

    private val order = compareBy<ArtifactDocument>({ it.path.count { c -> c == '/' } }, { it.path })

    fun markdownIn(input: InputStream): List<ArtifactDocument> {
        val kept = mutableListOf<ArtifactDocument>()
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (entry.isDirectory || !isMarkdown(name)) continue
                    val bytes = zip.readNBytes(MAX_FILE_BYTES + 1)
                    if (bytes.size > MAX_FILE_BYTES) continue
                    kept += ArtifactDocument(name, String(bytes, Charsets.UTF_8))
                    // Only the best of them are held while the rest of the archive goes by.
                    if (kept.size > MAX_FILES) {
                        kept.sortWith(order)
                        kept.removeAt(kept.lastIndex)
                    }
                }
            }
        } catch (_: ZipException) {
            // What was read before the archive broke off still reads.
        }
        return kept.sortedWith(order)
    }

    private fun isMarkdown(name: String) = name.endsWith(".md", ignoreCase = true) || name.endsWith(".markdown", ignoreCase = true)
}
