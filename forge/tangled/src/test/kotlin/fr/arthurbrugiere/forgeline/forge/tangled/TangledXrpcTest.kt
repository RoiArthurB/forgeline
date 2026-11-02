package fr.arthurbrugiere.forgeline.forge.tangled

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** How the generated XRPC clients' answers read to the app: failures, and answers their lexicons don't allow. */
class TangledXrpcTest {
    private val core = RepoId("tangled.org", "core", ForgeInstance.Tangled)

    @Test
    fun a_handle_no_account_can_have_is_not_found_without_asking() = runTest {
        val fake = FakeTangled(emptyMap())

        val result = TangledIdentity(fake.client, fake.xrpc).did("not a handle")

        assertThat((result as ForgeResult.Failure).error).isEqualTo(ForgeError.Http(404, "Not a handle: not a handle"))
        assertThat(fake.requests).isEmpty()
    }

    @Test
    fun a_spent_limit_says_when_it_comes_back() = runTest {
        val client = tangledHttpClient(
            MockEngine {
                respond(
                    """{"error":"RateLimitExceeded","message":"Rate Limit Exceeded"}""",
                    HttpStatusCode.TooManyRequests,
                    headersOf(HttpHeaders.ContentType to listOf("application/json"), "ratelimit-reset" to listOf("1762100000")),
                )
            },
        )

        val result = TangledTrendingApi(TangledXrpc(client), TangledIdentity(client)).trending(TrendingPeriod.DAILY)

        assertThat((result as ForgeResult.Failure).error).isEqualTo(ForgeError.RateLimited(1762100000))
    }

    @Test
    fun an_expired_token_asks_to_sign_in_again() = runTest {
        val fake = FakeTangled(emptyMap(), status = { 401 })

        val result = TangledRepoApi(fake.xrpc, TangledIdentity(fake.client, fake.xrpc)).repo(null, core)

        assertThat((result as ForgeResult.Failure).error).isEqualTo(ForgeError.Unauthorized)
    }

    @Test
    fun an_answer_its_lexicon_does_not_allow_is_unreadable_rather_than_a_crash() = runTest {
        // getTrending promises at most four repositories, each with its creation time: this one has none.
        val fake = FakeTangled(
            mapOf("org.tangled.actor.getTrending" to { """{"people":[],"repos":[{"did":"$REPO_DID","owner":{"did":"$OWNER_DID","handle":"tangled.org"},"slug":"core"}]}""" }),
        )

        val result = TangledTrendingApi(fake.xrpc, TangledIdentity(fake.client, fake.xrpc)).trending(TrendingPeriod.DAILY)

        assertThat((result as ForgeResult.Failure).error).isEqualTo(ForgeError.Unreadable)
    }

    @Test
    fun a_repository_record_of_either_kind_tells_a_fork() = runTest {
        // Former records name their source by address, current ones with an object.
        val forked: (String) -> (io.ktor.client.request.HttpRequestData) -> String? = { source ->
            {
                """{"uri":"at://$OWNER_DID/sh.tangled.repo/3l","value":{"name":"core","repoDid":"$REPO_DID","source":$source},"git":{"defaultBranch":"master","isEmpty":false}}"""
            }
        }
        for (source in listOf(""""at://did:plc:abc/sh.tangled.repo/3k"""", """{"${'$'}type":"org.tangled.repo.defs#sourceTangled","repo":"did:plc:abc"}""")) {
            val fake = FakeTangled(mapOf("com.atproto.identity.resolveHandle" to resolved, "org.tangled.temp.repo.getRepoByName" to forked(source)))

            val details = TangledRepoApi(fake.xrpc, TangledIdentity(fake.client, fake.xrpc)).repo(null, core)

            assertThat((details as ForgeResult.Success).value.isFork).isTrue()
        }
    }
}
