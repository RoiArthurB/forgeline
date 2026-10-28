package fr.arthurbrugiere.forgeline.forge.github

import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.UserApi
import fr.arthurbrugiere.forgeline.core.forge.map
import fr.arthurbrugiere.forgeline.core.model.Contributions
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ProfileLink
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.StarList
import fr.arthurbrugiere.forgeline.core.model.UserProfile
import fr.arthurbrugiere.forgeline.core.model.UserSummary
import fr.arthurbrugiere.forgeline.core.model.distinctPlaces
import fr.arthurbrugiere.forgeline.forge.common.ForgeJson
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate

class GitHubUserApi(
    private val httpClient: HttpClient,
    private val apiBaseUrl: String = "https://api.github.com",
) : UserApi {

    // Only GraphQL has the lists. A list's repositories come a hundred at a time; one holding more is read on, to
    // a thousand.
    override suspend fun starLists(token: String): ForgeResult<List<StarList>> = forgeCall {
        readStarLists(token).map { lists ->
            // A list's name keeps the emoji codes it was typed with (":goggles: Oculus Quest stuff"); GitHub's site
            // draws them, so they are written here as the emoji they stand for.
            if (lists.none { EMOJI_CODE.containsMatchIn(it.name) }) return@map lists
            val emojis = emojis(token)
            lists.map { it.copy(name = withEmojis(it.name, emojis)) }
        }
    }

    /** GitHub's emoji codes and the characters they stand for; empty when they can't be read now. */
    private suspend fun emojis(token: String): Map<String, String> = runCatching {
        httpClient.gitHubApi(apiBaseUrl, token, "emojis").takeIf { it.status == HttpStatusCode.OK }?.body<Map<String, String>>()
    }.getOrNull().orEmpty().mapNotNull { (code, image) -> emojiOfImage(image)?.let { code to it } }.toMap()

    private suspend fun readStarLists(token: String): ForgeResult<List<StarList>> = forgeCall {
        httpClient.gitHubGraphQl(apiBaseUrl, token, LISTS_QUERY).map { data ->
            val lists = data["viewer"]?.jsonObject?.get("lists")?.jsonObject?.get("nodes")?.jsonArray.orEmpty()
                .map { ForgeJson.decodeFromJsonElement<StarListJson>(it) }
            lists.map { list ->
                val repos = list.items.nodes.mapNotNull { it.toSummary() }.toMutableList()
                var page = list.items.pageInfo
                var pages = 1
                while (page.hasNextPage && page.endCursor != null && pages < MAX_LIST_PAGES) {
                    val variables = buildJsonObject {
                        put("id", list.id)
                        put("after", page.endCursor)
                    }
                    val more = httpClient.gitHubGraphQl(apiBaseUrl, token, LIST_ITEMS_QUERY, variables)
                    val items = (more as? ForgeResult.Success)?.value?.get("node")?.jsonObject?.get("items") ?: break
                    val read = ForgeJson.decodeFromJsonElement<StarListItemsJson>(items)
                    repos += read.nodes.mapNotNull { it.toSummary() }
                    page = read.pageInfo
                    pages++
                }
                StarList(list.id, list.name, list.description?.takeIf { it.isNotBlank() }, repos)
            }
        }
    }

    // Only GraphQL has the calendar, and GraphQL wants a token: signed out, GitHub's profiles show none.
    override suspend fun contributions(token: String?, login: String): ForgeResult<Contributions> {
        if (token == null) return ForgeResult.Failure(fr.arthurbrugiere.forgeline.core.forge.ForgeError.Unauthorized)
        val variables = buildJsonObject { put("login", login) }
        return forgeCall {
            httpClient.gitHubGraphQl(apiBaseUrl, token, CONTRIBUTIONS_QUERY, variables).map { data ->
                val weeks = data["user"]?.jsonObject?.get("contributionsCollection")?.jsonObject?.get("contributionCalendar")?.jsonObject?.get("weeks")?.jsonArray.orEmpty()
                Contributions(
                    weeks.flatMap { it.jsonObject["contributionDays"]?.jsonArray.orEmpty() }.associate { day ->
                        LocalDate.parse(day.jsonObject.getValue("date").jsonPrimitive.content) to day.jsonObject.getValue("contributionCount").jsonPrimitive.int
                    },
                )
            }
        }
    }

    /** The profile, and beside it the social accounts the person listed (organisations have none to list). */
    override suspend fun user(token: String?, login: String): ForgeResult<UserProfile> = forgeCall {
        coroutineScope {
            val social = async {
                runCatching {
                    httpClient.gitHubApi(apiBaseUrl, token, "users", login, "social_accounts")
                        .takeIf { it.status == HttpStatusCode.OK }?.body<List<SocialAccountJson>>()
                }.getOrNull().orEmpty()
            }
            httpClient.gitHubApi(apiBaseUrl, token, "users", login).toResult {
                val profile = body<ProfileJson>()
                profile.toModel().copy(links = profile.links(social.await()))
            }
        }
    }

    /**
     * GitHub's list of someone's repositories holds only the public ones, even for that person. One's own come from
     * `/user/repos`, an organisation's from `/orgs/…/repos`, which add the private ones the token may see.
     */
    override suspend fun repos(token: String?, login: String, mine: Boolean, organization: Boolean): ForgeResult<List<RepoSummary>> = forgeCall {
        val query = mapOf("sort" to "updated", "per_page" to "30")
        val response = when {
            mine && token != null -> httpClient.gitHubApi(apiBaseUrl, token, "user", "repos", query = query + ("affiliation" to "owner"))
            organization -> httpClient.gitHubApi(apiBaseUrl, token, "orgs", login, "repos", query = query + ("type" to "all"))
            else -> httpClient.gitHubApi(apiBaseUrl, token, "users", login, "repos", query = query)
        }
        response.toResult { body<List<RepoJson>>().map { it.toModel() } }
    }

    /** Own, organisations' and shared repositories, by last push; the pages after the first are asked all at once. */
    override suspend fun reachableRepos(token: String): ForgeResult<List<RepoSummary>> = forgeCall {
        val query = mapOf("affiliation" to "owner,collaborator,organization_member", "sort" to "pushed", "per_page" to "100")
        val first = httpClient.gitHubApi(apiBaseUrl, token, "user", "repos", query = query + ("page" to "1"))
        if (first.status != HttpStatusCode.OK) return@forgeCall first.failure()
        val last = (first.lastPage() ?: 1).coerceAtMost(MAX_REACHABLE_PAGES)
        val rest = coroutineScope {
            (2..last).map { page ->
                async {
                    // A later page that fails only loses its own repositories.
                    runCatching {
                        httpClient.gitHubApi(apiBaseUrl, token, "user", "repos", query = query + ("page" to "$page"))
                            .takeIf { it.status == HttpStatusCode.OK }?.body<List<RepoJson>>()
                    }.getOrNull().orEmpty()
                }
            }.awaitAll().flatten()
        }
        ForgeResult.Success((first.body<List<RepoJson>>() + rest).map { it.toModel() }.distinctBy { it.id })
    }

    override suspend fun organizations(token: String): ForgeResult<List<UserSummary>> = forgeCall {
        httpClient.gitHubApi(apiBaseUrl, token, "user", "orgs", query = mapOf("per_page" to "100"))
            .toResult { body<List<OrgJson>>().map { UserSummary(it.login, it.avatarUrl, isOrganization = true, forge = ForgeInstance.GitHub) } }
    }

    override suspend fun starred(token: String?, login: String): ForgeResult<List<RepoSummary>> = forgeCall {
        httpClient.gitHubApi(apiBaseUrl, token, "users", login, "starred", query = mapOf("per_page" to "30"))
            .toResult { body<List<RepoJson>>().map { it.toModel() } }
    }

    override suspend fun isFollowing(token: String, login: String): ForgeResult<Boolean> = forgeCall {
        // Answered by status code alone: 204 follows, 404 doesn't.
        val response = httpClient.gitHubApi(apiBaseUrl, token, "user", "following", login)
        when (response.status) {
            HttpStatusCode.NoContent -> ForgeResult.Success(true)
            HttpStatusCode.NotFound -> ForgeResult.Success(false)
            else -> response.failure()
        }
    }

    override suspend fun setFollowing(token: String, login: String, follow: Boolean): ForgeResult<Unit> = forgeCall {
        httpClient.gitHubApi(apiBaseUrl, token, "user", "following", login, method = if (follow) HttpMethod.Put else HttpMethod.Delete)
            .toResult { }
    }
}

