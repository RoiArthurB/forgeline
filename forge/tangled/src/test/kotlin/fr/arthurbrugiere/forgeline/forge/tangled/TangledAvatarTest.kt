package fr.arthurbrugiere.forgeline.forge.tangled

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import io.ktor.client.request.HttpRequestData
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Regression: no one on Tangled had a picture. Tangled's index names a profile picture only by its blob's CID, and its
 * web app serves it through a signed address of its own; the picture is read as Tangled's avatar service reads it
 * (`avatar/src/index.js` in tangled.org/core, read 2026-11-05): the blob in the person's Tangled profile, from their
 * own server, and their Bluesky picture when that profile has none.
 */
class TangledAvatarTest {
    private fun <T> ForgeResult<T>.value(): T = (this as? ForgeResult.Success)?.value ?: error("$this")

    private val cid = "bafkreicjbfhlviak6ombnyqn3lt66rrdzxtf2ndsbsswxhivo7jtz452yy"
    private val blob = "https://tngl.sh/xrpc/com.atproto.sync.getBlob?did=$OWNER_DID&cid=$cid"
    private val bluesky = "https://cdn.bsky.app/img/avatar/plain/$OWNER_DID/bafkreib@jpeg"

    private val document: (HttpRequestData) -> String? = {
        """{"id":"x","alsoKnownAs":["at://tangled.org"],"service":[{"id":"#atproto_pds","type":"AtprotoPersonalDataServer","serviceEndpoint":"https://tngl.sh/"}]}"""
    }

    private fun profile(avatar: Boolean): (HttpRequestData) -> String? = {
        val picture = if (avatar) ""","avatar":{"${'$'}type":"blob","ref":{"${'$'}link":"$cid"},"mimeType":"image/jpeg","size":40000}""" else ""
        """{"uri":"at://$OWNER_DID/org.tangled.actor.profile/self","value":{"${'$'}type":"org.tangled.actor.profile","description":"Git, on the AT Protocol"$picture}}"""
    }

    private val blueskyProfile: (HttpRequestData) -> String? = { """{"did":"$OWNER_DID","handle":"tangled.org","avatar":"$bluesky"}""" }

    private fun fake(vararg answers: Pair<String, (HttpRequestData) -> String?>) = FakeTangled(
        mapOf("com.atproto.identity.resolveHandle" to resolved, "plc.directory/$OWNER_DID" to document) + answers,
    )

    private suspend fun profilePicture(fake: FakeTangled): String? =
        TangledUserApi(fake.xrpc, TangledIdentity(fake.client, fake.xrpc)).user(null, "tangled.org").value().avatarUrl

    @Test
    fun a_profile_s_picture_is_the_blob_its_tangled_profile_names_on_its_own_server() = runTest {
        val fake = fake("org.tangled.actor.getProfile" to profile(avatar = true), "app.bsky.actor.getProfile" to blueskyProfile)

        assertThat(profilePicture(fake)).isEqualTo(blob)
        // The Tangled picture wins: Bluesky isn't asked.
        assertThat(fake.to("app.bsky.actor.getProfile")).isEmpty()
    }

    @Test
    fun without_a_picture_in_its_tangled_profile_it_is_the_bluesky_one() = runTest {
        val fake = fake("org.tangled.actor.getProfile" to profile(avatar = false), "app.bsky.actor.getProfile" to blueskyProfile)

        assertThat(profilePicture(fake)).isEqualTo(bluesky)
        assertThat(fake.asked).contains("app.bsky.actor.getProfile?actor=$OWNER_DID")
    }

    @Test
    fun with_neither_there_is_none_and_the_handle_s_initial_stands_in() = runTest {
        // Bluesky's AppView answers 400 for an account it doesn't know.
        val fake = fake("org.tangled.actor.getProfile" to profile(avatar = false))

        assertThat(profilePicture(fake)).isNull()
    }

    @Test
    fun a_repository_shows_its_owner_s_picture() = runTest {
        val fake = fake("org.tangled.temp.repo.getRepoByName" to coreRepo, "org.tangled.actor.getProfile" to profile(avatar = true))

        val repo = TangledRepoApi(fake.xrpc, TangledIdentity(fake.client, fake.xrpc)).repo(null, RepoId("tangled.org", "core", ForgeInstance.Tangled)).value()

        assertThat(repo.ownerAvatarUrl).isEqualTo(blob)
    }

    @Test
    fun trending_shows_each_owner_s_picture_from_the_cid_the_index_gives() = runTest {
        val fake = fake(
            "org.tangled.actor.getTrending" to {
                """{"people":[],"repos":[{"did":"$REPO_DID","owner":{"did":"$OWNER_DID","handle":"tangled.org","avatarCid":"$cid"},"slug":"core","createdAt":"2025-03-01T10:00:00Z","starCount":412}]}"""
            },
        )

        val core = TangledTrendingApi(fake.xrpc, TangledIdentity(fake.client, fake.xrpc)).trending(TrendingPeriod.DAILY).value().single()

        assertThat(core.ownerAvatarUrl).isEqualTo(blob)
        assertThat(core.builtBy.single().avatarUrl).isEqualTo(blob)
        // The CID was given: the profile isn't read again for it.
        assertThat(fake.to("org.tangled.actor.getProfile")).isEmpty()
    }

    @Test
    fun the_signed_in_person_has_their_picture() = runTest {
        val fake = fake(
            "com.atproto.server.getSession" to { """{"did":"$OWNER_DID","handle":"tangled.org"}""" },
            "org.tangled.actor.getProfile" to profile(avatar = false),
            "app.bsky.actor.getProfile" to blueskyProfile,
        )
        val identity = TangledIdentity(fake.client, fake.xrpc)
        val token = TangledAccountTestTokens.forSubject(OWNER_DID)

        val user = TangledAccount(fake.xrpc, identity).user(token).value()

        assertThat(user.avatarUrl).isEqualTo(bluesky)
    }

    @Test
    fun a_picture_is_looked_up_once_per_person() = runTest {
        val fake = fake("org.tangled.actor.getProfile" to profile(avatar = false), "app.bsky.actor.getProfile" to blueskyProfile)
        val identity = TangledIdentity(fake.client, fake.xrpc)

        repeat(3) { identity.avatar(OWNER_DID) }

        assertThat(fake.to("app.bsky.actor.getProfile")).hasSize(1)
    }
}

/** A session token, as an AT Protocol server signs one, for [TangledAccount] to read whose it is. */
internal object TangledAccountTestTokens {
    fun forSubject(did: String, exp: Long = 4_102_444_800): String {
        val enc = java.util.Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("""{"alg":"ES256K"}""".toByteArray()) + "." +
            enc.encodeToString("""{"scope":"com.atproto.appPass","sub":"$did","iat":1,"exp":$exp}""".toByteArray()) + ".c2ln"
    }
}
