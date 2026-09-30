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

    @Test
    fun times_with_an_offset_read_as_instants() {
        assertThat(instant("2026-09-30T00:12:14+02:00").toString()).isEqualTo("2026-09-29T22:12:14Z")
        assertThat(instant("not a date")).isNull()
        assertThat(instant(null)).isNull()
    }
}
