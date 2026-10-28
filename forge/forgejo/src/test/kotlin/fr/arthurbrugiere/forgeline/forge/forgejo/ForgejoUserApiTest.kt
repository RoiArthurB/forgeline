package fr.arthurbrugiere.forgeline.forge.forgejo

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.WorkKind
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ForgejoUserApiTest {
    private val codeberg = Codeberg()

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    @Test
    fun a_profile_counts_repositories_and_tells_organizations_apart() = runTest {
        val api = with(codeberg) {
            ForgejoUserApi(
                client {
                    val path = it.url.encodedPath
                    when {
                        path.startsWith("/api/v1/orgs/") -> status(HttpStatusCode.NotFound)
                        path.endsWith("/repos") -> json("[]", headers = mapOf("X-Total-Count" to "12"))
                        else -> json(fixture("user.json"))
                    }
                },
                ForgeInstance.Codeberg,
            )
        }

        val user = api.user(null, "earl-warren").value()

        assertThat(user.name).isEqualTo("Earl Warren")
        assertThat(user.followers).isEqualTo(50)
        assertThat(user.publicRepos).isEqualTo(12)
        assertThat(user.isOrganization).isFalse()
        assertThat(user.bio).isNull()
    }

    @Test
    fun a_public_email_is_a_link_and_the_server_s_noreply_stand_in_is_not() = runTest {
        fun api(email: String) = with(codeberg) {
            ForgejoUserApi(
                client {
                    when {
                        it.url.encodedPath.startsWith("/api/v1/orgs/") -> status(HttpStatusCode.NotFound)
                        it.url.encodedPath.endsWith("/repos") -> json("[]")
                        else -> json("""{"login":"ana","email":"$email"}""")
                    }
                },
                ForgeInstance.Codeberg,
            )
        }

        assertThat(api("ana@example.org").user(null, "ana").value().links.map { it.label }).containsExactly("ana@example.org")
        assertThat(api("ana@noreply.codeberg.org").user(null, "ana").value().links).isEmpty()
    }

    @Test
    fun an_organization_is_known_by_its_orgs_page() = runTest {
        val api = with(codeberg) {
            ForgejoUserApi(
                client { if (it.url.encodedPath.startsWith("/api/v1/orgs/")) json(fixture("org.json")) else json(fixture("user.json")) },
                ForgeInstance.Codeberg,
            )
        }

        assertThat(api.user(null, "forgejo").value().isOrganization).isTrue()
    }

    @Test
    fun a_persons_repositories_are_on_their_forge() = runTest {
        val repos = with(codeberg) { ForgejoUserApi(client { json(fixture("user_repos.json")) }, ForgeInstance.Codeberg) }
            .repos(null, "earl-warren").value()

        assertThat(repos.map { it.id.fullName }).containsExactly("earl-warren/website", "earl-warren/woodpecker-forgejo", "earl-warren/docker-buildx")
        assertThat(repos.all { it.id.forge == ForgeInstance.Codeberg }).isTrue()
    }

    @Test
    fun following_is_told_by_status_code() = runTest {
        val api = with(codeberg) { ForgejoUserApi(client { status(if (it.method == HttpMethod.Get) HttpStatusCode.NoContent else HttpStatusCode.NoContent) }, ForgeInstance.Codeberg) }

        assertThat(api.isFollowing("t", "alice").value()).isTrue()
        api.setFollowing("t", "alice", follow = false)

        assertThat(codeberg.requests.map { it.method to it.url.encodedPath }).containsExactly(
            HttpMethod.Get to "/api/v1/user/following/alice",
            HttpMethod.Delete to "/api/v1/user/following/alice",
        ).inOrder()
    }

    @Test
    fun stars_are_asked_one_repository_at_a_time() = runTest {
        val starred = RepoId("forgejo", "forgejo", ForgeInstance.Codeberg)
        val other = RepoId("alice", "tool", ForgeInstance.Codeberg)
        val api = with(codeberg) { ForgejoStarApi(client { status(if (it.url.encodedPath.contains("forgejo")) HttpStatusCode.NoContent else HttpStatusCode.NotFound) }, ForgeInstance.Codeberg) }

        assertThat(api.starredStatus("t", listOf(starred, other)).value()).containsExactly(starred, true, other, false)
    }

    @Test
    fun searches_read_repositories_and_people() = runTest {
        val api = with(codeberg) {
            ForgejoSearchApi(
                client { json(fixture(if (it.url.encodedPath.startsWith("/api/v1/users")) "search_users.json" else "search_repos.json"), headers = mapOf("X-Total-Count" to "240")) },
                ForgeInstance.Codeberg,
            )
        }

        val repos = api.repositories(null, "forgejo").value()
        val users = api.users(null, "forgejo").value()

        assertThat(repos.items.first().id).isEqualTo(RepoId("rindeal", "__openpgp-proof-forgejo", ForgeInstance.Codeberg))
        assertThat(repos.totalCount).isEqualTo(240)
        assertThat(users.items.map { it.login }).contains("0xllx0-forgejo")
        // People found on Codeberg open on Codeberg.
        assertThat(users.items.map { it.forge }.toSet()).containsExactly(ForgeInstance.Codeberg)
    }

    @Test
    fun a_topic_asks_for_repositories_tagged_with_it_the_most_starred_first() = runTest {
        val api = with(codeberg) { ForgejoSearchApi(client { json(fixture("search_repos.json"), headers = mapOf("X-Total-Count" to "75")) }, ForgeInstance.Codeberg) }

        val page = api.topic(null, "SelfHosted").value()

        assertThat(page.items.first().id.forge).isEqualTo(ForgeInstance.Codeberg)
        assertThat(page.totalCount).isEqualTo(75)
        val url = codeberg.requests.single().url
        assertThat(url.encodedPath).isEqualTo("/api/v1/repos/search")
        assertThat(url.parameters["q"]).isEqualTo("selfhosted")
        assertThat(url.parameters["topic"]).isEqualTo("true")
        assertThat(url.parameters["sort"]).isEqualTo("stars")
        assertThat(url.parameters["order"]).isEqualTo("desc")
    }

    @Test
    fun issue_search_results_carry_their_repository() = runTest {
        // The search answers issues like a repository's list, plus each one's repository.
        val issue = """[{"number":3,"title":"Crash","state":"open","created_at":"2026-09-29T15:09:06+02:00","repository":{"owner":"alice","name":"tool"}}]"""
        val results = with(codeberg) { ForgejoSearchApi(client { json(issue) }, ForgeInstance.Codeberg) }.issues("t", "crash").value()

        assertThat(results.items.single().repo).isEqualTo(RepoId("alice", "tool", ForgeInstance.Codeberg))
    }

    @Test
    fun starring_puts_and_unstarring_deletes() = runTest {
        val repo = RepoId("forgejo", "forgejo", ForgeInstance.Codeberg)
        val api = with(codeberg) { ForgejoStarApi(client { status(HttpStatusCode.NoContent) }, ForgeInstance.Codeberg) }

        assertThat(api.setStarred("t", repo, starred = true)).isEqualTo(ForgeResult.Success(Unit))
        assertThat(api.setStarred("t", repo, starred = false)).isEqualTo(ForgeResult.Success(Unit))

        assertThat(codeberg.requests.map { it.method to it.url.encodedPath }).containsExactly(
            HttpMethod.Put to "/api/v1/user/starred/forgejo/forgejo",
            HttpMethod.Delete to "/api/v1/user/starred/forgejo/forgejo",
        ).inOrder()
        assertThat(codeberg.requests.map { it.headers["Authorization"] }.toSet()).containsExactly("token t")
    }

    @Test
    fun a_refused_star_is_an_error_and_an_unclear_answer_leaves_the_state_unknown() = runTest {
        val repo = RepoId("forgejo", "forgejo", ForgeInstance.Codeberg)
        val api = with(codeberg) { ForgejoStarApi(client { status(HttpStatusCode.InternalServerError) }, ForgeInstance.Codeberg) }

        assertThat(api.setStarred("t", repo, starred = true)).isInstanceOf(ForgeResult.Failure::class.java)
        // Unknown, not "not starred": the button then doesn't pretend either way.
        assertThat(api.starredStatus("t", listOf(repo)).value()).isEmpty()
    }

    @Test
    fun a_persons_stars_are_on_their_forge_and_need_an_account_on_codeberg() = runTest {
        val signedIn = with(codeberg) { ForgejoUserApi(client { json(fixture("user_repos.json")) }, ForgeInstance.Codeberg) }
            .starred("t", "alice").value()
        assertThat(signedIn).isNotEmpty()
        assertThat(signedIn.all { it.id.forge == ForgeInstance.Codeberg }).isTrue()
        assertThat(codeberg.requests.last().url.encodedPath).isEqualTo("/api/v1/users/alice/starred")

        // Codeberg answered 401 to anonymous star lists (2026-09-29).
        val anonymous = with(codeberg) { ForgejoUserApi(client { status(HttpStatusCode.Unauthorized) }, ForgeInstance.Codeberg) }
            .starred(null, "alice")
        assertThat(anonymous).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
    }

    private val forgejoRepo = RepoId("forgejo", "forgejo", ForgeInstance.Codeberg)

    @Test
    fun a_repository_is_watched_when_its_subscription_says_so_and_not_when_there_is_none() = runTest {
        val watched = with(codeberg) { ForgejoStarApi(client { json("""{"subscribed":true,"ignored":false}""") }, ForgeInstance.Codeberg) }
            .isWatching("t", forgejoRepo)
        assertThat(watched).isEqualTo(ForgeResult.Success(true))
        assertThat(codeberg.requests.last().url.encodedPath).isEqualTo("/api/v1/repos/forgejo/forgejo/subscription")

        // Forgejo answers 404 when the account doesn't watch the repository.
        val unwatched = with(codeberg) { ForgejoStarApi(client { status(HttpStatusCode.NotFound) }, ForgeInstance.Codeberg) }
            .isWatching("t", forgejoRepo)
        assertThat(unwatched).isEqualTo(ForgeResult.Success(false))
    }

    @Test
    fun watching_puts_and_no_longer_watching_deletes() = runTest {
        val api = with(codeberg) { ForgejoStarApi(client { json("""{"subscribed":true}""") }, ForgeInstance.Codeberg) }

        assertThat(api.setWatching("t", forgejoRepo, watching = true)).isEqualTo(ForgeResult.Success(Unit))
        assertThat(api.setWatching("t", forgejoRepo, watching = false)).isEqualTo(ForgeResult.Success(Unit))

        assertThat(codeberg.requests.map { it.method to it.url.encodedPath }).containsExactly(
            HttpMethod.Put to "/api/v1/repos/forgejo/forgejo/subscription",
            HttpMethod.Delete to "/api/v1/repos/forgejo/forgejo/subscription",
        ).inOrder()
    }

    @Test
    fun a_fork_says_where_the_copy_is_on_the_same_forge() = runTest {
        val fork = with(codeberg) { ForgejoStarApi(client { json("""{"name":"forgejo","owner":{"login":"me"}}""") }, ForgeInstance.Codeberg) }
            .fork("t", forgejoRepo)

        assertThat(fork).isEqualTo(ForgeResult.Success(RepoId("me", "forgejo", ForgeInstance.Codeberg)))
        assertThat(codeberg.requests.single().method).isEqualTo(HttpMethod.Post)
        assertThat(codeberg.requests.single().url.encodedPath).isEqualTo("/api/v1/repos/forgejo/forgejo/forks")
    }

    @Test
    fun a_fork_the_account_already_has_is_refused() = runTest {
        // Forgejo answers 409 to a second fork.
        val fork = with(codeberg) { ForgejoStarApi(client { status(HttpStatusCode.Conflict) }, ForgeInstance.Codeberg) }.fork("t", forgejoRepo)

        assertThat(fork).isEqualTo(ForgeResult.Failure(ForgeError.Http(409, null)))
    }

    @Test
    fun each_kind_of_work_asks_the_search_for_what_is_open_and_the_signed_in_person_s() = runTest {
        val issue = """[{"number":3,"title":"Crash","state":"open","created_at":"2026-09-29T15:09:06+02:00","repository":{"owner":"alice","name":"tool"}}]"""
        val api = with(codeberg) { ForgejoSearchApi(client { json(issue) }, ForgeInstance.Codeberg) }

        val found = api.work("t", "alice", WorkKind.REVIEW_REQUESTED).value()
        api.work("t", "alice", WorkKind.OWN_PULL_REQUESTS)
        api.work("t", "alice", WorkKind.ASSIGNED)

        assertThat(found.single().repo).isEqualTo(RepoId("alice", "tool", ForgeInstance.Codeberg))
        val asked = codeberg.requests.map { it.url.parameters }
        assertThat(codeberg.requests.map { it.url.encodedPath }.toSet()).containsExactly("/api/v1/repos/issues/search")
        assertThat(asked.map { it["state"] }.toSet()).containsExactly("open")
        // Most recently touched first, as on the other forges: Forgejo's own order is by creation.
        assertThat(asked.map { it["sort"] }.toSet()).containsExactly("recentupdate")
        assertThat(asked.map { Triple(it["review_requested"], it["created"], it["assigned"]) }).containsExactly(
            Triple("true", null, null),
            Triple(null, "true", null),
            Triple(null, null, "true"),
        ).inOrder()
        // Reviews and one's own are pull requests; what is assigned may be either.
        assertThat(asked.map { it["type"] }).containsExactly("pulls", "pulls", null).inOrder()
        assertThat(codeberg.requests.map { it.headers["Authorization"] }.toSet()).containsExactly("token t")
    }

    @Test
    fun work_the_server_refuses_is_a_failure() = runTest {
        val api = with(codeberg) { ForgejoSearchApi(client { status(HttpStatusCode.InternalServerError) }, ForgeInstance.Codeberg) }

        assertThat(api.work("t", "alice", WorkKind.ASSIGNED)).isInstanceOf(ForgeResult.Failure::class.java)
    }

    @Test
    fun work_is_given_the_time_codeberg_takes_to_search_every_repository() = runTest {
        // Regression: Codeberg answers these in 7 to 11 s, and the 10 s every call is given made them fail as "offline".
        val api = with(codeberg) { ForgejoSearchApi(client { json("[]") }, ForgeInstance.Codeberg) }

        api.work("t", "alice", WorkKind.REVIEW_REQUESTED)
        api.issues("t", "crash")

        val patience = codeberg.requests.map { it.getCapabilityOrNull(io.ktor.client.plugins.HttpTimeoutCapability)?.socketTimeoutMillis }
        assertThat(patience).containsExactly(30_000L, null).inOrder()
    }

    @Test
    fun every_reachable_repository_is_read_fifty_at_a_time() = runTest {
        val pages = java.util.concurrent.CopyOnWriteArrayList<String>()
        val repo = { name: String -> """{"name":"$name","owner":{"login":"ana"},"updated_at":"2026-10-0${name.last()}T10:00:00Z"}""" }
        val api = with(codeberg) {
            ForgejoUserApi(
                client {
                    when (it.url.encodedPath) {
                        "/api/v1/user/repos" -> {
                            val page = it.url.parameters["page"]!!
                            pages += page
                            json(if (page == "1") "[${repo("r1")}]" else "[${repo("r2")}]", headers = mapOf("X-Total-Count" to "51"))
                        }
                        else -> json("""[{"username":"forgejo","avatar_url":"https://codeberg.org/avatars/forgejo"}]""")
                    }
                },
                ForgeInstance.Codeberg,
            )
        }

        val repos = api.reachableRepos("tok").value()

        assertThat(pages).containsExactly("1", "2")
        // Forgejo can't sort this list: it's sorted here, latest first.
        assertThat(repos.map { it.id.name }).containsExactly("r2", "r1").inOrder()
        assertThat(api.organizations("tok").value().map { it.login }).containsExactly("forgejo")
    }
}
