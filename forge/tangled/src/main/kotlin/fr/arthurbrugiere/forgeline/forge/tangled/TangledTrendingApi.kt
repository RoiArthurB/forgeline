package fr.arthurbrugiere.forgeline.forge.tangled

import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.TrendingApi
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import fr.arthurbrugiere.forgeline.core.model.TrendingRepo
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.tangled.feed.ListStarsOrder
import org.tangled.feed.ListStarsQueryParams
import java.time.Clock
import java.time.Duration

/**
 * Tangled's own Trending (`org.tangled.actor.getTrending`): a handful of repositories, for no period in particular. What
 * each gained in the period asked is counted from its stars' dates (`org.tangled.feed.listStars`, newest first), so the
 * page can show it. The page's floors leave them be: Tangled already picked them.
 */
class TangledTrendingApi(
    private val xrpc: TangledXrpc,
    private val identity: TangledIdentity,
    private val clock: Clock = Clock.systemUTC(),
) : TrendingApi {
    override val forge: ForgeInstance = ForgeInstance.Tangled

    override suspend fun trending(period: TrendingPeriod): ForgeResult<List<TrendingRepo>> = forgeCall {
        val repos = when (val result = xrpc.index.getTrending().toResult { it.repos }) {
            is ForgeResult.Failure -> return@forgeCall result
            is ForgeResult.Success -> result.value
        }
        val since = clock.instant().minus(period.length)
        val gained = coroutineScope { repos.map { async { starsSince(it.did.did, since) } }.awaitAll() }
        // The index names an owner's picture by its blob's CID (rarely by an address): read as Tangled reads it.
        val avatars = coroutineScope {
            repos.map { repo -> async { repo.owner.avatar?.uri ?: identity.avatarOf(repo.owner.did.did, repo.owner.avatarCid?.cid) } }.awaitAll()
        }
        ForgeResult.Success(
            repos.zip(gained).zip(avatars).map { (repoAndStars, avatar) ->
                val (repo, periodStars) = repoAndStars
                val owner = repo.owner.handle.handle
                identity.remember(owner, repo.owner.did.did)
                TrendingRepo(
                    id = RepoId(owner, repo.slug, forge),
                    description = repo.description?.takeIf { it.isNotBlank() },
                    language = repo.primaryLanguage,
                    languageColor = null,
                    stars = repo.starCount?.toInt() ?: 0,
                    forks = repo.forkCount?.toInt() ?: 0,
                    periodStars = periodStars,
                    builtBy = listOf(ForgeUser(owner, null, avatar)),
                    ownerAvatarUrl = avatar,
                )
            },
        )
    }

    /** Stars [repoDid] was given since [since], from the newest back: one page of up to [STARS_PAGE] tells it for most. */
    private suspend fun starsSince(repoDid: String, since: java.time.Instant): Int {
        var count = 0
        var cursor: String? = null
        repeat(MAX_STAR_PAGES) {
            val page = (
                forgeCall {
                    xrpc.index.listStars(ListStarsQueryParams(repoDid, cursor = cursor, limit = STARS_PAGE.toLong(), order = ListStarsOrder.Desc))
                        .toResult { it }
                } as? ForgeResult.Success
                )?.value ?: return count
            for (star in page.items) {
                val at = instant(star.value.decodeAs<StarRecordJson>().createdAt) ?: continue
                if (at < since) return count
                count++
            }
            cursor = page.cursor?.takeIf { it.isNotBlank() && page.items.size == STARS_PAGE } ?: return count
        }
        return count
    }

    private companion object {
        const val STARS_PAGE = 100
        const val MAX_STAR_PAGES = 5

        val TrendingPeriod.length: Duration
            get() = when (this) {
                TrendingPeriod.DAILY -> Duration.ofDays(1)
                TrendingPeriod.WEEKLY -> Duration.ofDays(7)
                TrendingPeriod.MONTHLY -> Duration.ofDays(30)
            }
    }
}
