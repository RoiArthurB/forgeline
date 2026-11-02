package fr.arthurbrugiere.forgeline.forge.sourcehut

import com.apollographql.apollo.api.Optional
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.RepoApi
import fr.arthurbrugiere.forgeline.core.forge.VersionOrder
import fr.arthurbrugiere.forgeline.core.forge.map
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.GitRefs
import fr.arthurbrugiere.forgeline.core.model.IssueQuery
import fr.arthurbrugiere.forgeline.core.model.IssueSummary
import fr.arthurbrugiere.forgeline.core.model.Readme
import fr.arthurbrugiere.forgeline.core.model.Release
import fr.arthurbrugiere.forgeline.core.model.ReleaseAsset
import fr.arthurbrugiere.forgeline.core.model.RepoDetails
import fr.arthurbrugiere.forgeline.core.model.RepoFile
import fr.arthurbrugiere.forgeline.core.model.RepoFileType
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.WorkflowRun
import fr.arthurbrugiere.forgeline.core.model.blobBaseUrl
import fr.arthurbrugiere.forgeline.core.model.rawBaseUrl
import fr.arthurbrugiere.forgeline.core.model.releaseUrl
import fr.arthurbrugiere.forgeline.core.model.withLatest
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import fr.arthurbrugiere.forgeline.forge.sourcehut.graphql.BlobQuery
import fr.arthurbrugiere.forgeline.forge.sourcehut.graphql.PathEntriesQuery
import fr.arthurbrugiere.forgeline.forge.sourcehut.graphql.RepoQuery
import fr.arthurbrugiere.forgeline.forge.sourcehut.graphql.RepoReferencesQuery
import fr.arthurbrugiere.forgeline.forge.sourcehut.graphql.RepoReleaseQuery
import fr.arthurbrugiere.forgeline.forge.sourcehut.graphql.RepoReleasesQuery
import fr.arthurbrugiere.forgeline.forge.sourcehut.graphql.RootEntriesQuery
import fr.arthurbrugiere.forgeline.forge.sourcehut.graphql.fragment.EntryPage
import fr.arthurbrugiere.forgeline.forge.sourcehut.graphql.fragment.TagRelease
import io.ktor.client.HttpClient

/**
 * A git.sr.ht repository, read with the person's token: its description, README, files, branches, and its tags as
 * releases, with the files attached to them, as SourceHut's refs page shows them. Tickets (todo.sr.ht), patches (by
 * email, on lists.sr.ht) and builds (builds.sr.ht) are other services: the details say so, and their tabs stay away.
 */
