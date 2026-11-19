package fr.arthurbrugiere.forgeline.forge.tangled

import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ChangedFile
import fr.arthurbrugiere.forgeline.core.model.Commit
import fr.arthurbrugiere.forgeline.core.model.FileChange
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.tangled.temp.git.GetBlobQueryParams
import org.tangled.temp.git.ListCommitsCommit
import org.tangled.temp.git.ListCommitsQueryParams
import sh.christian.ozone.api.Did
import sh.tangled.git.DiffSrc
import sh.tangled.git.FileDiff
import sh.tangled.git.temp2.GetDiffQueryParams
import sh.tangled.git.temp2.GetDiffResponse

/** How much a change holds: lines added and taken away, files, commits. */
internal data class ChangeStats(val insertions: Int, val deletions: Int, val files: Int, val commits: Int)

/**
 * A change between two commits of a Tangled repository: its files, as a unified diff each, and its commits.
 *
 * The index's mirror of the knots diffs (`sh.tangled.git.temp2.getDiff`), holding a repository's objects and a fork's
 * together, so that a pull request from a fork, whose head its base repository's knot doesn't have, diffs too. It says
 * only which lines changed, by their numbers on each side, as Tangled's web app wants them
 * (`web/src/lib/api/repoDiff.ts`, read 2026-11-19): the text of both sides is then read from the index
 * (`org.tangled.temp.git.getBlob`) and the diff written out as git writes it, hunk by hunk.
 */
internal object TangledChanges {
    /** The diff from [base] in [baseRepo] to [head] in [headRepo], a fork's or the same. */
    suspend fun diff(xrpc: TangledXrpc, baseRepo: String, base: String, headRepo: String, head: String): ForgeResult<GetDiffResponse> = forgeCall {
        asDid(baseRepo).then { from ->
            asDid(headRepo).then { to -> xrpc.index.getDiff(GetDiffQueryParams(from, base, to, head)).toResult { it } }
        }
    }

    /** The commits from [base] (left out) to [head], newest first, reading objects from [repos] (a fork's too). */
    suspend fun commits(xrpc: TangledXrpc, repos: List<String>, base: String?, head: String, limit: Int = 100): ForgeResult<List<ListCommitsCommit>> = forgeCall {
        val dids = repos.distinct().map { asDid(it) }
        dids.firstOrNull { it is ForgeResult.Failure }?.let { return@forgeCall it as ForgeResult.Failure }
        val range = if (base != null) "$base..$head" else head
        xrpc.index.listCommits(
            ListCommitsQueryParams(dids.map { (it as ForgeResult.Success<Did>).value }, listOf(range), limit = limit.toLong()),
        ).toResult { it.commits }
    }

    /** How much a pull request's latest [patch] changes. */
    suspend fun stats(xrpc: TangledXrpc, repo: TicketRepo, patch: PatchRecordJson): ChangeStats? {
        val base = patch.base?.oid ?: return null
        val head = patch.head?.oid ?: return null
        return coroutineScope {
            val diff = async { (diff(xrpc, repo.did, base, patch.source?.repo ?: repo.did, head) as? ForgeResult.Success)?.value }
            val commits = async { (commits(xrpc, listOfNotNull(repo.did, patch.source?.repo), base, head) as? ForgeResult.Success)?.value?.size }
            val answer = diff.await() ?: return@coroutineScope null
            ChangeStats(
                insertions = answer.diffs.sumOf { file -> file.added() },
                deletions = answer.diffs.sumOf { file -> file.removed() },
                files = answer.diffs.size,
                commits = commits.await() ?: 0,
            )
        }
    }

    /**
     * [diffs] as Forgeline's changed files, their text read at [base] in [baseRepo] and at [head] in [headRepo]. A
     * binary file, a submodule or one too big to show gets no patch, as a forge leaves those out.
     */
    suspend fun files(xrpc: TangledXrpc, diffs: List<FileDiff>, baseRepo: String, base: String, headRepo: String, head: String): List<ChangedFile> = coroutineScope {
        diffs.map { file ->
            async {
                val change = file.change()
                val patch = if (file.showable()) {
                    val old = async { if (file.lhsSrc.exists) text(xrpc, baseRepo, base, file.lhsSrc.path) else "" }
                    val new = async { if (file.rhsSrc.exists) text(xrpc, headRepo, head, file.rhsSrc.path) else "" }
                    val oldText = old.await()
                    val newText = new.await()
                    if (oldText != null && newText != null) unifiedDiff(file, oldText.lines(), newText.lines()) else null
                } else {
                    null
                }
                ChangedFile(
                    path = file.rhsSrc.path.ifEmpty { file.lhsSrc.path },
                    previousPath = file.lhsSrc.path.takeIf { change == FileChange.RENAMED },
                    change = change,
                    additions = file.added(),
                    deletions = file.removed(),
                    patch = patch,
                )
            }
        }.awaitAll()
    }

    private suspend fun text(xrpc: TangledXrpc, repoDid: String, ref: String, path: String): String? =
        (asDid(repoDid).then { did -> xrpc.index.getBlob(GetBlobQueryParams(did, ref, path)).toResult { it.decodeToString() } } as? ForgeResult.Success)?.value

    /** A git commit, as a pull request or a history lists it. */
    fun commit(commit: ListCommitsCommit): Commit = Commit(
        sha = commit.oid,
        message = commit.message.trimEnd(),
        authorName = commit.author.name,
        author = null,
        date = commit.author.`when`.toJava(),
    )

