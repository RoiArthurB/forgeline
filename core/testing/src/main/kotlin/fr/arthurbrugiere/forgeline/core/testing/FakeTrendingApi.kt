package fr.arthurbrugiere.forgeline.core.testing

import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.TrendingApi
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import fr.arthurbrugiere.forgeline.core.model.TrendingRepo

class FakeTrendingApi(override val forge: ForgeInstance = ForgeInstance.GitHub) : TrendingApi {
    val results = mutableMapOf<TrendingPeriod, ForgeResult<List<TrendingRepo>>>()
    val calls = mutableListOf<TrendingPeriod>()

    override suspend fun trending(period: TrendingPeriod): ForgeResult<List<TrendingRepo>> {
        calls += period
        return results[period] ?: ForgeResult.Success(emptyList())
    }
}

fun trendingRepo(
    fullName: String,
    stars: Int = 100,
    periodStars: Int = 10,
    description: String? = "Description of $fullName",
    forge: ForgeInstance = ForgeInstance.GitHub,
): TrendingRepo {
    // GitLab owners can be nested groups: the name is the last part.
    val owner = fullName.substringBeforeLast('/')
    val name = fullName.substringAfterLast('/')
    return TrendingRepo(RepoId(owner, name, forge), description, "Kotlin", "#A97BFF", stars, forks = 5, periodStars, builtBy = emptyList())
}
