package fr.arthurbrugiere.forgeline.forge.bitbucket

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.RepoApi
import fr.arthurbrugiere.forgeline.core.forge.map
import fr.arthurbrugiere.forgeline.core.model.GitRefs
import fr.arthurbrugiere.forgeline.core.model.IssueQuery
import fr.arthurbrugiere.forgeline.core.model.IssueSummary
import fr.arthurbrugiere.forgeline.core.model.Readme
import fr.arthurbrugiere.forgeline.core.model.Release
import fr.arthurbrugiere.forgeline.core.model.RepoAccess
import fr.arthurbrugiere.forgeline.core.model.RepoDetails
import fr.arthurbrugiere.forgeline.core.model.RepoFile
import fr.arthurbrugiere.forgeline.core.model.RepoFileType
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoRights
import fr.arthurbrugiere.forgeline.core.model.WorkflowRun
import fr.arthurbrugiere.forgeline.core.model.blobBaseUrl
import fr.arthurbrugiere.forgeline.core.model.rawBaseUrl
import fr.arthurbrugiere.forgeline.core.model.releaseUrl
import fr.arthurbrugiere.forgeline.core.model.withLatest
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.PaginatedBranches
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.PaginatedPipelines
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.PaginatedPullrequests
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.PaginatedRepositoryPermissions
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.PaginatedTags
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.PaginatedTreeentries
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.Repository
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.Tag
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable

/**
 * A Bitbucket repository: its files, branches and tags, its pull requests and its Pipelines. Bitbucket has no issues
 * since 2026 (they moved to Jira), so a repository lists none, and no releases: its tags stand for them, as Tangled's
 * and SourceHut's do.
 */
