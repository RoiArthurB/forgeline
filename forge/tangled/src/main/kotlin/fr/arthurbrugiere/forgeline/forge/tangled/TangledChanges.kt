package fr.arthurbrugiere.forgeline.forge.tangled

import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ChangedFile
import fr.arthurbrugiere.forgeline.core.model.Commit
import fr.arthurbrugiere.forgeline.core.model.FileChange
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.tangled.git.DiffSrc
import org.tangled.git.FileDiff
import org.tangled.git.GetDiffQueryParams
import org.tangled.git.GetDiffResponse
import org.tangled.temp.git.GetBlobQueryParams
import org.tangled.temp.git.ListCommitsCommit
import org.tangled.temp.git.ListCommitsQueryParams
import sh.christian.ozone.api.Did

/** How much a change holds: lines added and taken away, files, commits. */
internal data class ChangeStats(val insertions: Int, val deletions: Int, val files: Int, val commits: Int)

/**
 * A change between two commits of a Tangled repository: its files, as a unified diff each, and its commits.
 *
 * The knot diffs (`org.tangled.git.getDiff`), but says only which lines changed, by their numbers on each side, as
 * Tangled's web app wants them (`web/src/lib/api/repoDiff.ts`, read 2026-11-17): the text of both sides is then read
 * from the index (`org.tangled.temp.git.getBlob`) and the diff written out as git writes it, hunk by hunk.
 */
internal object TangledChanges {
    /** The diff from [base] to [head] in [repo], [head] being in [headRepo] (a fork) where it isn't in [repo] itself. */
    suspend fun diff(knot: TangledKnot, repo: TicketRepo, base: String, head: String): ForgeResult<GetDiffResponse> = forgeCall {
        val host = repo.knot ?: return@forgeCall ForgeResult.Failure(fr.arthurbrugiere.forgeline.core.forge.ForgeError.Unsupported)
        asDid(repo.did).then { did -> knot.git(host).getDiff(GetDiffQueryParams(did, base, head)).toResult { it } }
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
    suspend fun stats(xrpc: TangledXrpc, knot: TangledKnot, repo: TicketRepo, patch: PatchRecordJson): ChangeStats? {
        val base = patch.base?.oid ?: return null
        val head = patch.head?.oid ?: return null
        return coroutineScope {
            val diff = async { (diff(knot, repo, base, head) as? ForgeResult.Success)?.value }
            val commits = async { (commits(xrpc, listOfNotNull(repo.did, patch.source?.repo), base, head) as? ForgeResult.Success)?.value?.size }
            val answer = diff.await() ?: return@coroutineScope null
            val stats = answer.stats
            ChangeStats(
                insertions = stats?.insertions?.toInt() ?: answer.diffs.sumOf { file -> file.added() },
                deletions = stats?.deletions?.toInt() ?: answer.diffs.sumOf { file -> file.removed() },
                files = stats?.filesChanged?.toInt() ?: answer.diffs.size,
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
     * [file]'s change as a unified diff, from its first `@@`: each of the knot's hunks pairs the lines of the two sides
     * that stay (0-based, `lhs` and `rhs`), names alone those only one side has, and calls "novel" the paired lines
     * whose text changed, which are written as taken away then added.
     */
    fun unifiedDiff(file: FileDiff, old: List<String>, new: List<String>): String = buildString {
        for (hunk in file.hunks) {
            val novelOld = hunk.novelLhs.toSet()
            val novelNew = hunk.novelRhs.toSet()
            val body = mutableListOf<String>()
            val removed = mutableListOf<String>()
            val added = mutableListOf<String>()
            fun flush() {
                body += removed
                body += added
                removed.clear()
                added.clear()
            }
            for (pair in hunk.lines) {
                val lhs = pair.lhs
                val rhs = pair.rhs
                when {
                    lhs != null && rhs != null && lhs !in novelOld && rhs !in novelNew -> {
                        flush()
                        body += " " + new.line(rhs)
                    }
                    else -> {
                        if (lhs != null) removed += "-" + old.line(lhs)
                        if (rhs != null) added += "+" + new.line(rhs)
                    }
                }
            }
            flush()
            val olds = hunk.lines.mapNotNull { it.lhs }
            val news = hunk.lines.mapNotNull { it.rhs }
            append("@@ -").append(start(olds)).append(',').append(olds.size)
            append(" +").append(start(news)).append(',').append(news.size).append(" @@\n")
            body.forEach { append(it).append('\n') }
        }
    }.trimEnd('\n')

    private fun start(lines: List<Long>): Long = lines.minOrNull()?.plus(1) ?: 0

    private fun List<String>.line(index: Long): String = getOrNull(index.toInt()).orEmpty()

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
}
