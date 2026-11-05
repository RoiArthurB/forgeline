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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.tangled.feed.CountStarsQueryParams
import org.tangled.repo.CountForksQueryParams
import org.tangled.temp.git.GetBlobQueryParams
import org.tangled.temp.git.GetTreeQueryParams
import org.tangled.temp.git.GetTreeTreeEntry
import org.tangled.temp.git.ListBranchesQueryParams
import org.tangled.temp.git.ListTagsQueryParams
import org.tangled.temp.repo.GetRepoByNameQueryParams
import sh.christian.ozone.api.Did
import java.util.concurrent.ConcurrentHashMap

/**
 * A Tangled repository, read from Tangled's index without an account: its description, README, files, branches and tags.
 * The owner's handle is resolved to a DID ([TangledIdentity]), the repository found by that DID and its name, and its
 * git content asked by the repository's own DID. Issues, pull requests, releases and CI aren't listed yet: the details
 * say so, and the tabs stay away.
 */
class TangledRepoApi(
    private val xrpc: TangledXrpc,
    private val identity: TangledIdentity,
    private val forge: ForgeInstance = ForgeInstance.Tangled,
) : RepoApi {

    /** Each repository's own DID and what its git says, once it was looked up: every file read needs the DID. */
    private val found = ConcurrentHashMap<RepoId, FoundRepo>()

    internal suspend fun lookUp(id: RepoId): ForgeResult<FoundRepo> {
        found[id]?.let { return ForgeResult.Success(it) }
        val owner = when (val did = identity.did(id.owner).then(::asDid)) {
            is ForgeResult.Failure -> return did
            is ForgeResult.Success -> did.value
        }
        return xrpc.index.getRepoByName(GetRepoByNameQueryParams(owner, id.name))
            .toResult { answer ->
                val record = answer.value.decodeAs<RepoRecordJson>()
                FoundRepo(record, record.repoDid(answer.uri.atUri), answer.git).also { repo -> found[id] = repo }
            }
    }

    /** The DID the repository's git content is asked by; Unsupported for one declared before repositories had their own. */
    private suspend fun repoDid(id: RepoId): ForgeResult<Did> = when (val repo = lookUp(id)) {
        is ForgeResult.Failure -> repo
        is ForgeResult.Success -> repo.value.repoDid?.let(::asDid) ?: ForgeResult.Failure(ForgeError.Unsupported)
    }

    override suspend fun repo(token: String?, id: RepoId): ForgeResult<RepoDetails> = forgeCall {
        val repo = when (val result = lookUp(id)) {
            is ForgeResult.Failure -> return@forgeCall result
            is ForgeResult.Success -> result.value
        }
        val did = repo.repoDid
        val (stars, forks, ownerAvatar) = coroutineScope {
            val ownerAvatar = async { (identity.did(id.owner) as? ForgeResult.Success)?.value?.let { identity.avatar(it) } }
            val stars = async { did?.let { count { xrpc.index.countStars(CountStarsQueryParams(did)).toResult { it.count } } } ?: 0 }
            val forks = async { did?.let { count { asDid(did).then { xrpc.index.countForks(CountForksQueryParams(it)).toResult { answer -> answer.count } } } } ?: 0 }
            Triple(stars.await(), forks.await(), ownerAvatar.await())
        }
        val git = repo.git
        ForgeResult.Success(
            RepoDetails(
                id = id,
                description = repo.record.description?.takeIf { it.isNotBlank() },
                homepage = repo.record.website?.takeIf { it.isNotBlank() },
                topics = repo.record.topics,
                stars = stars,
                forks = forks,
                watchers = 0,
                language = git?.languages?.maxByOrNull { it.size }?.name,
                license = null,
                defaultBranch = git?.defaultBranch ?: "main",
                ownerAvatarUrl = ownerAvatar,
                isFork = repo.record.source != null,
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
    private suspend fun count(ask: suspend () -> ForgeResult<Long>): Int = (forgeCall { ask() } as? ForgeResult.Success)?.value?.toInt() ?: 0

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
            val branches = async {
                names { cursor -> xrpc.index.listBranches(ListBranchesQueryParams(did, REF_PAGE, cursor)).toResult { page -> page.branches.map { it.name } to page.cursor } }
            }
            val tags = async {
                names { cursor -> xrpc.index.listTags(ListTagsQueryParams(did, REF_PAGE, cursor)).toResult { page -> page.tags.map { it.name } to page.cursor } }
            }
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
    private suspend fun names(page: suspend (cursor: String?) -> ForgeResult<Pair<List<String>, String?>>): ForgeResult<List<String>> {
        val all = mutableListOf<String>()
        var cursor: String? = null
        repeat(MAX_REF_PAGES) {
            val (names, next) = when (val result = page(cursor)) {
                is ForgeResult.Failure -> return result
                is ForgeResult.Success -> result.value
            }
            all += names
            cursor = next?.takeIf { it.isNotBlank() && names.isNotEmpty() } ?: return ForgeResult.Success(all)
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

    private suspend fun tree(id: RepoId, ref: String?, path: String): ForgeResult<List<GetTreeTreeEntry>> {
        val did = when (val result = repoDid(id)) {
            is ForgeResult.Failure -> return result
            is ForgeResult.Success -> result.value
        }
        return xrpc.index.getTree(GetTreeQueryParams(did, ref ?: "HEAD", path)).toResult { it.entries }
    }

    private suspend fun blob(id: RepoId, ref: String?, path: String): ForgeResult<String> {
        val did = when (val result = repoDid(id)) {
            is ForgeResult.Failure -> return result
            is ForgeResult.Success -> result.value
        }
        return xrpc.index.getBlob(GetBlobQueryParams(did, ref ?: "HEAD", path.trim('/'))).toResult { it.decodeToString() }
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
        /** 100 refs a page (the most Tangled gives): a repository with more than 2,000 branches or tags shows the first 2,000. */
        const val MAX_REF_PAGES = 20
        const val REF_PAGE = 100L

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
