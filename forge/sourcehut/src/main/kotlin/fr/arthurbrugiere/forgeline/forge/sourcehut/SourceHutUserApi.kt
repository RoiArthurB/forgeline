package fr.arthurbrugiere.forgeline.forge.sourcehut

import com.apollographql.apollo.api.Optional
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.UserProfile
import fr.arthurbrugiere.forgeline.forge.common.UnsupportedUserApi
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import fr.arthurbrugiere.forgeline.forge.sourcehut.graphql.PersonQuery
import fr.arthurbrugiere.forgeline.forge.sourcehut.graphql.PersonRepositoriesQuery
import fr.arthurbrugiere.forgeline.forge.sourcehut.graphql.type.Visibility
import io.ktor.client.HttpClient

/**
 * A person on SourceHut, by their `~name`: their profile and their repositories. SourceHut has no followers, stars or
 * activity: Unsupported, and the profile says none (the app shows no counts for a forge that isn't social).
 */
class SourceHutUserApi(
    private val httpClient: HttpClient,
    private val forge: ForgeInstance = ForgeInstance.SourceHut,
) : UnsupportedUserApi() {

    override suspend fun user(token: String?, login: String): ForgeResult<UserProfile> = forgeCall {
        when (val result = httpClient.srht(token, PersonQuery(login.username))) {
            is ForgeResult.Failure -> result
            is ForgeResult.Success -> {
                val user = result.value.user ?: return@forgeCall notFound("No user $login")
                val repos = (repositories(token, login) as? ForgeResult.Success)?.value?.size ?: 0
                ForgeResult.Success(
                    UserProfile(
                        login = user.canonicalName,
                        name = null,
                        avatarUrl = null,
                        bio = user.bio?.takeIf { it.isNotBlank() },
                        company = null,
                        location = user.location?.takeIf { it.isNotBlank() },
                        website = user.url?.takeIf { it.isNotBlank() },
                        followers = 0,
                        following = 0,
                        publicRepos = repos,
                        isOrganization = false,
                        createdAt = instant(user.created),
                    ),
                )
            }
        }
    }

    /** Newest first, as SourceHut lists them: those the token may read, private ones too for its owner. */
    override suspend fun repos(token: String?, login: String, mine: Boolean, organization: Boolean): ForgeResult<List<RepoSummary>> = forgeCall {
        when (val result = repositories(token, login)) {
            is ForgeResult.Failure -> result
            is ForgeResult.Success -> ForgeResult.Success(
                result.value.map { repo ->
                    RepoSummary(
                        id = RepoId(login.canonical, repo.name, forge),
                        description = repo.description?.takeIf { it.isNotBlank() },
                        language = null,
                        stars = 0,
                        forks = 0,
                        isFork = false,
                        updatedAt = instant(repo.updated),
                        isPrivate = repo.visibility == Visibility.PRIVATE,
                    )
                },
            )
        }
    }

    private suspend fun repositories(token: String?, login: String): ForgeResult<List<PersonRepositoriesQuery.Result>> {
        val all = mutableListOf<PersonRepositoriesQuery.Result>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val page = when (val result = httpClient.srht(token, PersonRepositoriesQuery(login.username, Optional.presentIfNotNull(cursor)))) {
                is ForgeResult.Failure -> return result
                is ForgeResult.Success -> result.value.user?.repositories ?: return notFound("No user $login")
            }
            all += page.results
            cursor = page.cursor ?: return ForgeResult.Success(all)
        }
        return ForgeResult.Success(all)
    }

    private companion object {
        /** 100 a page: someone with more than 1,000 repositories shows their newest 1,000. */
        const val MAX_PAGES = 10
    }
}