@Serializable
private data class OrgJson(val login: String, @SerialName("avatar_url") val avatarUrl: String? = null)

/** Ten pages of a hundred: past a thousand repositories, those left alone longest are left out. */
private const val MAX_REACHABLE_PAGES = 10

@Serializable
private data class SocialAccountJson(val provider: String = "generic", val url: String)

@Serializable
private data class ProfileJson(
    val login: String,
    val name: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val bio: String? = null,
    val company: String? = null,
    val location: String? = null,
    val blog: String? = null,
    val followers: Int = 0,
    val following: Int = 0,
    @SerialName("public_repos") val publicRepos: Int = 0,
    val type: String = "User",
    @SerialName("created_at") val createdAt: String? = null,
    val email: String? = null,
    @SerialName("twitter_username") val twitterUsername: String? = null,
) {
    /** The public email, the social accounts, then the X name GitHub keeps apart (once, if also listed). */
    fun links(social: List<SocialAccountJson>): List<ProfileLink> = (
        listOfNotNull(ProfileLink.email(email)) +
            social.mapNotNull { ProfileLink.of(it.url, mastodon = it.provider == "mastodon") } +
            listOfNotNull(twitterUsername?.ifBlank { null }?.let { ProfileLink.of("https://x.com/$it") })
        ).distinctPlaces()

    fun toModel() = UserProfile(
        login = login,
        name = name?.ifBlank { null },
        avatarUrl = avatarUrl,
        bio = bio?.ifBlank { null },
        company = company?.ifBlank { null },
        location = location?.ifBlank { null },
        website = blog?.ifBlank { null },
        followers = followers,
        following = following,
        publicRepos = publicRepos,
        isOrganization = type == "Organization",
        createdAt = createdAt?.let(Instant::parse),
    )
}