    /**
     * [file]'s change as a unified diff, from its first `@@`, as git writes it: three lines of context around each change,
     * and changes closer than that in one hunk. The mirror's hunks hold only the lines that changed (the knot's held
     * context too): each pairs the lines of the two sides (0-based, `lhs` and `rhs`), names alone those only one side
     * has, and calls "novel" the paired lines whose text changed, which are written as taken away then added. Between
     * hunks the two sides are the same, line for line, which is where the context is read.
     */
    fun unifiedDiff(file: FileDiff, old: List<String>, new: List<String>): String {
        val oldLines = old.withoutLastBreak()
        val newLines = new.withoutLastBreak()
        val rows = mutableListOf<Row>()
        var i = 0L
        var j = 0L
        fun same(untilOld: Long, untilNew: Long) {
            while (i < untilOld && j < untilNew) rows += Row(' ', i++, j++)
        }
        for (hunk in file.hunks.sortedBy { hunk -> hunk.lines.firstNotNullOfOrNull { it.lhs ?: it.rhs } }) {
            val lefts = hunk.lines.mapNotNull { it.lhs }
            val rights = hunk.lines.mapNotNull { it.rhs }
            // A side the hunk has no line of starts where the lines alike before it end.
            val oldFirst = lefts.minOrNull() ?: (i + ((rights.minOrNull() ?: j) - j))
            val newFirst = rights.minOrNull() ?: (j + (oldFirst - i))
            same(oldFirst, newFirst)
            val novelOld = hunk.novelLhs.toSet()
            val novelNew = hunk.novelRhs.toSet()
            val removed = mutableListOf<Row>()
            val added = mutableListOf<Row>()
            fun flush() {
                rows += removed
                rows += added
                removed.clear()
                added.clear()
            }
            for (pair in hunk.lines) {
                val lhs = pair.lhs
                val rhs = pair.rhs
                if (lhs != null && rhs != null && lhs !in novelOld && rhs !in novelNew) {
                    flush()
                    rows += Row(' ', lhs, rhs)
                } else {
                    if (lhs != null) removed += Row('-', lhs, null)
                    if (rhs != null) added += Row('+', null, rhs)
                }
            }
            flush()
            i = maxOf(i, (lefts.maxOrNull() ?: (oldFirst - 1)) + 1)
            j = maxOf(j, (rights.maxOrNull() ?: (newFirst - 1)) + 1)
        }
        same(oldLines.size.toLong(), newLines.size.toLong())
        return buildString {
            for (range in hunksOf(rows)) {
                val shown = rows.subList(range.first, range.last + 1)
                val olds = shown.mapNotNull { it.old }
                val news = shown.mapNotNull { it.new }
                append("@@ -").append(start(olds)).append(',').append(olds.size)
                append(" +").append(start(news)).append(',').append(news.size).append(" @@\n")
                for (row in shown) {
                    append(row.mark).append(if (row.mark == '-') oldLines.line(row.old!!) else newLines.line(row.new!!)).append('\n')
                }
            }
        }.trimEnd('\n')
    }

    /** A line of a unified diff: kept (' '), taken away ('-') or added ('+'), by its number on each side it is on. */
    private data class Row(val mark: Char, val old: Long?, val new: Long?)

    /** Which [rows] git shows, hunk by hunk: each change with three lines around it, changes six lines apart or less together. */
    private fun hunksOf(rows: List<Row>): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        rows.forEachIndexed { index, row ->
            if (row.mark == ' ') return@forEachIndexed
            val from = maxOf(0, index - CONTEXT)
            val to = minOf(rows.lastIndex, index + CONTEXT)
            val last = ranges.lastOrNull()
            if (last != null && from <= last.last + 1) ranges[ranges.lastIndex] = last.first..to else ranges += from..to
        }
        return ranges
    }

    private fun start(lines: List<Long>): Long = lines.minOrNull()?.plus(1) ?: 0

    private fun List<String>.line(index: Long): String = getOrNull(index.toInt()).orEmpty()

    /** A file's lines, without the empty one `lines()` gives after a last line break. */
    private fun List<String>.withoutLastBreak(): List<String> = if (lastOrNull()?.isEmpty() == true) dropLast(1) else this

    private val DiffSrc.exists: Boolean get() = path.isNotEmpty() && oid.trim('0').isNotEmpty()

    private fun FileDiff.change(): FileChange = when {
        !lhsSrc.exists -> FileChange.ADDED
        !rhsSrc.exists -> FileChange.REMOVED
        lhsSrc.path != rhsSrc.path -> FileChange.RENAMED
        else -> FileChange.MODIFIED
    }

    private fun FileDiff.showable(): Boolean =
        !lhsSrc.isBinary && !rhsSrc.isBinary && !lhsSrc.isSubmodule && !rhsSrc.isSubmodule && lhsSrc.size <= MAX_SHOWN && rhsSrc.size <= MAX_SHOWN

    private fun FileDiff.added(): Int = hunks.sumOf { hunk ->
        val novel = hunk.novelRhs.toSet()
        hunk.lines.count { it.rhs != null && (it.lhs == null || it.rhs in novel) }
    }

    private fun FileDiff.removed(): Int = hunks.sumOf { hunk ->
        val novel = hunk.novelLhs.toSet()
        hunk.lines.count { it.lhs != null && (it.rhs == null || it.lhs in novel) }
    }

    /** The largest file shown, as Tangled's web app shows them: 1 MB. */
    private const val MAX_SHOWN = 1_000_000L

    /** The lines of context git shows around a change. */
    private const val CONTEXT = 3
}
