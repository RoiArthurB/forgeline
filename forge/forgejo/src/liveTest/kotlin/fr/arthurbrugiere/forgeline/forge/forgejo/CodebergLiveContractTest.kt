package fr.arthurbrugiere.forgeline.forge.forgejo

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.time.OffsetDateTime

/**
 * If one of these fails, Codeberg changed something docs/CODEBERG.md relies on: revisit the design
 * (and, once it exists, the Forgejo client). Everything here is public, so no account is needed.
 */
class CodebergLiveContractTest {
    private val api = "https://codeberg.org/api/v1"
    private val client = HttpClient(OkHttp) {
        expectSuccess = false
        defaultRequest { header(HttpHeaders.UserAgent, "Forgeline (+https://github.com/RoiArthurB/forgeline)") }
    }

    private suspend fun get(url: String): HttpResponse = client.get(url)

    private suspend fun HttpResponse.json(): JsonElement = Json.parseToJsonElement(bodyAsText())

    private suspend fun searchPage(query: String): Pair<HttpResponse, List<JsonObject>> {
        val response = get("$api/repos/search?$query")
        assertWithMessage("repos/search?$query").that(response.status.value).isEqualTo(200)
        return response to response.json().jsonObject.getValue("data").jsonArray.map { it.jsonObject }
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    @Test
    fun repository_search_pages_are_capped_at_50_and_announce_their_total() = runBlocking<Unit> {
        val (response, repos) = searchPage("mode=source&sort=stars&order=desc&limit=100")

        // A larger limit is cut to 50: the Trending job's page budget depends on it.
        assertThat(repos).hasSize(50)
        assertThat(response.headers["X-Total-Count"]?.toIntOrNull()).isGreaterThan(0)
        assertThat(response.headers[HttpHeaders.Link]).contains("""rel="next"""")
    }

    @Test
    fun the_stars_ranking_is_ordered_and_carries_what_trending_rows_show() = runBlocking<Unit> {
        val (_, repos) = searchPage("mode=source&sort=stars&order=desc&limit=50")

        val stars = repos.map { it.getValue("stars_count").jsonPrimitive.int }
        assertThat(stars).isInOrder(Comparator.reverseOrder<Int>())
        for (repo in repos) {
            val name = repo.string("full_name")
            // Keyed by the numeric id, so renames don't break the star history.
            assertWithMessage("$name id").that(repo.getValue("id").jsonPrimitive.content.toLongOrNull()).isNotNull()
            assertWithMessage("$name forks_count").that(repo["forks_count"]).isNotNull()
            assertWithMessage("$name created_at").that(repo.string("created_at")).isNotNull()
            assertWithMessage("$name owner avatar").that(repo["owner"]?.jsonObject?.string("avatar_url")).isNotNull()
            // mode=source leaves forks and mirrors out.
            assertWithMessage("$name fork").that(repo.getValue("fork").jsonPrimitive.content).isEqualTo("false")
            assertWithMessage("$name mirror").that(repo.getValue("mirror").jsonPrimitive.content).isEqualTo("false")
        }
    }

    @Test
    fun new_forks_are_listed_newest_first_with_their_parent() = runBlocking<Unit> {
        val (_, forks) = searchPage("mode=fork&sort=created&order=desc&limit=50")

        assertThat(forks).isNotEmpty()
        val created = forks.map { OffsetDateTime.parse(it.string("created_at")).toInstant() }
        assertThat(created).isInOrder(Comparator.reverseOrder<java.time.Instant>())
        assertThat(forks.count { it["parent"]?.jsonObject?.string("full_name") != null }).isEqualTo(forks.size)
    }

    @Test
    fun the_api_sort_key_for_age_is_created_not_the_web_uis_newest() = runBlocking<Unit> {
        assertThat(get("$api/repos/search?sort=newest&limit=1").status.value).isEqualTo(422)
    }

    @Test
    fun the_rate_limit_is_announced_in_headers() = runBlocking<Unit> {
        val response = get("$api/repos/search?limit=1")

        val policy = requireNotNull(response.headers["RateLimit-Policy"]) { "no RateLimit-Policy header" }
        val quota = Regex("""q=(\d+)""").find(policy)?.groupValues?.get(1)?.toInt()
        val window = Regex("""w=(\d+)""").find(policy)?.groupValues?.get(1)?.toInt()
        // docs/CODEBERG.md plans around 2000 requests per 10 minutes; a much tighter limit changes the plan.
        assertWithMessage(policy).that(quota).isAtLeast(500)
        assertWithMessage(policy).that(window).isAtMost(3600)
        assertThat(response.headers["RateLimit"]).isNotNull()
    }

    @Test
    fun activity_content_carries_the_number_and_title_as_a_json_array() = runBlocking<Unit> {
        // A busy repository: its latest activity always has issue and pull request events.
        val response = get("$api/repos/forgejo/forgejo/activities/feeds?limit=50")
        assertThat(response.status.value).isEqualTo(200)
        val activities = response.json().jsonArray.map { it.jsonObject }
        val numbered = setOf(
            "create_issue", "close_issue", "reopen_issue", "comment_issue",
            "create_pull_request", "close_pull_request", "reopen_pull_request", "merge_pull_request", "comment_pull",
        )

        val events = activities.filter { it.string("op_type") in numbered }
        assertWithMessage("issue or pull request events among ${activities.map { it.string("op_type") }}").that(events).isNotEmpty()
        for (event in events) {
            val content = Json.parseToJsonElement(event.string("content")!!) as JsonArray
            assertWithMessage("${event.string("op_type")}: $content").that(content.first().jsonPrimitive.content.toIntOrNull()).isNotNull()
        }
        val opened = events.filter { it.string("op_type") in setOf("create_issue", "create_pull_request") }
        for (event in opened) {
            val title = (Json.parseToJsonElement(event.string("content")!!) as JsonArray)[1].jsonPrimitive.content
            assertWithMessage("title of ${event.string("content")}").that(title).isNotEmpty()
        }
    }

    @Test
    fun a_users_own_activity_is_readable_without_an_account() = runBlocking<Unit> {
        // The Feed fans out to each followed person's feed. dnkl (foot, fuzzel) is a long-standing, active account.
        val response = get("$api/users/dnkl/activities/feeds?only-performed-by=true&limit=5")

        assertThat(response.status.value).isEqualTo(200)
        assertThat(response.headers["X-Total-Count"]?.toIntOrNull()).isGreaterThan(0)
        val actors = response.json().jsonArray.map { it.jsonObject.getValue("act_user").jsonObject.string("login") }
        assertThat(actors.toSet()).containsExactly("dnkl")
    }

    @Test
    fun stargazers_carry_no_star_time() = runBlocking<Unit> {
        val response = get("$api/repos/forgejo/forgejo/stargazers?limit=5")

        assertThat(response.status.value).isEqualTo(200)
        // Without a star time, star history can't be rebuilt: Trending has to measure it daily.
        val keys = response.json().jsonArray.flatMap { it.jsonObject.keys }.toSet()
        assertThat(keys).containsNoneOf("starred_at", "starred")
    }

    @Test
    fun sign_in_is_oauth_with_pkce_and_no_device_flow() = runBlocking<Unit> {
        val config = get("https://codeberg.org/.well-known/openid-configuration").json().jsonObject

        val grants = config.getValue("grant_types_supported").jsonArray.map { it.jsonPrimitive.content }
        assertThat(grants).contains("authorization_code")
        assertThat(grants).contains("refresh_token")
        assertThat(grants).doesNotContain("urn:ietf:params:oauth:grant-type:device_code")
        assertThat(config.keys).doesNotContain("device_authorization_endpoint")
        assertThat(config.getValue("code_challenge_methods_supported").jsonArray.map { it.jsonPrimitive.content }).contains("S256")
        assertThat(config.string("authorization_endpoint")).isEqualTo("https://codeberg.org/login/oauth/authorize")
        assertThat(config.string("token_endpoint")).isEqualTo("https://codeberg.org/login/oauth/access_token")
    }

    @Test
    fun notifications_exist_and_need_a_token() = runBlocking<Unit> {
        // 401, not 404: the endpoints are there, including the cheap "anything new?" check.
        assertThat(get("$api/notifications").status.value).isEqualTo(401)
        assertThat(get("$api/notifications/new").status.value).isEqualTo(401)
    }

    @Test
    fun the_signed_in_endpoints_keep_the_shape_the_inbox_design_assumes() = runBlocking<Unit> {
        // Checked against Codeberg's own API description, since calling them needs an account.
        val spec = get("https://codeberg.org/swagger.v1.json").json().jsonObject
        val definitions = spec.getValue("definitions").jsonObject
        val paths = spec.getValue("paths").jsonObject
        fun queryParams(path: String, method: String = "get") = paths.getValue(path).jsonObject.getValue(method).jsonObject["parameters"]
            ?.jsonArray.orEmpty().map { it.jsonObject }.filter { it.string("in") == "query" }.mapNotNull { it.string("name") }

        // No reason: "Needs you" is rebuilt from issue searches.
        val thread = definitions.getValue("NotificationThread").jsonObject.getValue("properties").jsonObject
        assertThat(thread.keys).containsAtLeast("id", "repository", "subject", "unread", "updated_at")
        assertThat(thread.keys).doesNotContain("reason")
        assertThat(queryParams("/repos/issues/search")).containsAtLeast("review_requested", "mentioned", "assigned", "created", "since")

        // No done: a thread can only change status, so done is a local tombstone.
        val threadPath = paths.getValue("/notifications/threads/{id}").jsonObject
        assertThat(threadPath.keys).doesNotContain("delete")
        assertThat(queryParams("/notifications/threads/{id}", "patch")).contains("to-status")
        assertThat(queryParams("/notifications")).containsAtLeast("all", "since", "page", "limit")

        // Unsubscribing goes through the issue's subscriptions.
        assertThat(paths.keys).contains("/repos/{owner}/{repo}/issues/{index}/subscriptions/{user}")
    }
}
