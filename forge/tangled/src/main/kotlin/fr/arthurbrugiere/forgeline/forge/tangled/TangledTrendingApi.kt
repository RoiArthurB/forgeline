package fr.arthurbrugiere.forgeline.forge.tangled

import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.TrendingApi
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import fr.arthurbrugiere.forgeline.core.model.TrendingRepo
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Clock
import java.time.Duration

/**
 * Tangled's own Trending (`org.tangled.actor.getTrending`): a handful of repositories, for no period in particular. What
 * each gained in the period asked is counted from its stars' dates (`org.tangled.feed.listStars`, newest first), so the
 * page can show it and place it among the other forges' by share of stars. The page's star floor leaves them be: Tangled
 * already picked them.
 */
class TangledTrendingApi(
    private val httpClient: HttpClient,
    private val identity: TangledIdentity,
    private val clock: Clock = Clock.systemUTC(),
) : TrendingApi {
    override val forge: ForgeInstance = ForgeInstance.Tangled

    override suspend fun trending(period: TrendingPeriod): ForgeResult<List<TrendingRepo>> = forgeCall {
        val repos = when (val result = httpClient.xrpc("org.tangled.actor.getTrending").toResult { body<TrendingJson>().repos }) {
            is ForgeResult.Failure -> return@forgeCall result
            is ForgeResult.Success -> result.value
        }
        val since = clock.instant().minus(period.length)
        val gained = coroutineScope { repos.map { async { starsSince(it.did, since) } }.awaitAll() }
        ForgeResult.Success(
            repos.zip(gained).map { (repo, periodStars) ->
                identity.remember(repo.owner.handle, repo.owner.did)
                TrendingRepo(
                    id = RepoId(repo.owner.handle, repo.slug, forge),
                    description = repo.description?.takeIf { it.isNotBlank() },
                    language = repo.primaryLanguage,
                    languageColor = null,
                    stars = repo.starCount ?: 0,
                    forks = repo.forkCount ?: 0,
                    periodStars = periodStars,
                    builtBy = listOf(ForgeUser(repo.owner.handle, null, repo.owner.avatar)),
                    ownerAvatarUrl = repo.owner.avatar,
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
                httpClient.xrpc("org.tangled.feed.listStars", "subject" to repoDid, "limit" to "$STARS_PAGE", "order" to "desc", "cursor" to cursor)
                    .toResult { body<RecordListJson<StarRecordJson>>() } as? ForgeResult.Success
                )?.value ?: return count
            for (star in page.items) {
                val at = instant(star.value.createdAt) ?: continue
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
