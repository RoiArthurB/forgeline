package fr.arthurbrugiere.forgeline.forge.forgejo

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
    }

    @Test
    fun issue_search_results_carry_their_repository() = runTest {
        // The search answers issues like a repository's list, plus each one's repository.
        val issue = """[{"number":3,"title":"Crash","state":"open","created_at":"2026-09-29T15:09:06+02:00","repository":{"owner":"alice","name":"tool"}}]"""
        val results = with(codeberg) { ForgejoSearchApi(client { json(issue) }, ForgeInstance.Codeberg) }.issues("t", "crash").value()

        assertThat(results.items.single().repo).isEqualTo(RepoId("alice", "tool", ForgeInstance.Codeberg))
    }
}
