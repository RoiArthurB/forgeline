package fr.arthurbrugiere.forgeline.core.model

import java.time.Instant

/** What a change did to a file. */
enum class FileChange { ADDED, MODIFIED, REMOVED, RENAMED }

/**
 * A file a pull request or a commit changes. [patch] is its change as a unified diff, from the first `@@` on; null
 * when the forge sends none: a binary file, or a change too large for it to send.
 */
data class ChangedFile(
    val path: String,
    /** Where it was, when the change moved it. */
    val previousPath: String?,
    val change: FileChange,
    val additions: Int,
    val deletions: Int,
    val patch: String?,
)

/** One page of the files a pull request changes; [nextPage] is null on the last. */
data class ChangedFiles(val files: List<ChangedFile>, val nextPage: Int?)

enum class DiffLineKind { CONTEXT, ADDED, REMOVED }

/** A line of a change, with its number in the file before ([oldNumber]) and after ([newNumber]), where it is in each. */
data class DiffLine(val kind: DiffLineKind, val text: String, val oldNumber: Int?, val newNumber: Int?)

/** A run of changed lines with the unchanged ones around them. [header] is its `@@ -1,4 +1,6 @@ fun name()` line. */
data class DiffHunk(val header: String, val lines: List<DiffLine>)

private val hunkHeader = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""")

/** A file's [ChangedFile.patch] as hunks of numbered lines. Lines git adds about the file itself ("\ No newline") are left out. */
fun parsePatch(patch: String): List<DiffHunk> {
    val hunks = mutableListOf<DiffHunk>()
    var header: String? = null
    var lines = mutableListOf<DiffLine>()
    var old = 0
    var new = 0
    fun close() {
        header?.let { hunks += DiffHunk(it, lines) }
        lines = mutableListOf()
    }
    patch.lineSequence().forEach { line ->
        val start = hunkHeader.find(line)
        when {
            start != null -> {
                close()
                header = line
                old = start.groupValues[1].toInt()
                new = start.groupValues[2].toInt()
            }
            header == null || line.startsWith("\\") -> Unit
            line.startsWith("+") -> lines += DiffLine(DiffLineKind.ADDED, line.drop(1), null, new++)
            line.startsWith("-") -> lines += DiffLine(DiffLineKind.REMOVED, line.drop(1), old++, null)
            // A context line starts with a space; some forges trim it off an empty one.
            else -> lines += DiffLine(DiffLineKind.CONTEXT, line.removePrefix(" "), old++, new++)
        }
    }
    close()
    // The text ends with a line break: that is not one more empty line of the file.
    return hunks.map { hunk ->
        val last = hunk.lines.lastOrNull()
        if (patch.endsWith("\n") && hunk === hunks.last() && last?.kind == DiffLineKind.CONTEXT && last.text.isEmpty()) hunk.copy(lines = hunk.lines.dropLast(1)) else hunk
    }
}

/** How many lines [patch] adds and takes away. */
fun countChanges(patch: String): Pair<Int, Int> {
    var added = 0
    var removed = 0
    var inHunk = false
    patch.lineSequence().forEach { line ->
        when {
            line.startsWith("@@") -> inHunk = true
            !inHunk -> Unit
            line.startsWith("+") -> added++
            line.startsWith("-") -> removed++
        }
    }
    return added to removed
}

private val diffStart = Regex("""^diff --git "?a/(.*?)"? "?b/(.*?)"?$""")

/**
 * The files a whole diff changes, as `git diff` writes it: what a forge serves for a pull request or a commit when
 * its API lists the files without their changes (Forgejo's `.diff`).
 */
fun parseUnifiedDiff(text: String): List<ChangedFile> {
    val files = mutableListOf<ChangedFile>()
    var oldPath: String? = null
    var newPath: String? = null
    var change = FileChange.MODIFIED
    var patch: StringBuilder? = null
    var started = false
    fun close() {
        if (!started) return
        val body = patch?.toString()
        val (added, removed) = body?.let(::countChanges) ?: (0 to 0)
        val path = (if (change == FileChange.REMOVED) oldPath else newPath) ?: oldPath ?: return
        files += ChangedFile(path, oldPath.takeIf { change == FileChange.RENAMED }, change, added, removed, body)
    }
    text.lineSequence().forEach { line ->
        val start = if (line.startsWith("diff --git ")) diffStart.find(line) else null
        when {
            line.startsWith("diff --git ") -> {
                close()
                started = true
                oldPath = start?.groupValues?.get(1)
                newPath = start?.groupValues?.get(2)
                change = FileChange.MODIFIED
                patch = null
            }
            !started -> Unit
            patch != null -> patch!!.append(line).append('\n')
            line.startsWith("@@") -> patch = StringBuilder(line).append('\n')
            line.startsWith("new file mode") -> change = FileChange.ADDED
            line.startsWith("deleted file mode") -> change = FileChange.REMOVED
            line.startsWith("rename from ") -> { change = FileChange.RENAMED; oldPath = line.removePrefix("rename from ") }
            line.startsWith("rename to ") -> newPath = line.removePrefix("rename to ")
        }
    }
    close()
    return files
}

/** A commit. [author] is their account on the forge, when the forge knows one for the commit's email. */
data class Commit(
    val sha: String,
    val message: String,
    val authorName: String?,
    val author: ForgeUser?,
    val date: Instant?,
) {
    /** The message's first line. */
    val title: String get() = message.lineSequence().firstOrNull().orEmpty()

    /** What follows the first line, when the message says more. */
    val description: String? get() = message.substringAfter('\n', "").trim().ifEmpty { null }

    val shortSha: String get() = sha.take(7)
}

data class CommitDetails(val commit: Commit, val files: List<ChangedFile>)

/** One page of a history, newest first; [nextPage] is null on the last. */
data class CommitPage(val commits: List<Commit>, val nextPage: Int?)

/** The lines [startLine] to [endLine] of a file (from 1, both included) as [commit] last left them. */
data class BlameRange(val startLine: Int, val endLine: Int, val commit: Commit)

enum class CheckState { PENDING, SUCCESS, FAILURE, SKIPPED }

/**
 * Something a forge ran on a pull request's latest commit: a CI job, a status another service reported. [runId]
 * is the run it belongs to, when the app can show it; [url] its page otherwise.
 */
data class Check(val name: String, val state: CheckState, val description: String?, val url: String?, val runId: Long? = null)

/** Where a set of checks stands as a whole: failed if one did, still running if one is, passed otherwise; null with none. */
fun List<Check>.overall(): CheckState? = when {
    isEmpty() -> null
    any { it.state == CheckState.FAILURE } -> CheckState.FAILURE
    any { it.state == CheckState.PENDING } -> CheckState.PENDING
    all { it.state == CheckState.SKIPPED } -> CheckState.SKIPPED
    else -> CheckState.SUCCESS
}

enum class MergeMethod { MERGE, SQUASH, REBASE }

/**
 * Whether a pull request can be merged, and how. [mergeable] is null while the forge is still working it out;
 * [canMerge] is whether the signed-in user may; [methods] are the ways the repository allows, its preferred first.
 */
data class MergeInfo(val mergeable: Boolean?, val canMerge: Boolean, val methods: List<MergeMethod>)

/** What a review says of a pull request as a whole. */
enum class ReviewVerdict { COMMENT, APPROVE, REQUEST_CHANGES }

/**
 * A remark on one line of a pull request's change, told as the change tells it: [newLine] is its number in the file
 * after the change, [oldLine] before it. A line the change adds has only the first, one it removes only the second,
 * one it leaves alone both. [previousPath] is where the file was, when the change moved it.
 */
data class LineComment(val path: String, val oldLine: Int?, val newLine: Int?, val body: String, val previousPath: String? = null) {
    /** Whether the line is only in the file before the change: one it removes. */
    val onOldSide: Boolean get() = newLine == null
}

/** A pull request to open: [head] is the branch that holds the change, [base] the one it goes into. */
data class NewPullRequest(val title: String, val body: String, val head: String, val base: String, val draft: Boolean = false)
