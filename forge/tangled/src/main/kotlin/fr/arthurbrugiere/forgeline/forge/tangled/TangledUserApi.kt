package fr.arthurbrugiere.forgeline.forge.tangled

import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ProfileLink
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.UserProfile
import fr.arthurbrugiere.forgeline.forge.common.UnsupportedUserApi
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import org.tangled.actor.GetProfileQueryParams
import org.tangled.feed.ListStarsByOrder
import org.tangled.feed.ListStarsByQueryParams
import org.tangled.graph.CountFollowsByQueryParams
import org.tangled.graph.CountFollowsQueryParams
import org.tangled.repo.CountReposQueryParams
import org.tangled.repo.GetRepoByRepoDidQueryParams
import org.tangled.repo.ListReposOrder
import org.tangled.repo.ListReposQueryParams
import sh.christian.ozone.api.AtUri

/**
 * A person or organisation on Tangled, by handle: their profile record, how many follow them and they follow, their
 * repositories and those they starred; and following them, once signed in ([follows]). Their activity isn't served yet:
 * Unsupported.
 */
class TangledUserApi internal constructor(
    private val xrpc: TangledXrpc,
    private val identity: TangledIdentity,
    private val follows: TangledFollows?,
    private val forge: ForgeInstance = ForgeInstance.Tangled,
) : UnsupportedUserApi() {

    /** Read-only: without an account, following answers Unsupported. */
    constructor(xrpc: TangledXrpc, identity: TangledIdentity) : this(xrpc, identity, null)

    override suspend fun isFollowing(token: String, login: String): ForgeResult<Boolean> =
        follows?.isFollowing(token, login) ?: super.isFollowing(token, login)

    override suspend fun setFollowing(token: String, login: String, follow: Boolean): ForgeResult<Unit> =
        follows?.setFollowing(token, login, follow) ?: super.setFollowing(token, login, follow)

    /**
     * Newest first, up to [STARRED]. A star names the repository by its DID: each is looked up for its owner and name,
     * and one that is gone is left out.
     */
    override suspend fun starred(token: String?, login: String): ForgeResult<List<RepoSummary>> = forgeCall {
        val did = when (val result = identity.did(login).then(::asDid)) {
            is ForgeResult.Failure -> return@forgeCall result
            is ForgeResult.Success -> result.value
        }
        val stars = when (
            val page = xrpc.index.listStarsBy(ListStarsByQueryParams(did, limit = STARRED, order = ListStarsByOrder.Desc))
                .toResult { answer -> answer.items.map { it.value.decodeAs<JsonObject>() } }
        ) {
            is ForgeResult.Failure -> return@forgeCall page
            is ForgeResult.Success -> page.value
        }
        val repoDids = stars.mapNotNull { it.starredRepo }.distinct()
        val repos = coroutineScope { repoDids.map { async { starredRepo(it) } }.awaitAll() }
        ForgeResult.Success(repos.filterNotNull())
    }

    private suspend fun starredRepo(repoDid: String): RepoSummary? {
        val (uri, record) = (
            forgeCall {
                asDid(repoDid).then { did ->
                    xrpc.index.getRepoByRepoDid(GetRepoByRepoDidQueryParams(did)).toResult { it.uri.atUri to it.value.decodeAs<RepoRecordJson>() }
                }
            } as? ForgeResult.Success
            )?.value ?: return null
        val ownerDid = record.ownerDid(uri)
        val owner = (identity.handle(ownerDid) as? ForgeResult.Success)?.value ?: ownerDid
        return RepoSummary(
            id = RepoId(owner, record.name(uri), forge),
            description = record.description?.takeIf { it.isNotBlank() },
            language = null,
            stars = 0,
            forks = 0,
            isFork = record.source != null,
            updatedAt = instant(record.createdAt),
            starsKnown = false,
        )
    }

    override suspend fun user(token: String?, login: String): ForgeResult<UserProfile> = forgeCall {
        val did = when (val result = identity.did(login).then(::asDid)) {
            is ForgeResult.Failure -> return@forgeCall result
            is ForgeResult.Success -> result.value
        }
        coroutineScope {
            val profile = async { identity.profile(did.did) }
            val followers = async { count { xrpc.index.countFollows(CountFollowsQueryParams(did)).toResult { it.count } } }
            val following = async { count { xrpc.index.countFollowsBy(CountFollowsByQueryParams(did)).toResult { it.count } } }
            val repos = async { count { xrpc.index.countRepos(CountReposQueryParams(did)).toResult { it.count } } }
            // Someone who never wrote a profile has none: they're still someone, with their handle.
            val record = (profile.await() as? ForgeResult.Success)?.value ?: ProfileRecordJson()
            val links = record.links.filter { it.isNotBlank() }
            val avatar = identity.avatarOf(did.did, record.avatar?.link)
            ForgeResult.Success(
                UserProfile(
                    login = login.removePrefix("@"),
                    name = null,
                    avatarUrl = avatar,
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

    /** Newest first. Tangled's list says neither stars nor forks: they are left unsaid. */
    override suspend fun repos(token: String?, login: String, mine: Boolean, organization: Boolean): ForgeResult<List<RepoSummary>> = forgeCall {
        val did = when (val result = identity.did(login).then(::asDid)) {
            is ForgeResult.Failure -> return@forgeCall result
            is ForgeResult.Success -> result.value
        }
        xrpc.index.listRepos(ListReposQueryParams(did, limit = REPOS, order = ListReposOrder.Desc)).toResult { answer ->
            answer.items.map { item ->
                val record = item.value.decodeAs<RepoRecordJson>()
                RepoSummary(
                    id = RepoId(login.removePrefix("@"), record.name(item.uri.atUri), forge),
                    description = record.description?.takeIf { it.isNotBlank() },
                    language = null,
                    stars = 0,
                    forks = 0,
                    isFork = record.source != null,
                    updatedAt = instant(record.createdAt),
                    starsKnown = false,
                )
            }
        }
    }

    private companion object {
        const val STARRED = 50L
        const val REPOS = 100L
    }

    /** A count Tangled keeps for the person; 0 where it couldn't say, which only leaves a number out. */
    private suspend fun count(ask: suspend () -> ForgeResult<Long>): Int = (forgeCall { ask() } as? ForgeResult.Success)?.value?.toInt() ?: 0
}
