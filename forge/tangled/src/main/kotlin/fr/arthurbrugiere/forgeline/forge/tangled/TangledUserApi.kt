package fr.arthurbrugiere.forgeline.forge.tangled

import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ProfileLink
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.UserProfile
import fr.arthurbrugiere.forgeline.forge.common.UnsupportedUserApi
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject

/**
 * A person or organisation on Tangled, by handle: their profile record, how many follow them and they follow, their
 * repositories and those they starred; and following them, once signed in ([follows]). Their activity isn't served yet:
 * Unsupported.
 */
class TangledUserApi internal constructor(
    private val httpClient: HttpClient,
    private val identity: TangledIdentity,
    private val follows: TangledFollows?,
    private val forge: ForgeInstance = ForgeInstance.Tangled,
) : UnsupportedUserApi() {

    /** Read-only: without an account, following answers Unsupported. */
    constructor(httpClient: HttpClient, identity: TangledIdentity) : this(httpClient, identity, null)

    override suspend fun isFollowing(token: String, login: String): ForgeResult<Boolean> =
        follows?.isFollowing(token, login) ?: super.isFollowing(token, login)

    override suspend fun setFollowing(token: String, login: String, follow: Boolean): ForgeResult<Unit> =
        follows?.setFollowing(token, login, follow) ?: super.setFollowing(token, login, follow)

    /**
     * Newest first, up to [STARRED]. A star names the repository by its DID: each is looked up for its owner and name,
     * and one that is gone is left out.
     */
    override suspend fun starred(token: String?, login: String): ForgeResult<List<RepoSummary>> = forgeCall {
        val did = when (val result = identity.did(login)) {
            is ForgeResult.Failure -> return@forgeCall result
            is ForgeResult.Success -> result.value
        }
        val stars = when (
            val page = httpClient.xrpc("org.tangled.feed.listStarsBy", "subject" to did, "limit" to "$STARRED", "order" to "desc")
                .toResult { body<RecordListJson<JsonObject>>().items }
        ) {
            is ForgeResult.Failure -> return@forgeCall page
            is ForgeResult.Success -> page.value
        }
        val repoDids = stars.mapNotNull { it.value.starredRepo }.distinct()
        val repos = coroutineScope { repoDids.map { async { starredRepo(it) } }.awaitAll() }
        ForgeResult.Success(repos.filterNotNull())
    }

    private suspend fun starredRepo(repoDid: String): RepoSummary? {
        val repo = (
            forgeCall { httpClient.xrpc("org.tangled.repo.getRepoByRepoDid", "repoDid" to repoDid).toResult { body<RecordJson<RepoRecordJson>>() } }
                as? ForgeResult.Success
            )?.value ?: return null
        val ownerDid = repo.uri.removePrefix("at://").substringBefore('/')
        val owner = (identity.handle(ownerDid) as? ForgeResult.Success)?.value ?: ownerDid
        return RepoSummary(
            id = RepoId(owner, repo.value.name ?: repo.uri.recordKey, forge),
            description = repo.value.description?.takeIf { it.isNotBlank() },
            language = null,
            stars = 0,
            forks = 0,
            isFork = repo.value.source != null,
            updatedAt = instant(repo.value.createdAt),
        )
    }

    override suspend fun user(token: String?, login: String): ForgeResult<UserProfile> = forgeCall {
        val did = when (val result = identity.did(login)) {
            is ForgeResult.Failure -> return@forgeCall result
            is ForgeResult.Success -> result.value
        }
        coroutineScope {
            val profile = async { httpClient.xrpc("org.tangled.actor.getProfile", "actor" to did).toResult { body<RecordJson<ProfileRecordJson>>().value } }
            val followers = async { count("org.tangled.graph.countFollows", did) }
            val following = async { count("org.tangled.graph.countFollowsBy", did) }
            val repos = async { count("org.tangled.repo.countRepos", did) }
            // Someone who never wrote a profile has none: they're still someone, with their handle.
            val record = (profile.await() as? ForgeResult.Success)?.value ?: ProfileRecordJson()
            val links = record.links.filter { it.isNotBlank() }
            ForgeResult.Success(
                UserProfile(
                    login = login.removePrefix("@"),
                    name = null,
                    avatarUrl = null,
                    bio = record.description?.takeIf { it.isNotBlank() },
                    company = null,
                    location = record.location?.takeIf { it.isNotBlank() },
                    website = links.firstOrNull(),
                    followers = followers.await(),
                    following = following.await(),
                    publicRepos = repos.await(),
                    isOrganization = record.isOrganization,
                    createdAt = null,
                    links = links.drop(1).mapNotNull { ProfileLink.of(it) },
                ),
            )
        }
    }

    /** Newest first. Tangled's list says neither stars nor forks: they read 0. */
    override suspend fun repos(token: String?, login: String, mine: Boolean, organization: Boolean): ForgeResult<List<RepoSummary>> = forgeCall {
        val did = when (val result = identity.did(login)) {
            is ForgeResult.Failure -> return@forgeCall result
            is ForgeResult.Success -> result.value
        }
        httpClient.xrpc("org.tangled.repo.listRepos", "subject" to did, "limit" to "100", "order" to "desc").toResult {
            body<RecordListJson<RepoRecordJson>>().items.map { item ->
                RepoSummary(
                    id = RepoId(login.removePrefix("@"), item.value.name ?: item.uri.recordKey, forge),
                    description = item.value.description?.takeIf { it.isNotBlank() },
                    language = null,
                    stars = 0,
                    forks = 0,
                    isFork = item.value.source != null,
                    updatedAt = instant(item.value.createdAt),
                )
            }
        }
    }

    private companion object {
        const val STARRED = 50
    }

    private suspend fun count(nsid: String, subject: String): Int =
        (forgeCall { httpClient.xrpc(nsid, "subject" to subject).toResult { body<CountJson>().count } } as? ForgeResult.Success)?.value ?: 0
}
