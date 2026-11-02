package fr.arthurbrugiere.forgeline.forge.sourcehut

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.RepoApi
import fr.arthurbrugiere.forgeline.core.forge.VersionOrder
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
        when (val result = repository(token, id, "name description visibility updated HEAD { name }")) {
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
                        defaultBranch = repo.head?.name?.removePrefix(HEADS) ?: "master",
                        ownerAvatarUrl = null,
                        isFork = false,
                        isArchived = false,
                        pushedAt = instant(repo.updated),
                        hasActions = false,
                        hasIssues = false,
                        isEmpty = repo.head == null,
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
        val path = readmeOf(root.filter { it.mode.toFileType() == RepoFileType.FILE }.mapNotNull { it.name }) ?: return@forgeCall ForgeResult.Success(null)
        when (val text = blob(token, id, path, ref ?: "HEAD")) {
            is ForgeResult.Failure -> text
            is ForgeResult.Success -> ForgeResult.Success(Readme(path, text.value))
        }
    }

    override suspend fun refs(token: String?, id: RepoId): ForgeResult<GitRefs> = forgeCall {
        when (val refs = references(token, id, "name")) {
            is ForgeResult.Failure -> refs
            is ForgeResult.Success -> {
                val names = refs.value.map { it.name }
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
                    .mapNotNull { entry -> entry.name?.let { RepoFile(if (folder.isEmpty()) it else "$folder/$it", it, entry.mode.toFileType(), 0) } }
                    .sortedWith(compareBy<RepoFile> { it.type != RepoFileType.DIR }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }),
            )
        }
    }

    override suspend fun fileText(token: String?, id: RepoId, path: String, ref: String): ForgeResult<String> = forgeCall { blob(token, id, path.trim('/'), ref) }

    /** Every tag, newest first, with its message and files: what SourceHut shows as releases on a repository's refs page. */
    override suspend fun releases(token: String?, id: RepoId): ForgeResult<List<Release>> = forgeCall {
        when (val refs = references(token, id, "name $RELEASE_FIELDS")) {
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
        when (val result = repository(token, id, "reference(name: \$ref) { name $RELEASE_FIELDS }", Variable("ref", "String!", "$TAGS$tag"))) {
            is ForgeResult.Failure -> result
            is ForgeResult.Success -> result.value.reference?.let { ForgeResult.Success(it.toRelease(id)) } ?: notFound("No tag $tag")
        }
    }

    private fun ReferenceJson.toRelease(id: RepoId): Release {
        val tag = name.removePrefix(TAGS)
        // An annotated tag carries a message and its tagger's time; a light one only points at a commit.
        val annotated = follow?.type == "TAG"
        return Release(
            tag = tag,
            name = tag,
            body = follow?.message?.trim()?.takeIf { annotated && it.isNotEmpty() },
            publishedAt = instant(follow?.tagger?.time ?: follow?.committer?.time),
            isPrerelease = false,
            author = null,
            assets = artifacts?.results.orEmpty().map { ReleaseAsset(it.filename, it.size, null, it.url) },
            tarUrl = "${id.webUrl}/archive/$tag.tar.gz",
            webUrl = id.releaseUrl(tag),
        )
    }

    override suspend fun issues(token: String?, id: RepoId, query: IssueQuery): ForgeResult<List<IssueSummary>> = ForgeResult.Failure(ForgeError.Unsupported)

    override suspend fun pullRequests(token: String?, id: RepoId, query: IssueQuery): ForgeResult<List<IssueSummary>> = ForgeResult.Failure(ForgeError.Unsupported)

    override suspend fun workflowRuns(token: String?, id: RepoId): ForgeResult<List<WorkflowRun>> = ForgeResult.Failure(ForgeError.Unsupported)

    override fun rawBaseUrl(id: RepoId, ref: String): String = id.rawBaseUrl(ref)

    override fun blobBaseUrl(id: RepoId, ref: String): String = id.blobBaseUrl(ref)

    /** A variable of a query: its [name], its GraphQL [type] as the schema declares it, and its [value]. */
    private class Variable(val name: String, val type: String, val value: Any?)

    /** The repository [id], with the [fields] asked of it (and the [variables] they use, beside `$owner` and `$name`). */
    private suspend fun repository(token: String?, id: RepoId, fields: String, vararg variables: Variable): ForgeResult<RepositoryJson> {
        val declared = variables.joinToString("") { ", \$${it.name}: ${it.type}" }
        val query = "query(\$owner: String!, \$name: String!$declared) { user(username: \$owner) { repository(name: \$name) { $fields } } }"
        val values = mapOf("owner" to id.owner.username, "name" to id.name) + variables.associate { it.name to it.value }
        return when (val result = httpClient.srht(token, query, values, UserData.serializer())) {
            is ForgeResult.Failure -> result
            is ForgeResult.Success -> {
                val user = result.value.user ?: return notFound("No user ${id.owner}")
                user.repository?.let { ForgeResult.Success(it) } ?: notFound("No repository ${id.fullName}")
            }
        }
    }

    /** Every ref of [id], with the [fields] asked of each, a page at a time, up to [MAX_PAGES] pages. */
    private suspend fun references(token: String?, id: RepoId, fields: String): ForgeResult<List<ReferenceJson>> {
        val all = mutableListOf<ReferenceJson>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val page = when (val result = repository(token, id, "references(cursor: \$cursor) { results { $fields } cursor }", Variable("cursor", "Cursor", cursor))) {
                is ForgeResult.Failure -> return result
                is ForgeResult.Success -> result.value.references ?: return ForgeResult.Success(all)
            }
            all += page.results
            cursor = page.cursor ?: return ForgeResult.Success(all)
        }
        return ForgeResult.Success(all)
    }

    /** The entries of the folder at [path] (the root when empty) at [ref], a page at a time, up to [MAX_PAGES] pages. */
    private suspend fun entries(token: String?, id: RepoId, path: String, ref: String): ForgeResult<List<TreeEntryJson>> {
        val all = mutableListOf<TreeEntryJson>()
        var cursor: String? = null
        val listing = "entries(cursor: \$cursor) { results { name mode } cursor }"
        repeat(MAX_PAGES) {
            val result = if (path.isEmpty()) {
                repository(token, id, "revparse_single(revspec: \$rev) { tree { $listing } }", Variable("rev", "String!", ref), Variable("cursor", "Cursor", cursor))
            } else {
                repository(token, id, "path(revspec: \$rev, path: \$path) { object { type ... on Tree { $listing } } }", Variable("rev", "String!", ref), Variable("path", "String!", path), Variable("cursor", "Cursor", cursor))
            }
            val page = when (result) {
                is ForgeResult.Failure -> return result
                is ForgeResult.Success -> if (path.isEmpty()) {
                    result.value.commit?.tree?.entries ?: return notFound("No ref $ref")
                } else {
                    val found = result.value.path?.`object` ?: return notFound("No $path at $ref")
                    if (found.type != "TREE") return ForgeResult.Failure(ForgeError.Http(400, "$path is a file"))
                    found.entries ?: return ForgeResult.Success(all)
                }
            }
            all += page.results
            cursor = page.cursor ?: return ForgeResult.Success(all)
        }
        return ForgeResult.Success(all)
    }

    /** A file's text at [ref]: as the query gives it up to 128 KiB, and from its content's address beyond, or when binary. */
    private suspend fun blob(token: String?, id: RepoId, path: String, ref: String): ForgeResult<String> {
        val fields = "path(revspec: \$rev, path: \$path) { object { type ... on TextBlob { size text content } ... on BinaryBlob { size content } } }"
        val found = when (val result = repository(token, id, fields, Variable("rev", "String!", ref), Variable("path", "String!", path))) {
            is ForgeResult.Failure -> return result
            is ForgeResult.Success -> result.value.path?.`object` ?: return notFound("No $path at $ref")
        }
        if (found.type != "BLOB") return ForgeResult.Failure(ForgeError.Http(400, "$path is not a file"))
        val text = found.text
        return if (text != null && (found.size ?: 0) <= MAX_INLINE_TEXT) {
            ForgeResult.Success(text)
        } else {
            found.content?.let { httpClient.srhtContent(token, it) } ?: ForgeResult.Failure(ForgeError.Unreadable)
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

        const val RELEASE_FIELDS = "follow { type ... on Tag { message tagger { time } } ... on Commit { committer { time } } } artifacts { results { filename size url } }"

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
