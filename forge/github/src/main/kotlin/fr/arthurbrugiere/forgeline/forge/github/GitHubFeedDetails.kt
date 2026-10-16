package fr.arthurbrugiere.forgeline.forge.github

import fr.arthurbrugiere.forgeline.core.forge.FeedDetail
import fr.arthurbrugiere.forgeline.core.forge.FeedDetailKey
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ConversationPreview
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.ReleasePreview
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoPreview
import fr.arthurbrugiere.forgeline.core.model.firstImage
import fr.arthurbrugiere.forgeline.core.model.releaseSummary
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * Every Feed row's details asked in GraphQL, [BATCH] rows per request and the requests together: one request for a
 * screen of rows, where REST takes one (or three, for a release) per row. Each row is its own aliased field, so one
 * that isn't found (a deleted repository) comes back null without failing the others.
 */
internal suspend fun HttpClient.gitHubFeedDetails(apiBaseUrl: String, token: String, keys: List<FeedDetailKey>): ForgeResult<Map<FeedDetailKey, FeedDetail>> {
    val results = coroutineScope { keys.chunked(BATCH).map { batch -> async { gitHubCall { batch(apiBaseUrl, token, batch) } } }.awaitAll() }
    // One batch failing loses only its rows, unless all did: then the Feed hears why.
    if (results.all { it is ForgeResult.Failure }) return results.first() as ForgeResult.Failure
    return ForgeResult.Success(results.filterIsInstance<ForgeResult.Success<Map<FeedDetailKey, FeedDetail>>>().fold(emptyMap()) { all, it -> all + it.value })
}

private const val BATCH = 25

private const val REPO_FIELDS = "description primaryLanguage { name } stargazerCount pushedAt"
private const val RELEASE_FIELDS = "owner { avatarUrl } usesCustomOpenGraphImage openGraphImageUrl " +
    "releases(first: 20, orderBy: {field: CREATED_AT, direction: DESC}) { nodes { tagName isPrerelease isDraft } }"
private const val CONVERSATION_FIELDS = "title comments { totalCount } labels(first: 5) { nodes { name color } }"

private suspend fun HttpClient.batch(apiBaseUrl: String, token: String, keys: List<FeedDetailKey>): ForgeResult<Map<FeedDetailKey, FeedDetail>> {
    val params = mutableListOf<String>()
    val variables = mutableMapOf<String, Any>()
    val fields = keys.mapIndexed { i, key ->
        val repo = key.repo()
        params += "\$o$i: String!, \$n$i: String!"
        variables["o$i"] = repo.owner
        variables["n$i"] = repo.name
        val inside = when (key) {
            is FeedDetailKey.Repo -> REPO_FIELDS
            is FeedDetailKey.Release -> {
                params += "\$t$i: String!"
                variables["t$i"] = key.ref.tag
                "$RELEASE_FIELDS release(tagName: \$t$i) { description reactionGroups { content reactors { totalCount } } }"
            }
            is FeedDetailKey.Conversation -> {
                params += "\$c$i: Int!"
                variables["c$i"] = key.ref.number
                "issueOrPullRequest(number: \$c$i) { __typename ... on Issue { $CONVERSATION_FIELDS } " +
                    "... on PullRequest { $CONVERSATION_FIELDS additions deletions changedFiles } }"
            }
        }
        "r$i: repository(owner: \$o$i, name: \$n$i) { $inside }"
    }
    val request = buildJsonObject {
        put("query", "query FeedDetails(${params.joinToString(", ")}) { ${fields.joinToString(" ")} }")
        put(
            "variables",
            buildJsonObject {
                variables.forEach { (name, value) -> if (value is Int) put(name, value) else put(name, value.toString()) }
            },
        )
    }
    return gitHubApi(apiBaseUrl, token, "graphql", method = HttpMethod.Post, body = request).toResult {
        val data = body<DetailsResponse>().data ?: JsonObject(emptyMap())
        keys.withIndex().mapNotNull { (i, key) ->
            val node = data["r$i"] as? JsonObject ?: return@mapNotNull null
            runCatching { key.detail(GitHubJson.decodeFromJsonElement<DetailsRepoJson>(node)) }.getOrNull()?.let { key to it }
        }.toMap()
    }
}

