package fr.arthurbrugiere.forgeline.forge.forgejo

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeType
import fr.arthurbrugiere.forgeline.core.model.RepoId
import io.ktor.client.engine.mock.MockEngine
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

/** What every Forgejo call shares: where it goes, how it signs in, and how failures read. */
class ForgejoHttpTest {
    private val codeberg = Codeberg()

    @Test
    fun a_self_hosted_instance_is_called_on_its_own_host_with_its_own_token() = runTest {
        val selfHosted = ForgeInstance(ForgeType.FORGEJO, "git.example.org")
        val api = with(codeberg) { ForgejoRepoApi(client { json(fixture("repo.json")) }, selfHosted) }

        val repo = (api.repo("s3cret", RepoId("team", "tool", selfHosted)) as ForgeResult.Success).value

        val request = codeberg.requests.single()
        assertThat(request.url.toString()).isEqualTo("https://git.example.org/api/v1/repos/team/tool")
        assertThat(request.headers["Authorization"]).isEqualTo("token s3cret")
        assertThat(repo.id.forge).isEqualTo(selfHosted)
    }

    @Test
    fun signed_out_no_authorization_header_is_sent() = runTest {
        with(codeberg) { ForgejoRepoApi(client { json(fixture("repo.json")) }, ForgeInstance.Codeberg) }
            .repo(null, RepoId("forgejo", "forgejo", ForgeInstance.Codeberg))

        assertThat(codeberg.requests.single().headers["Authorization"]).isNull()
    }

    @Test
    fun a_network_failure_is_a_network_error_not_a_crash() = runTest {
        val api = ForgejoRepoApi(forgejoHttpClient(MockEngine { throw IOException("no route to host") }), ForgeInstance.Codeberg)

        assertThat(api.repo(null, RepoId("forgejo", "forgejo", ForgeInstance.Codeberg))).isEqualTo(ForgeResult.Failure(ForgeError.Network))
    }

    @Test
    fun an_expired_token_is_unauthorized_and_an_error_keeps_forgejos_message() = runTest {
        val repo = RepoId("forgejo", "forgejo", ForgeInstance.Codeberg)
        val unauthorized = with(codeberg) { ForgejoRepoApi(client { status(HttpStatusCode.Unauthorized) }, ForgeInstance.Codeberg) }
        val forbidden = with(codeberg) {
            ForgejoRepoApi(client { json("""{"message":"repo is archived"}""", HttpStatusCode.Forbidden) }, ForgeInstance.Codeberg)
        }

        assertThat(unauthorized.repo("t", repo)).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
        assertThat(forbidden.repo("t", repo)).isEqualTo(ForgeResult.Failure(ForgeError.Http(403, "repo is archived")))
    }

    // Codeberg's answer to a signed-out repository search (2026-11-12): 429, with most of the quota left.
    private val refusal = mapOf("RateLimit-Policy" to "\"baseline\";q=2000;w=600", "RateLimit" to "\"baseline\";r=1999;t=600")

    @Test
    fun a_signed_out_search_turned_away_with_quota_left_wants_an_account_not_patience() = runTest {
        val api = with(codeberg) { ForgejoSearchApi(client { json("{}", HttpStatusCode.TooManyRequests, refusal) }, ForgeInstance.Codeberg) }

        assertThat(api.repositories(null, "kotlin", 1)).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
    }

    @Test
    fun signed_in_the_same_answer_is_a_rate_limit_whose_end_is_announced() = runTest {
        val api = with(codeberg) { ForgejoSearchApi(client { json("{}", HttpStatusCode.TooManyRequests, refusal) }, ForgeInstance.Codeberg) }
        val before = java.time.Instant.now().epochSecond

        val error = (api.repositories("t", "kotlin", 1) as ForgeResult.Failure).error as ForgeError.RateLimited

        assertThat(error.resetAtEpochSeconds).isAtLeast(before + 600)
    }

    @Test
    fun a_spent_quota_is_a_rate_limit_read_from_either_way_forgejo_announces_it() = runTest {
        val spent = mapOf("RateLimit" to "\"baseline\";r=0;t=42")
        val now = with(codeberg) { ForgejoSearchApi(client { json("{}", HttpStatusCode.TooManyRequests, spent) }, ForgeInstance.Codeberg) }
        val before = with(codeberg) {
            ForgejoSearchApi(client { json("{}", HttpStatusCode.TooManyRequests, mapOf("RateLimit-Remaining" to "0", "RateLimit-Reset" to "42")) }, ForgeInstance.Codeberg)
        }
        val start = java.time.Instant.now().epochSecond

        for (api in listOf(now, before)) {
            val error = (api.repositories(null, "kotlin", 1) as ForgeResult.Failure).error as ForgeError.RateLimited
            assertThat(error.resetAtEpochSeconds).isIn(com.google.common.collect.Range.closed(start + 42, start + 44))
        }
    }

    @Test
    fun the_tightest_of_several_announced_limits_counts() = runTest {
        val api = with(codeberg) {
            ForgejoSearchApi(client { json("{}", HttpStatusCode.TooManyRequests, mapOf("RateLimit" to "\"baseline\";r=1999;t=600, \"search\";r=0;t=30")) }, ForgeInstance.Codeberg)
        }

        assertThat((api.repositories(null, "kotlin", 1) as ForgeResult.Failure).error).isInstanceOf(ForgeError.RateLimited::class.java)
    }

    @Test
    fun times_with_an_offset_read_as_instants() {
        assertThat(instant("2026-09-30T00:12:14+02:00").toString()).isEqualTo("2026-09-29T22:12:14Z")
        assertThat(instant("not a date")).isNull()
        assertThat(instant(null)).isNull()
    }
}
