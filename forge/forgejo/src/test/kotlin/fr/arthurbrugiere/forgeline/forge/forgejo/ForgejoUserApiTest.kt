package fr.arthurbrugiere.forgeline.forge.forgejo

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoId
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
}
