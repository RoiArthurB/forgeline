package fr.arthurbrugiere.forgeline.forge.tangled

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoId
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

/** Signing in to Tangled, starring and following: what goes to the person's own server, and what is asked of the index. */
class TangledAccountTest {
    private fun <T> ForgeResult<T>.value(): T = (this as? ForgeResult.Success)?.value ?: error("$this")

    private val now = Instant.parse("2026-11-02T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val core = RepoId("tangled.org", "core", ForgeInstance.Tangled)

    private fun jwt(sub: String, exp: Long = now.epochSecond + 7_200): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("""{"alg":"ES256K"}""".toByteArray()) + "." +
            enc.encodeToString("""{"scope":"com.atproto.appPass","sub":"$sub","iat":${now.epochSecond},"exp":$exp}""".toByteArray()) + ".c2ln"
    }

    private val me = "did:plc:meme7t2adsemcrrd4snkeqli"
    private val token = jwt(me)

    private fun didDocument(handle: String, pds: String): (HttpRequestData) -> String? = {
        """{"id":"x","alsoKnownAs":["at://$handle"],"service":[{"id":"#atproto_pds","type":"AtprotoPersonalDataServer","serviceEndpoint":"$pds"}]}"""
    }

    /** The signed-in person (on their own server, pds.example) and tangled.org's core, as the index knows them. */
    private fun fake(extra: Map<String, (HttpRequestData) -> String?> = emptyMap()) = FakeTangled(
        mapOf(
            "com.atproto.identity.resolveHandle" to { r -> if (r.url.parameters["handle"] == "tangled.org") """{"did":"$OWNER_DID"}""" else """{"did":"$me"}""" },
            "plc.directory/$me" to didDocument("me.example", "https://pds.example/"),
            "plc.directory/$OWNER_DID" to didDocument("tangled.org", "https://tngl.sh"),
            "org.tangled.temp.repo.getRepoByName" to coreRepo,
            "com.atproto.repo.createRecord" to { """{"uri":"at://$me/org.tangled.feed.star/3new","cid":"bafy"}""" },
            "com.atproto.repo.putRecord" to { """{"uri":"at://$me/org.tangled.graph.follow/$OWNER_DID","cid":"bafy"}""" },
            "com.atproto.repo.deleteRecord" to { "{}" },
        ) + extra,
    )

    private fun apis(fake: FakeTangled): Triple<TangledAccount, TangledStarApi, TangledUserApi> {
        val identity = TangledIdentity(fake.client, fake.xrpc)
        val account = TangledAccount(fake.xrpc, identity, clock)
        val repos = TangledRepoApi(fake.xrpc, identity)
        return Triple(account, TangledStarApi(fake.xrpc, account, repos, clock), TangledUserApi(fake.xrpc, identity, TangledFollows(fake.xrpc, account, identity, clock)))
    }