private fun FeedDetailKey.repo(): RepoId = when (this) {
    is FeedDetailKey.Repo -> id
    is FeedDetailKey.Release -> ref.repo
    is FeedDetailKey.Conversation -> ref.repo
}

private fun FeedDetailKey.detail(json: DetailsRepoJson): FeedDetail? = when (this) {
    is FeedDetailKey.Repo -> FeedDetail.Repo(
        RepoPreview(json.description?.ifBlank { null }, json.primaryLanguage?.name, json.stargazerCount ?: 0, json.pushedAt?.let(Instant::parse)),
    )
    is FeedDetailKey.Release -> json.release?.let { release ->
        val tags = json.releases?.nodes.orEmpty()
        val at = tags.indexOfFirst { it.tagName == ref.tag }
        // The newest stable release before this one: a pre-release in between says nothing of the step.
        val previous = if (at < 0) null else tags.drop(at + 1).firstOrNull { !it.isPrerelease && !it.isDraft }?.tagName
        FeedDetail.Release(
            ReleasePreview(
                summary = releaseSummary(release.description),
                previousTag = previous,
                // GitHub draws a card for every repository; only one the project made itself is a picture worth showing.
                imageUrl = json.openGraphImageUrl?.takeIf { json.usesCustomOpenGraphImage } ?: firstImage(release.description),
                logoUrl = json.owner?.avatarUrl,
                reactions = release.reactionGroups.reactions(),
            ),
        )
    }
    is FeedDetailKey.Conversation -> json.issueOrPullRequest?.let { c ->
        FeedDetail.Conversation(
            ConversationPreview(
                title = c.title,
                labels = c.labels?.nodes.orEmpty().map { Label(it.name, it.color) },
                comments = c.comments?.totalCount,
                additions = c.additions,
                deletions = c.deletions,
                changedFiles = c.changedFiles,
            ),
        )
    }
}

private fun List<DetailsReactionGroupJson>.reactions(): Map<Reaction, Int> =
    mapNotNull { group -> Reaction.entries.firstOrNull { it.name == group.content }?.let { it to group.reactors.totalCount } }
        .filter { it.second > 0 }.toMap()

@Serializable
private data class DetailsResponse(val data: JsonObject? = null)

@Serializable
private data class DetailsNameJson(val name: String)

@Serializable
private data class DetailsAvatarJson(val avatarUrl: String? = null)

@Serializable
private data class DetailsCountJson(val totalCount: Int)

@Serializable
private data class DetailsTagJson(val tagName: String, val isPrerelease: Boolean = false, val isDraft: Boolean = false)

@Serializable
private data class DetailsNodes<T>(val nodes: List<T> = emptyList())

@Serializable
private data class DetailsReactionGroupJson(val content: String, val reactors: DetailsCountJson)

@Serializable
private data class DetailsReleaseJson(val description: String? = null, val reactionGroups: List<DetailsReactionGroupJson> = emptyList())

@Serializable
private data class DetailsLabelJson(val name: String, val color: String? = null)

@Serializable
private data class DetailsConversationJson(
    val title: String? = null,
    val comments: DetailsCountJson? = null,
    val labels: DetailsNodes<DetailsLabelJson>? = null,
    val additions: Int? = null,
    val deletions: Int? = null,
    val changedFiles: Int? = null,
)

@Serializable
private data class DetailsRepoJson(
    val description: String? = null,
    val primaryLanguage: DetailsNameJson? = null,
    val stargazerCount: Int? = null,
    val pushedAt: String? = null,
    val owner: DetailsAvatarJson? = null,
    val usesCustomOpenGraphImage: Boolean = false,
    val openGraphImageUrl: String? = null,
    val releases: DetailsNodes<DetailsTagJson>? = null,
    val release: DetailsReleaseJson? = null,
    val issueOrPullRequest: DetailsConversationJson? = null,
)