class SourceHutRepoApi(
    private val httpClient: HttpClient,
    private val forge: ForgeInstance = ForgeInstance.SourceHut,
) : RepoApi {

    override suspend fun repo(token: String?, id: RepoId): ForgeResult<RepoDetails> = forgeCall {
        when (val result = httpClient.srht(token, RepoQuery(id.owner.username, id.name)).then { located(id, it.user, it.user?.repository) }) {
            is ForgeResult.Failure -> result
            is ForgeResult.Success -> {
                val repo = result.value
                ForgeResult.Success(
                    RepoDetails(
                        id = id,
                        description = repo.description?.takeIf { it.isNotBlank() },
                        homepage = null,
                        topics = emptyList(),
                        stars = 0,
                        forks = 0,
                        watchers = 0,
                        language = null,
                        license = null,
                        defaultBranch = repo.HEAD?.name?.removePrefix(HEADS) ?: "master",
                        ownerAvatarUrl = null,
                        isFork = false,
                        isArchived = false,
                        pushedAt = instant(repo.updated),
                        hasActions = false,
                        hasIssues = false,
                        isEmpty = repo.HEAD == null,
                        listsConversations = false,
                    ),
                )
            }
        }
    }

    override suspend fun readme(token: String?, id: RepoId, ref: String?): ForgeResult<Readme?> = forgeCall {
        val root = when (val tree = entries(token, id, "", ref ?: "HEAD")) {
            is ForgeResult.Failure -> return@forgeCall if (tree.isMissing) ForgeResult.Success(null) else tree
            is ForgeResult.Success -> tree.value
        }
        val path = readmeOf(root.filter { it.mode.toFileType() == RepoFileType.FILE }.map { it.name }) ?: return@forgeCall ForgeResult.Success(null)
        when (val text = blob(token, id, path, ref ?: "HEAD")) {
            is ForgeResult.Failure -> text
            is ForgeResult.Success -> ForgeResult.Success(Readme(path, text.value))
        }
    }

    override suspend fun refs(token: String?, id: RepoId): ForgeResult<GitRefs> = forgeCall {
        val refs = pages { cursor ->
            httpClient.srht(token, RepoReferencesQuery(id.owner.username, id.name, Optional.presentIfNotNull(cursor)))
                .then { located(id, it.user, it.user?.repository) }
                .map { repo -> repo.references.results.map { it.name } to repo.references.cursor }
        }
        when (refs) {
            is ForgeResult.Failure -> refs
            is ForgeResult.Success -> {
                val names = refs.value
                ForgeResult.Success(
                    GitRefs(
                        branches = names.filter { it.startsWith(HEADS) }.map { it.removePrefix(HEADS) }.sortedWith(String.CASE_INSENSITIVE_ORDER),
                        tags = names.filter { it.startsWith(TAGS) }.map { it.removePrefix(TAGS) }.sortedWith(VersionOrder.reversed()),
                    ),
                )
            }
        }
    }

    override suspend fun contents(token: String?, id: RepoId, path: String, ref: String): ForgeResult<List<RepoFile>> = forgeCall {
        val folder = path.trim('/')
        when (val tree = entries(token, id, folder, ref)) {
            is ForgeResult.Failure -> tree
            is ForgeResult.Success -> ForgeResult.Success(
                tree.value
                    .map { entry -> RepoFile(if (folder.isEmpty()) entry.name else "$folder/${entry.name}", entry.name, entry.mode.toFileType(), 0) }
                    .sortedWith(compareBy<RepoFile> { it.type != RepoFileType.DIR }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }),
            )
        }
    }

    override suspend fun fileText(token: String?, id: RepoId, path: String, ref: String): ForgeResult<String> = forgeCall { blob(token, id, path.trim('/'), ref) }

    /** Every tag, newest first, with its message and files: what SourceHut shows as releases on a repository's refs page. */
    override suspend fun releases(token: String?, id: RepoId): ForgeResult<List<Release>> = forgeCall {
        val tags = pages { cursor ->
            httpClient.srht(token, RepoReleasesQuery(id.owner.username, id.name, Optional.presentIfNotNull(cursor)))
                .then { located(id, it.user, it.user?.repository) }
                .map { repo -> repo.references.results.map { it.tagRelease } to repo.references.cursor }
        }
        when (val refs = tags) {
            is ForgeResult.Failure -> refs
            is ForgeResult.Success -> ForgeResult.Success(
                refs.value.filter { it.name.startsWith(TAGS) }
                    .map { it.toRelease(id) }
                    .sortedWith(compareByDescending<Release> { it.publishedAt }.thenComparing({ it.tag }, VersionOrder.reversed()))
                    .withLatest(),
            )
        }
    }

    override suspend fun release(token: String?, id: RepoId, tag: String): ForgeResult<Release> = forgeCall {
        when (val result = httpClient.srht(token, RepoReleaseQuery(id.owner.username, id.name, "$TAGS$tag")).then { located(id, it.user, it.user?.repository) }) {
            is ForgeResult.Failure -> result
            is ForgeResult.Success -> result.value.reference?.tagRelease?.let { ForgeResult.Success(it.toRelease(id)) } ?: notFound("No tag $tag")
        }
    }

    private fun TagRelease.toRelease(id: RepoId): Release {
        val tag = name.removePrefix(TAGS)
        // An annotated tag carries a message and its tagger's time; a light one only points at a commit.
        val annotated = follow?.onTag
        return Release(
            tag = tag,
            name = tag,
            body = annotated?.message?.trim()?.takeIf { it.isNotEmpty() },
            publishedAt = instant(annotated?.tagger?.time ?: follow?.onCommit?.committer?.time),
            isPrerelease = false,
            author = null,
            assets = artifacts.results.map { ReleaseAsset(it.filename, it.size.toLong(), null, it.url) },
            tarUrl = "${id.webUrl}/archive/$tag.tar.gz",
            webUrl = id.releaseUrl(tag),
        )
    }

    override suspend fun issues(token: String?, id: RepoId, query: IssueQuery): ForgeResult<List<IssueSummary>> = ForgeResult.Failure(ForgeError.Unsupported)

    override suspend fun pullRequests(token: String?, id: RepoId, query: IssueQuery): ForgeResult<List<IssueSummary>> = ForgeResult.Failure(ForgeError.Unsupported)

    override suspend fun workflowRuns(token: String?, id: RepoId): ForgeResult<List<WorkflowRun>> = ForgeResult.Failure(ForgeError.Unsupported)

    override fun rawBaseUrl(id: RepoId, ref: String): String = id.rawBaseUrl(ref)

    override fun blobBaseUrl(id: RepoId, ref: String): String = id.blobBaseUrl(ref)

    /** The repository a query found, or a 404 for a person or repository SourceHut doesn't know. */
    private fun <R : Any> located(id: RepoId, user: Any?, repository: R?): ForgeResult<R> = when {
        user == null -> notFound("No user ${id.owner}")
        repository == null -> notFound("No repository ${id.fullName}")
        else -> ForgeResult.Success(repository)
    }

    /** What [page] gives from each page, a cursor after another, up to [MAX_PAGES] pages. */
    private inline fun <T> pages(page: (cursor: String?) -> ForgeResult<Pair<List<T>, String?>>): ForgeResult<List<T>> {
        val all = mutableListOf<T>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val (items, next) = when (val result = page(cursor)) {
                is ForgeResult.Failure -> return result
                is ForgeResult.Success -> result.value
            }
            all += items
            cursor = next ?: return ForgeResult.Success(all)
        }
        return ForgeResult.Success(all)
    }

    /** The entries of the folder at [path] (the root when empty) at [ref], a page at a time. */
    private suspend fun entries(token: String?, id: RepoId, path: String, ref: String): ForgeResult<List<EntryPage.Result>> = pages { cursor ->
        val after = Optional.presentIfNotNull(cursor)
        val page = if (path.isEmpty()) {
            httpClient.srht(token, RootEntriesQuery(id.owner.username, id.name, ref, after))
                .then { located(id, it.user, it.user?.repository) }
                .then { repo -> repo.revparse_single?.tree?.entries?.entryPage?.let { ForgeResult.Success(it) } ?: notFound("No ref $ref") }
        } else {
            httpClient.srht(token, PathEntriesQuery(id.owner.username, id.name, ref, path, after))
                .then { located(id, it.user, it.user?.repository) }
                .then { repo ->
                    val found = repo.path?.`object` ?: return@then notFound("No $path at $ref")
                    val tree = found.onTree ?: return@then ForgeResult.Failure(ForgeError.Http(400, "$path is a file"))
                    ForgeResult.Success(tree.entries.entryPage)
                }
        }
        page.map { it.results to it.cursor }
    }

    /** A file's text at [ref]: as the query gives it up to 128 KiB, and from its content's address beyond, or when binary. */
    private suspend fun blob(token: String?, id: RepoId, path: String, ref: String): ForgeResult<String> {
        val found = when (val result = httpClient.srht(token, BlobQuery(id.owner.username, id.name, ref, path)).then { located(id, it.user, it.user?.repository) }) {
            is ForgeResult.Failure -> return result
            is ForgeResult.Success -> result.value.path?.`object` ?: return notFound("No $path at $ref")
        }
        val text = found.onTextBlob
        val binary = found.onBinaryBlob
        return when {
            text != null && text.size <= MAX_INLINE_TEXT -> ForgeResult.Success(text.text)
            text != null -> httpClient.srhtContent(token, text.content)
            binary != null -> httpClient.srhtContent(token, binary.content)
            else -> ForgeResult.Failure(ForgeError.Http(400, "$path is not a file"))
        }
    }

    private val ForgeResult.Failure.isMissing: Boolean get() = (error as? ForgeError.Http)?.status == 404

    internal companion object {
        const val HEADS = "refs/heads/"
        const val TAGS = "refs/tags/"

        /** SourceHut's lists come 25 at a time: 40 pages show a folder or a repository's refs up to 1,000. */
        const val MAX_PAGES = 40

        /** What the `text` field holds at most: 128 KiB. */
        const val MAX_INLINE_TEXT = 128 * 1024

        /** The README a forge shows: README.md first, then any README.* or README. */
        fun readmeOf(files: List<String>): String? {
            val candidates = files.filter { it.substringBefore('.').equals("readme", ignoreCase = true) }
            return candidates.firstOrNull { it.equals("README.md", ignoreCase = true) } ?: candidates.minByOrNull { it.length }
        }

        /** git's mode, as SourceHut gives it: a number whose octal digits are the mode. */
        fun Int.toFileType(): RepoFileType = when (this and 0xF000) {
            0x4000 -> RepoFileType.DIR
            0xA000 -> RepoFileType.SYMLINK
            0xE000 -> RepoFileType.SUBMODULE
            else -> RepoFileType.FILE
        }
    }
}
