package fr.arthurbrugiere.forgeline.forge.tangled

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
import fr.arthurbrugiere.forgeline.core.model.RepoDetails
import fr.arthurbrugiere.forgeline.core.model.RepoFile
import fr.arthurbrugiere.forgeline.core.model.RepoFileType
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoLanguage
import fr.arthurbrugiere.forgeline.core.model.WorkflowRun
import fr.arthurbrugiere.forgeline.core.model.blobBaseUrl
import fr.arthurbrugiere.forgeline.core.model.languageShares
import fr.arthurbrugiere.forgeline.core.model.rawBaseUrl
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

/**
 * A Tangled repository, read from Tangled's index without an account: its description, README, files, branches and tags.
 * The owner's handle is resolved to a DID ([TangledIdentity]), the repository found by that DID and its name, and its
 * git content asked by the repository's own DID. Issues, pull requests, releases and CI aren't listed yet: the details
 * say so, and the tabs stay away.
 */
class TangledRepoApi(
    private val httpClient: HttpClient,
    private val identity: TangledIdentity,
    private val forge: ForgeInstance = ForgeInstance.Tangled,
) : RepoApi {

    /** Each repository's own DID and what its git says, once it was looked up: every file read needs the DID. */
    private val found = ConcurrentHashMap<RepoId, RepoByNameJson>()

    internal suspend fun lookUp(id: RepoId): ForgeResult<RepoByNameJson> {
        found[id]?.let { return ForgeResult.Success(it) }
        val owner = when (val did = identity.did(id.owner)) {
            is ForgeResult.Failure -> return did
            is ForgeResult.Success -> did.value
        }
        return httpClient.xrpc("org.tangled.temp.repo.getRepoByName", "owner" to owner, "name" to id.name)
            .toResult { body<RepoByNameJson>().also { found[id] = it } }
    }

    /** The DID the repository's git content is asked by; Unsupported for one declared before repositories had their own. */
    private suspend fun repoDid(id: RepoId): ForgeResult<String> = when (val repo = lookUp(id)) {
        is ForgeResult.Failure -> repo
        is ForgeResult.Success -> repo.value.value.repoDid?.let { ForgeResult.Success(it) } ?: ForgeResult.Failure(ForgeError.Unsupported)
    }

    override suspend fun repo(token: String?, id: RepoId): ForgeResult<RepoDetails> = forgeCall {
        val repo = when (val result = lookUp(id)) {
            is ForgeResult.Failure -> return@forgeCall result
            is ForgeResult.Success -> result.value
        }
        val did = repo.value.repoDid
        val (stars, forks) = coroutineScope {
            val stars = async { did?.let { count("org.tangled.feed.countStars", it) } ?: 0 }
            val forks = async { did?.let { count("org.tangled.repo.countForks", it) } ?: 0 }
            stars.await() to forks.await()
        }
        val git = repo.git
        ForgeResult.Success(
            RepoDetails(
                id = id,
                description = repo.value.description?.takeIf { it.isNotBlank() },
                homepage = repo.value.website?.takeIf { it.isNotBlank() },
                topics = repo.value.topics,
                stars = stars,
                forks = forks,
                watchers = 0,
                language = git?.languages?.maxByOrNull { it.size }?.name,
                license = null,
                defaultBranch = git?.defaultBranch ?: "main",
                ownerAvatarUrl = null,
                isFork = repo.value.source != null,
                isArchived = false,
                pushedAt = null,
                hasActions = false,
                hasIssues = false,
                isEmpty = git?.isEmpty == true,
                listsConversations = false,
                listsReleases = false,
            ),
        )
    }

    /** A count Tangled keeps for the repository; 0 where it couldn't say, which only leaves a number out. */
    private suspend fun count(nsid: String, subject: String): Int =
        (forgeCall { httpClient.xrpc(nsid, "subject" to subject).toResult { body<CountJson>().count } } as? ForgeResult.Success)?.value ?: 0

    override suspend fun languages(token: String?, id: RepoId): ForgeResult<List<RepoLanguage>> = when (val repo = forgeCall { lookUp(id) }) {
        is ForgeResult.Failure -> repo
        is ForgeResult.Success -> ForgeResult.Success(languageShares(repo.value.git?.languages.orEmpty().associate { it.name to it.size.toDouble() }))
    }

    override suspend fun readme(token: String?, id: RepoId, ref: String?): ForgeResult<Readme?> = forgeCall {
        val root = when (val tree = tree(id, ref, "")) {
            is ForgeResult.Failure -> return@forgeCall if (tree.isMissing) ForgeResult.Success(null) else tree
            is ForgeResult.Success -> tree.value
        }
        val path = readmeOf(root.filter { it.mode.toFileType() == RepoFileType.FILE }.map { it.name }) ?: return@forgeCall ForgeResult.Success(null)
        when (val text = blob(id, ref, path)) {
            is ForgeResult.Failure -> text
            is ForgeResult.Success -> ForgeResult.Success(Readme(path, text.value))
        }
    }

    override suspend fun refs(token: String?, id: RepoId): ForgeResult<GitRefs> = forgeCall {
        val did = when (val result = repoDid(id)) {
            is ForgeResult.Failure -> return@forgeCall result
            is ForgeResult.Success -> result.value
        }
        coroutineScope {
            val branches = async { names(did, "org.tangled.temp.git.listBranches") { it.branches } }
            val tags = async { names(did, "org.tangled.temp.git.listTags") { it.tags } }
            when (val b = branches.await()) {
                is ForgeResult.Failure -> b
                is ForgeResult.Success -> when (val t = tags.await()) {
                    is ForgeResult.Failure -> t
                    is ForgeResult.Success -> ForgeResult.Success(GitRefs(b.value.sortedWith(String.CASE_INSENSITIVE_ORDER), t.value.sortedWith(VersionOrder.reversed())))
                }
            }
        }
    }

    /** Every name a ref list holds, a page of 100 at a time, up to [MAX_REF_PAGES] pages. */
    private suspend fun names(did: String, nsid: String, of: (RefsJson) -> List<NamedRefJson>): ForgeResult<List<String>> {
        val all = mutableListOf<String>()
        var cursor: String? = null
        repeat(MAX_REF_PAGES) {
            val page = when (val result = httpClient.xrpc(nsid, "repo" to did, "limit" to "100", "cursor" to cursor).toResult { body<RefsJson>() }) {
                is ForgeResult.Failure -> return result
                is ForgeResult.Success -> result.value
            }
            all += of(page).map { it.name }
            cursor = page.cursor?.takeIf { it.isNotBlank() && of(page).isNotEmpty() } ?: return ForgeResult.Success(all)
        }
        return ForgeResult.Success(all)
    }

    override suspend fun contents(token: String?, id: RepoId, path: String, ref: String): ForgeResult<List<RepoFile>> = forgeCall {
        val folder = path.trim('/')
        when (val tree = tree(id, ref, folder)) {
            is ForgeResult.Failure -> tree
            is ForgeResult.Success -> ForgeResult.Success(
                tree.value
                    .map { RepoFile(if (folder.isEmpty()) it.name else "$folder/${it.name}", it.name, it.mode.toFileType(), it.size) }
                    .sortedWith(compareBy<RepoFile> { it.type != RepoFileType.DIR }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }),
            )
        }
    }

    override suspend fun fileText(token: String?, id: RepoId, path: String, ref: String): ForgeResult<String> = forgeCall { blob(id, ref, path) }

    private suspend fun tree(id: RepoId, ref: String?, path: String): ForgeResult<List<TreeEntryJson>> {
        val did = when (val result = repoDid(id)) {
            is ForgeResult.Failure -> return result
            is ForgeResult.Success -> result.value
        }
        return httpClient.xrpc("org.tangled.temp.git.getTree", "repo" to did, "ref" to ref, "path" to path.takeIf { it.isNotEmpty() })
            .toResult { body<TreeJson>().entries }
    }

    private suspend fun blob(id: RepoId, ref: String?, path: String): ForgeResult<String> {
        val did = when (val result = repoDid(id)) {
            is ForgeResult.Failure -> return result
            is ForgeResult.Success -> result.value
        }
        return httpClient.xrpc("org.tangled.temp.git.getBlob", "repo" to did, "ref" to ref, "path" to path.trim('/')).toResult { bodyAsText() }
    }

    private val ForgeResult.Failure.isMissing: Boolean get() = (error as? ForgeError.Http)?.status == 404

    override suspend fun issues(token: String?, id: RepoId, query: IssueQuery): ForgeResult<List<IssueSummary>> = ForgeResult.Failure(ForgeError.Unsupported)

    override suspend fun pullRequests(token: String?, id: RepoId, query: IssueQuery): ForgeResult<List<IssueSummary>> = ForgeResult.Failure(ForgeError.Unsupported)

    override suspend fun releases(token: String?, id: RepoId): ForgeResult<List<Release>> = ForgeResult.Failure(ForgeError.Unsupported)

    override suspend fun release(token: String?, id: RepoId, tag: String): ForgeResult<Release> = ForgeResult.Failure(ForgeError.Unsupported)

    override suspend fun workflowRuns(token: String?, id: RepoId): ForgeResult<List<WorkflowRun>> = ForgeResult.Failure(ForgeError.Unsupported)

    override fun rawBaseUrl(id: RepoId, ref: String): String = id.rawBaseUrl(ref)

    override fun blobBaseUrl(id: RepoId, ref: String): String = id.blobBaseUrl(ref)

    internal companion object {
        /** 100 refs a page: a repository with more than 2,000 branches or tags shows the first 2,000. */
        const val MAX_REF_PAGES = 20

        /** The README a forge shows: README.md first, then any README.* or README. */
        fun readmeOf(files: List<String>): String? {
            val candidates = files.filter { it.substringBefore('.').equals("readme", ignoreCase = true) }
            return candidates.firstOrNull { it.equals("README.md", ignoreCase = true) } ?: candidates.minByOrNull { it.length }
        }

        fun String.toFileType(): RepoFileType = when (trimStart('0')) {
            "40000" -> RepoFileType.DIR
            "120000" -> RepoFileType.SYMLINK
            "160000" -> RepoFileType.SUBMODULE
            else -> RepoFileType.FILE
        }
    }
}