@Serializable
private data class RepoJsonOwner(@SerialName("avatar_url") val avatarUrl: String? = null, val type: String? = null)

@Serializable
private data class RepoJson(
    @SerialName("full_name") val fullName: String,
    val owner: RepoJsonOwner? = null,
    val description: String? = null,
    val language: String? = null,
    @SerialName("stargazers_count") val stars: Int = 0,
    @SerialName("forks_count") val forks: Int = 0,
    val fork: Boolean = false,
    @SerialName("updated_at") val updatedAt: String? = null,
    val private: Boolean = false,
    @SerialName("pushed_at") val pushedAt: String? = null,
) {
    fun toModel(): RepoSummary {
        val (owner, name) = fullName.split('/', limit = 2)
        return RepoSummary(RepoId(owner, name, ForgeInstance.GitHub), description, language, stars, forks, fork, updatedAt?.let(Instant::parse), this.owner?.avatarUrl, private, pushedAt?.let(Instant::parse), this.owner?.type?.let { it == "Organization" })
    }
}

private const val CONTRIBUTIONS_QUERY =
    "query Contributions(\$login: String!) { user(login: \$login) { contributionsCollection { contributionCalendar { weeks { contributionDays { date contributionCount } } } } } }"

private const val LIST_ITEM_FIELDS =
    "totalCount nodes { __typename ... on Repository { nameWithOwner description stargazerCount forkCount isFork isPrivate primaryLanguage { name } owner { avatarUrl } } } pageInfo { hasNextPage endCursor }"

private const val LISTS_QUERY = "query StarLists { viewer { lists(first: 100) { nodes { id name description items(first: 100) { $LIST_ITEM_FIELDS } } } } }"

private const val LIST_ITEMS_QUERY =
    "query StarListItems(\$id: ID!, \$after: String) { node(id: \$id) { ... on UserList { items(first: 100, after: \$after) { $LIST_ITEM_FIELDS } } } }"

/** Ten pages of a hundred: a list longer than that is cut there. */
private const val MAX_LIST_PAGES = 10

@Serializable
private data class StarListJson(val id: String, val name: String, val description: String? = null, val items: StarListItemsJson)

@Serializable
private data class StarListItemsJson(val nodes: List<StarListItemJson> = emptyList(), val pageInfo: PageInfoJson = PageInfoJson())

@Serializable
private data class PageInfoJson(val hasNextPage: Boolean = false, val endCursor: String? = null)

@Serializable
private data class NamedJson(val name: String? = null)

@Serializable
private data class AvatarJson(val avatarUrl: String? = null)

/** A list holds repositories (and, one day perhaps, more): anything else is left out. */
@Serializable
private data class StarListItemJson(
    @SerialName("__typename") val type: String,
    val nameWithOwner: String? = null,
    val description: String? = null,
    val stargazerCount: Int = 0,
    val forkCount: Int = 0,
    val isFork: Boolean = false,
    val isPrivate: Boolean = false,
    val primaryLanguage: NamedJson? = null,
    val owner: AvatarJson? = null,
) {
    fun toSummary(): RepoSummary? {
        if (type != "Repository") return null
        val (owner, name) = nameWithOwner?.split('/')?.takeIf { it.size == 2 } ?: return null
        return RepoSummary(RepoId(owner, name, ForgeInstance.GitHub), description, primaryLanguage?.name, stargazerCount, forkCount, isFork, null, this.owner?.avatarUrl, isPrivate)
    }
}

private val EMOJI_CODE = Regex(":([a-z0-9_+-]+):")

/** [name] with each emoji code GitHub knows written as its emoji; a code it doesn't know (or its own, like `:octocat:`) stays. */
internal fun withEmojis(name: String, emojis: Map<String, String>): String =
    EMOJI_CODE.replace(name) { match -> emojis[match.groupValues[1]] ?: match.value }

/**
 * The emoji an image of GitHub's names in its file (`.../unicode/1f9d1-1f3ed.png`): its code points, which GitHub
 * writes without the zero-width joiner a sequence needs. Flags, keycaps and skin tones join without one; any other
 * sequence (a person and a job, a heart on fire) gets it back. An image of GitHub's own (`octocat.png`) is no emoji.
 */
internal fun emojiOfImage(image: String): String? {
    val name = image.substringAfter("/unicode/", "").substringBefore('.').takeIf { it.isNotEmpty() } ?: return null
    val points = name.split('-').map { it.toIntOrNull(16) ?: return null }
    val plain = points.all { it in 0x1F1E6..0x1F1FF } || points.any { it == 0x20E3 || it in 0x1F3FB..0x1F3FF } || points.size == 1
    return points.joinToString(if (plain) "" else "\u200D") { String(Character.toChars(it)) }
}