class BitbucketRepoApi(
    private val http: HttpClient,
    private val people: BitbucketPeople = BitbucketPeople(),
) : RepoApi {
    override suspend fun repo(token: String?, id: RepoId): ForgeResult<RepoDetails> = repoAndRights(token, id).map { it.first }

    override suspend fun repoAndRights(token: String?, id: RepoId): ForgeResult<Pair<RepoDetails, RepoRights?>> = forgeCall {
        coroutineScope {
            // Counted apart: a repository's answer doesn't say how many forks, watchers or open pull requests it has.
            val forks = async { count(token, *id.api("forks")) }
            val watchers = async { count(token, *id.api("watchers")) }
            val pulls = async { count(token, *id.api("pullrequests"), query = listOf("state" to "OPEN")) }
            val rights = async { token?.let { rights(it, id) } }
            http.bitbucket(token, *id.api()).toResult { body<Repository>() }.map { repository ->
                repository.toDetails(repository.repoId() ?: id, forks.await() ?: 0, watchers.await() ?: 0, pulls.await()) to rights.await()
            }
        }
    }

    /** How many a paged list holds, from the `size` of a one-item page; null when it can't be told. */
    private suspend fun count(token: String?, vararg segments: String, query: List<Pair<String, String>> = emptyList()): Int? = try {
        http.bitbucket(token, *segments, query = query + listOf("pagelen" to "1", "fields" to "size"))
            .takeIf { it.status == HttpStatusCode.OK }?.body<Sized>()?.size
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A count is a nicety: the repository opens without it.
        null
    }

    @Serializable
    private data class Sized(val size: Int? = null)

    /** What [token]'s account may do in [id]: Bitbucket's admin, write or read, as the workspace says. */
    internal suspend fun rights(token: String, id: RepoId): RepoRights? = try {
        val answer = http.bitbucket(
            token, "user", "workspaces", id.owner, "permissions", "repositories",
            query = listOf("q" to "repository.full_name=${bbql(id.fullName)}"),
        )
        if (answer.status != HttpStatusCode.OK) {
            null
        } else {
            val permission = answer.body<PaginatedRepositoryPermissions>().propertyValues.orEmpty().firstOrNull()?.permission
            RepoRights(
                when (permission) {
                    "admin" -> RepoAccess.ADMIN
                    "write" -> RepoAccess.WRITE
                    else -> RepoAccess.NONE
                },
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    override suspend fun readme(token: String?, id: RepoId, ref: String?): ForgeResult<Readme?> = forgeCall {
        val at = ref ?: when (val details = http.bitbucket(token, *id.api()).toResult { body<Repository>() }) {
            is ForgeResult.Failure -> return@forgeCall details
            // An empty repository has nothing to read.
            is ForgeResult.Success -> details.value.mainbranch?.name ?: return@forgeCall ForgeResult.Success(null)
        }
        val root = when (val entries = entries(token, id, "", at)) {
            is ForgeResult.Failure -> return@forgeCall entries
            is ForgeResult.Success -> entries.value
        }
        val file = root.filter { it.type == RepoFileType.FILE && it.name.startsWith("readme", ignoreCase = true) }
            .minByOrNull { README_ORDER.indexOf(it.name.lowercase()).let { place -> if (place == -1) README_ORDER.size else place } }
            ?: return@forgeCall ForgeResult.Success(null)
        fileText(token, id, file.path, at).map { Readme(file.path, it) }
    }

    override suspend fun refs(token: String?, id: RepoId): ForgeResult<GitRefs> = forgeCall {
        coroutineScope {
            val branches = async { all(token, *id.api("refs", "branches"), query = listOf("sort" to "name")) { body<PaginatedBranches>().let { page -> page.propertyValues.orEmpty().mapNotNull { it.name } to page.next } } }
            val tags = async { all(token, *id.api("refs", "tags"), query = listOf("sort" to "-target.date")) { body<PaginatedTags>().let { page -> page.propertyValues.orEmpty().mapNotNull { it.name } to page.next } } }
            when (val b = branches.await()) {
                is ForgeResult.Failure -> b
                is ForgeResult.Success -> tags.await().map { GitRefs(b.value.sortedWith(String.CASE_INSENSITIVE_ORDER), it) }
            }
        }
    }

    /** Every page of a list, up to [MAX_PAGES] of a hundred: [read] gives a page's items and its `next`. */
    private suspend fun <T> all(
        token: String?,
        vararg segments: String,
        query: List<Pair<String, String>> = emptyList(),
        read: suspend io.ktor.client.statement.HttpResponse.() -> Pair<List<T>, String?>,
    ): ForgeResult<List<T>> {
        val items = mutableListOf<T>()
        var answer = http.bitbucket(token, *segments, query = query + ("pagelen" to "100"))
        repeat(MAX_PAGES) {
            if (answer.status != HttpStatusCode.OK) return answer.failure()
            val (page, next) = answer.read()
            items += page
            next ?: return ForgeResult.Success(items)
            answer = http.bitbucketAt(token, next)
        }
        return ForgeResult.Success(items)
    }

    override suspend fun contents(token: String?, id: RepoId, path: String, ref: String): ForgeResult<List<RepoFile>> = forgeCall {
        entries(token, id, path, ref).map { files -> files.sortedWith(compareBy({ it.type != RepoFileType.DIR }, { it.name.lowercase() })) }
    }

    /** The entries of the folder at [path] of [ref]: Bitbucket lists a folder at its address ending with `/`. */
    private suspend fun entries(token: String?, id: RepoId, path: String, ref: String): ForgeResult<List<RepoFile>> =
        all(token, *id.api("src", ref, *pathSegments(path), "")) {
            val page = body<PaginatedTreeentries>()
            page.propertyValues.orEmpty().mapNotNull { entry ->
                val entryPath = entry.path ?: return@mapNotNull null
                val type = when {
                    entry.type == "commit_directory" -> RepoFileType.DIR
                    "subrepository" in entry.attributes.orEmpty() -> RepoFileType.SUBMODULE
                    "link" in entry.attributes.orEmpty() -> RepoFileType.SYMLINK
                    else -> RepoFileType.FILE
                }
                RepoFile(entryPath, entryPath.substringAfterLast('/'), type, entry.propertySize ?: 0)
            } to page.next
        }

    override suspend fun fileText(token: String?, id: RepoId, path: String, ref: String): ForgeResult<String> = forgeCall {
        http.bitbucket(token, *id.api("src", ref, *pathSegments(path)), accept = "*/*").toResult { bodyAsText() }
    }

    /** Bitbucket has no issues any more: a repository has none to list. */
    override suspend fun issues(token: String?, id: RepoId, query: IssueQuery): ForgeResult<List<IssueSummary>> = ForgeResult.Success(emptyList())

    override suspend fun pullRequests(token: String?, id: RepoId, query: IssueQuery): ForgeResult<List<IssueSummary>> = forgeCall {
        val states = if (query.open) listOf("OPEN") else listOf("MERGED", "DECLINED", "SUPERSEDED")
        val params = states.map { "state" to it } + listOfNotNull(
            "sort" to "-created_on",
            "pagelen" to "${IssueQuery.PAGE_SIZE}",
            "page" to "${query.page}",
            PULL_LIST_FIELDS,
            query.text.takeIf { it.isNotBlank() }?.let { "q" to "(title ~ ${bbql(it.trim())} OR description ~ ${bbql(it.trim())})" },
        )
        http.bitbucket(token, *id.api("pullrequests"), query = params).toResult { body<PaginatedPullrequests>().propertyValues.orEmpty().map { it.toSummary(people) } }
    }

    override suspend fun releases(token: String?, id: RepoId): ForgeResult<List<Release>> = forgeCall {
        http.bitbucket(token, *id.api("refs", "tags"), query = listOf("sort" to "-target.date", "pagelen" to "50"))
            .toResult { body<PaginatedTags>().propertyValues.orEmpty().mapNotNull { it.toRelease(id) }.withLatest() }
    }

    override suspend fun release(token: String?, id: RepoId, tag: String): ForgeResult<Release> = forgeCall {
        val answer = http.bitbucket(token, *id.api("refs", "tags", tag))
        if (answer.status != HttpStatusCode.OK) return@forgeCall answer.failure()
        answer.body<Tag>().toRelease(id)?.let { ForgeResult.Success(it) } ?: ForgeResult.Failure(ForgeError.Unreadable)
    }

    /** A tag, as a release: its message (an annotated tag's) says what it is, and Bitbucket serves its files as archives. */
    private fun Tag.toRelease(id: RepoId): Release? {
        val tag = name ?: return null
        return Release(
            tag = tag,
            name = tag,
            body = message?.trim()?.takeIf { it.isNotEmpty() && it != target?.message?.trim() },
            publishedAt = bitbucketInstant(date) ?: bitbucketInstant(target?.date),
            isPrerelease = false,
            author = people.user(tagger?.user ?: target?.author?.user),
            zipUrl = "${id.webUrl}/get/$tag.zip",
            tarUrl = "${id.webUrl}/get/$tag.tar.gz",
            webUrl = id.releaseUrl(tag),
        )
    }

    override suspend fun workflowRuns(token: String?, id: RepoId): ForgeResult<List<WorkflowRun>> = forgeCall {
        http.bitbucket(token, *id.api("pipelines"), query = listOf("sort" to "-created_on", "pagelen" to "30"))
            .toResult { body<PaginatedPipelines>().propertyValues.orEmpty().map { it.toRun(id, people) } }
    }

    override fun rawBaseUrl(id: RepoId, ref: String): String = id.rawBaseUrl(ref)

    override fun blobBaseUrl(id: RepoId, ref: String): String = id.blobBaseUrl(ref)

    private companion object {
        const val MAX_PAGES = 10

        /** Which README is read when a repository has several, as Bitbucket's own page picks. */
        val README_ORDER = listOf("readme.md", "readme.markdown", "readme.mkd", "readme", "readme.txt", "readme.rst")
    }
}
