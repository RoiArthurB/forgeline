package fr.arthurbrugiere.forgeline.forge.bitbucket

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.UserApi
import fr.arthurbrugiere.forgeline.core.forge.map
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.UserProfile
import fr.arthurbrugiere.forgeline.core.model.UserSummary
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.Account
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.PaginatedRepositories
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.PaginatedWorkspaceAccess
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.Workspace
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * People and workspaces on Bitbucket. A workspace (a person's own, or a team's) holds repositories and is named by
 * its slug; a person is named by their nickname ([BitbucketPeople]). Bitbucket has no followers, no stars and no
 * contribution graph: none are listed, and following is Unsupported.
 */
class BitbucketUserApi(
    private val http: HttpClient,
    private val people: BitbucketPeople = BitbucketPeople(),
) : UserApi {
    /** A workspace by its slug, else a person Forgeline saw by their nickname (or a UUID). */
    override suspend fun user(token: String?, login: String): ForgeResult<UserProfile> = forgeCall {
        val uuid = people.uuidOf(login)
        if (uuid == null || (' ' !in login && !login.startsWith("{"))) {
            val workspace = http.bitbucket(token, "workspaces", login)
            if (workspace.status.isSuccess()) return@forgeCall ForgeResult.Success(workspace.body<Workspace>().toProfile(login))
            if (uuid == null) return@forgeCall workspace.failure()
        }
        http.bitbucket(token, "users", uuid).toResult { body<Account>().toProfile() }
    }

    private fun Workspace.toProfile(login: String) = UserProfile(
        login = slug ?: login,
        name = name?.takeIf { it != slug },
        avatarUrl = links?.avatar?.href,
        bio = null,
        company = null,
        location = null,
        website = null,
        followers = 0,
        following = 0,
        publicRepos = 0,
        // A person's own workspace stands for them; any other is a team's.
        isOrganization = isPersonal != true,
        createdAt = bitbucketInstant(createdOn),
    )

    private fun Account.toProfile(): UserProfile {
        val user = people.user(this)
        return UserProfile(
            login = user?.login ?: uuid.orEmpty(),
            name = user?.name,
            avatarUrl = user?.avatarUrl,
            bio = null,
            company = null,
            location = null,
            website = null,
            followers = 0,
            following = 0,
            publicRepos = 0,
            isOrganization = false,
            createdAt = bitbucketInstant(createdOn),
        )
    }

    /** The repositories of the workspace [login], or of a person's own (which Bitbucket addresses by their UUID). */
    override suspend fun repos(token: String?, login: String, mine: Boolean, organization: Boolean): ForgeResult<List<RepoSummary>> = forgeCall {
        val workspace = if (' ' in login || login.startsWith("{")) people.uuidOf(login) ?: login else login
        workspaceRepos(token, workspace, pages = 3)
    }

    private suspend fun workspaceRepos(token: String?, workspace: String, pages: Int, query: List<Pair<String, String>> = emptyList()): ForgeResult<List<RepoSummary>> {
        val all = mutableListOf<RepoSummary>()
        var page = 1
        while (page <= pages) {
            val answer = http.bitbucket(token, "repositories", workspace, query = query + listOf("sort" to "-updated_on", "pagelen" to "100", "page" to "$page"))
            if (!answer.status.isSuccess()) return if (all.isEmpty()) answer.failure() else ForgeResult.Success(all)
            val read = answer.body<PaginatedRepositories>()
            all += read.propertyValues.orEmpty().mapNotNull { it.toSummary() }
            page = nextPageOf(read.next) ?: break
        }
        return ForgeResult.Success(all)
    }

    /** No one stars on Bitbucket. */
    override suspend fun starred(token: String?, login: String): ForgeResult<List<RepoSummary>> = ForgeResult.Success(emptyList())

    /** Every repository of every workspace the person is in, the most recently updated first. */
    override suspend fun reachableRepos(token: String): ForgeResult<List<RepoSummary>> = forgeCall {
        when (val workspaces = workspaces(token)) {
            is ForgeResult.Failure -> workspaces
            is ForgeResult.Success -> coroutineScope {
                val lists = workspaces.value.map { slug -> async { workspaceRepos(token, slug, pages = 10) } }.awaitAll()
                lists.firstOrNull { it is ForgeResult.Failure }?.takeIf { lists.all { list -> list is ForgeResult.Failure } }?.let { return@coroutineScope it }
                ForgeResult.Success(lists.filterIsInstance<ForgeResult.Success<List<RepoSummary>>>().flatMap { it.value }.distinctBy { it.id }.sortedByDescending { it.updatedAt }.take(MAX_REACHABLE))
            }
        }
    }

    /** The workspaces of the person [token] signs in, by slug: their own, and the teams they are a member of. */
    internal suspend fun workspaces(token: String): ForgeResult<List<String>> = workspaceAccess(token).map { access -> access.map { it.first } }

    private suspend fun workspaceAccess(token: String): ForgeResult<List<Pair<String, String?>>> {
        val answer = http.bitbucket(token, "user", "workspaces", query = listOf("pagelen" to "100"))
        if (answer.status == HttpStatusCode.Unauthorized) return ForgeResult.Failure(ForgeError.Unauthorized)
        return answer.toResult { body<PaginatedWorkspaceAccess>().propertyValues.orEmpty().mapNotNull { access -> access.workspace?.slug?.let { it to access.workspace.links?.avatar?.href } } }
    }

    /** The workspaces the person is in besides their own: Bitbucket's teams, as organisations. */
    override suspend fun organizations(token: String): ForgeResult<List<UserSummary>> = forgeCall {
        val me = http.bitbucket(token, "user").let { if (it.status.isSuccess()) it.body<Account>() else return@forgeCall it.failure() }
        workspaceAccess(token).map { access ->
            // A person's own workspace is named as they are, or by their UUID.
            access.filter { (slug, _) -> slug != me.username && slug != me.uuid && !slug.equals(me.nickname, ignoreCase = true) }
                .map { (slug, avatar) -> UserSummary(slug, avatar, isOrganization = true, forge = Bitbucket) }
        }
    }

    override suspend fun isFollowing(token: String, login: String): ForgeResult<Boolean> = ForgeResult.Failure(ForgeError.Unsupported)

    override suspend fun setFollowing(token: String, login: String, follow: Boolean): ForgeResult<Unit> = ForgeResult.Failure(ForgeError.Unsupported)

    private companion object {
        const val MAX_REACHABLE = 1000
    }
}