    @Test
    fun signing_in_opens_a_session_with_the_account_s_own_server() = runTest {
        val fake = fake(
            mapOf(
                "com.atproto.server.createSession" to {
                    """{"did":"$me","handle":"me.example","accessJwt":"$token","refreshJwt":"${jwt(me, now.epochSecond + 90 * 86_400)}","active":true}"""
                },
            ),
        )

        val tokens = apis(fake).first.createSession("@me.example", "abcd-efgh-ijkl-mnop").value()

        assertThat(tokens.accessToken).isEqualTo(token)
        assertThat(tokens.refreshToken).isNotNull()
        // Read from the access token's own expiry: two hours.
        assertThat(tokens.expiresInSeconds).isEqualTo(7_200)
        val sent = fake.to("com.atproto.server.createSession").single()
        assertThat(sent.url.host).isEqualTo("pds.example")
        assertThat(sent.method).isEqualTo(HttpMethod.Post)
        assertThat(sent.text).contains(""""identifier":"me.example"""")
        assertThat(sent.text).contains(""""password":"abcd-efgh-ijkl-mnop"""")
    }

    @Test
    fun an_account_on_bluesky_s_servers_signs_in_at_its_entryway() = runTest {
        val fake = fake(
            mapOf(
                "plc.directory/$me" to didDocument("me.bsky.social", "https://morel.us-east.host.bsky.network"),
                "com.atproto.server.createSession" to { """{"did":"$me","handle":"me.bsky.social","accessJwt":"$token","refreshJwt":"r"}""" },
            ),
        )

        apis(fake).first.createSession("me.bsky.social", "pw").value()

        assertThat(fake.to("com.atproto.server.createSession").single().url.host).isEqualTo("bsky.social")
    }

    @Test
    fun a_wrong_password_is_a_refused_sign_in() = runTest {
        val fake = FakeTangled(
            mapOf(
                "com.atproto.identity.resolveHandle" to { """{"did":"$me"}""" },
                "plc.directory/$me" to didDocument("me.example", "https://pds.example"),
            ),
            status = { if (it == "com.atproto.server.createSession") 401 else null },
        )

        val result = TangledAccount(fake.xrpc, TangledIdentity(fake.client, fake.xrpc), clock).createSession("me.example", "nope")

        assertThat((result as ForgeResult.Failure).error).isEqualTo(ForgeError.Unauthorized)
    }

    @Test
    fun a_session_is_renewed_where_its_account_lives() = runTest {
        val refresh = jwt(me, now.epochSecond + 90 * 86_400)
        val fake = fake(mapOf("com.atproto.server.refreshSession" to { """{"did":"$me","handle":"me.example","accessJwt":"$token","refreshJwt":"next"}""" }))

        val tokens = apis(fake).first.refreshSession(refresh).value()

        assertThat(tokens.refreshToken).isEqualTo("next")
        val sent = fake.to("com.atproto.server.refreshSession").single()
        assertThat(sent.url.host).isEqualTo("pds.example")
        assertThat(sent.headers[HttpHeaders.Authorization]).isEqualTo("Bearer $refresh")
    }

    @Test
    fun the_signed_in_person_is_who_their_server_says() = runTest {
        val fake = fake(mapOf("com.atproto.server.getSession" to { """{"did":"$me","handle":"me.example"}""" }))

        val user = apis(fake).first.user(token).value()

        assertThat(user.login).isEqualTo("me.example")
        assertThat(fake.to("com.atproto.server.getSession").single().headers[HttpHeaders.Authorization]).isEqualTo("Bearer $token")
    }

    @Test
    fun something_that_isn_t_a_session_token_is_refused_without_asking() = runTest {
        val fake = fake()

        assertThat((apis(fake).first.user("ghp_not_a_jwt") as ForgeResult.Failure).error).isEqualTo(ForgeError.Unauthorized)
        assertThat(fake.requests).isEmpty()
    }

    @Test
    fun a_star_is_a_record_on_the_person_s_server_naming_the_repository_s_did() = runTest {
        val fake = fake() // getStar unknown: answers RepoNotFound, i.e. no star yet.

        apis(fake).second.setStarred(token, core, starred = true).value()

        val sent = fake.to("com.atproto.repo.createRecord").single()
        assertThat(sent.url.host).isEqualTo("pds.example")
        assertThat(sent.headers[HttpHeaders.Authorization]).isEqualTo("Bearer $token")
        assertThat(sent.text).contains(""""repo":"$me"""")
        assertThat(sent.text).contains(""""collection":"org.tangled.feed.star"""")
        assertThat(sent.text).contains(""""subject":{"${'$'}type":"org.tangled.feed.star#repo","identity":"$REPO_DID"}""")
        assertThat(sent.text).contains(""""createdAt":"2026-11-02T12:00:00Z"""")
        assertThat(fake.asked).contains("org.tangled.feed.getStar?actor=$me&subject=$REPO_DID")
    }

    @Test
    fun starring_what_is_starred_writes_nothing() = runTest {
        val fake = fake(mapOf("org.tangled.feed.getStar" to { """{"uri":"at://$me/org.tangled.feed.star/3old"}""" }))

        apis(fake).second.setStarred(token, core, starred = true).value()

        assertThat(fake.to("com.atproto.repo.createRecord")).isEmpty()
    }

    @Test
    fun unstarring_deletes_the_star_the_index_names_former_ones_too() = runTest {
        val fake = fake(mapOf("org.tangled.feed.getStar" to { """{"uri":"at://$me/sh.tangled.feed.star/3old"}""" }))

        apis(fake).second.setStarred(token, core, starred = false).value()

        val sent = fake.to("com.atproto.repo.deleteRecord").single()
        assertThat(sent.text).contains(""""collection":"sh.tangled.feed.star"""")
        assertThat(sent.text).contains(""""rkey":"3old"""")
    }

    @Test
    fun a_star_the_index_hasn_t_read_yet_is_found_on_the_person_s_server() = runTest {
        val fake = fake(
            mapOf(
                "com.atproto.repo.listRecords" to {
                    """{"records":[
                        {"uri":"at://$me/org.tangled.feed.star/3other","cid":"b","value":{"subject":{"${'$'}type":"org.tangled.feed.star#repo","identity":"did:plc:other"},"createdAt":"2026-11-01T00:00:00Z"}},
                        {"uri":"at://$me/org.tangled.feed.star/3fresh","cid":"b","value":{"subject":{"${'$'}type":"org.tangled.feed.star#repo","identity":"$REPO_DID"},"createdAt":"2026-11-02T11:59:00Z"}}
                    ]}"""
                },
            ),
        )

        apis(fake).second.setStarred(token, core, starred = false).value()

        assertThat(fake.to("com.atproto.repo.listRecords").single().url.host).isEqualTo("pds.example")
        assertThat(fake.to("com.atproto.repo.deleteRecord").single().text).contains(""""rkey":"3fresh"""")
    }

    @Test
    fun starred_status_is_asked_of_the_index_for_each_repository() = runTest {
        val fake = fake(mapOf("org.tangled.feed.getStar" to { """{"uri":"at://$me/org.tangled.feed.star/3old"}""" }))

        val status = apis(fake).second.starredStatus(token, listOf(core)).value()

        assertThat(status).containsExactly(core, true)
    }

    @Test
    fun a_follow_is_keyed_by_whom_it_follows() = runTest {
        val fake = fake()

        assertThat(apis(fake).third.isFollowing(token, "tangled.org").value()).isFalse()
        apis(fake).third.setFollowing(token, "tangled.org", follow = true).value()

        val sent = fake.to("com.atproto.repo.putRecord").single()
        assertThat(sent.url.host).isEqualTo("pds.example")
        assertThat(sent.text).contains(""""collection":"org.tangled.graph.follow"""")
        assertThat(sent.text).contains(""""rkey":"$OWNER_DID"""")
        assertThat(sent.text).contains(""""record":{"${'$'}type":"org.tangled.graph.follow","createdAt":"2026-11-02T12:00:00Z"}""")
    }

    @Test
    fun unfollowing_deletes_the_follow_the_index_names() = runTest {
        val fake = fake(mapOf("org.tangled.graph.getFollow" to { """{"uri":"at://$me/sh.tangled.graph.follow/3legacy"}""" }))

        assertThat(apis(fake).third.isFollowing(token, "tangled.org").value()).isTrue()
        apis(fake).third.setFollowing(token, "tangled.org", follow = false).value()

        val sent = fake.to("com.atproto.repo.deleteRecord").single()
        assertThat(sent.text).contains(""""collection":"sh.tangled.graph.follow"""")
        assertThat(sent.text).contains(""""rkey":"3legacy"""")
    }

    @Test
    fun someone_s_stars_list_the_repositories_by_owner_handle_and_name() = runTest {
        val fake = fake(
            mapOf(
                "org.tangled.feed.listStarsBy" to {
                    """{"items":[
                        {"uri":"at://$me/org.tangled.feed.star/3a","value":{"subject":{"${'$'}type":"org.tangled.feed.star#repo","identity":"$REPO_DID"},"createdAt":"2026-11-02T11:00:00Z"}},
                        {"uri":"at://$me/sh.tangled.feed.star/3b","value":{"subject":{"did":"did:plc:gone"},"createdAt":"2026-10-01T11:00:00Z"}}
                    ]}"""
                },
                "org.tangled.repo.getRepoByRepoDid" to { r -> if (r.url.parameters["repoDid"] == REPO_DID) coreRepo(r) else null },
            ),
        )

        val starred = apis(fake).third.starred(null, "me.example").value()

        assertThat(starred.map { it.id }).containsExactly(core)
        assertThat(starred.single().description).isEqualTo("Monorepo for Tangled")
    }

    @Test
    fun a_starred_repository_kept_as_a_manifest_is_listed_by_its_declared_owner() = runTest {
        val fake = fake(
            mapOf(
                "org.tangled.feed.listStarsBy" to {
                    """{"items":[{"uri":"at://$me/org.tangled.feed.star/3a","value":{"subject":{"${'$'}type":"org.tangled.feed.star#repo","identity":"$REPO_DID"},"createdAt":"2026-11-02T11:00:00Z"}}]}"""
                },
                "org.tangled.repo.getRepoByRepoDid" to { r -> if (r.url.parameters["repoDid"] == REPO_DID) coreManifest(r) else null },
            ),
        )

        val starred = apis(fake).third.starred(null, "me.example").value()

        assertThat(starred.map { it.id }).containsExactly(core)
    }
}
