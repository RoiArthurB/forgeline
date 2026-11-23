package fr.arthurbrugiere.forgeline.forge.tangled

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.Owner
import fr.arthurbrugiere.forgeline.core.model.OwnerChoice
import fr.arthurbrugiere.forgeline.core.model.OwnerSettingsChange
import fr.arthurbrugiere.forgeline.core.model.OwnerText
import io.ktor.client.request.HttpRequestData
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.util.Base64

/** The profile record as the lexicon (`org.tangled.actor.profile`, read 2026-11-23) describes it, on the person's own server. */
class TangledOwnerSettingsApiTest {
    private fun <T> ForgeResult<T>.value(): T = (this as? ForgeResult.Success)?.value ?: error("$this")

    private val now = Instant.parse("2026-11-23T12:00:00Z")
    private val me = "did:plc:meme7t2adsemcrrd4snkeqli"
    private val token = Base64.getUrlEncoder().withoutPadding().let { enc ->
        enc.encodeToString("""{"alg":"ES256K"}""".toByteArray()) + "." +
            enc.encodeToString("""{"scope":"com.atproto.appPass","sub":"$me","iat":${now.epochSecond},"exp":${now.epochSecond + 7_200}}""".toByteArray()) + ".c2ln"
    }

    private val record = """{"uri":"at://$me/org.tangled.actor.profile/self","cid":"bafy","value":{"${'$'}type":"org.tangled.actor.profile",
        "description":"Builds tools","location":"Paris","links":["https://mona.example"],"stats":["star-count"],
        "pinnedRepositories":["did:plc:repo1"],"avatar":{"${'$'}type":"blob","ref":{"${'$'}link":"bafkrei"},"mimeType":"image/png","size":1200}}}"""

    private fun fake(profile: String? = record) = FakeTangled(
        mapOf(
            "plc.directory/$me" to {
                """{"id":"x","alsoKnownAs":["at://me.example"],"service":[{"id":"#atproto_pds","type":"AtprotoPersonalDataServer","serviceEndpoint":"https://pds.example/"}]}"""
            },
            "com.atproto.repo.getRecord" to { _: HttpRequestData -> profile },
            "com.atproto.repo.putRecord" to { _: HttpRequestData -> """{"uri":"at://$me/org.tangled.actor.profile/self","cid":"bafz"}""" },
            "com.atproto.repo.uploadBlob" to { _: HttpRequestData -> """{"blob":{"${'$'}type":"blob","ref":{"${'$'}link":"bafknew"},"mimeType":"image/jpeg","size":3}}""" },
        ),
    )

    private fun api(fake: FakeTangled) = TangledOwnerSettingsApi(TangledAccount(fake.xrpc, TangledIdentity(fake.client, fake.xrpc)))

    @Test
    fun a_profile_reads_its_record_on_the_person_s_own_server() = runTest {
        val fake = fake()
        val profile = api(fake).settings(token, Owner.Me).value()

        assertThat(fake.to("com.atproto.repo.getRecord").single().url.host).isEqualTo("pds.example")
        assertThat(profile.texts).containsExactly(OwnerText.BIO, "Builds tools", OwnerText.PRONOUNS, "", OwnerText.LOCATION, "Paris").inOrder()
        assertThat(profile.links).containsExactly("https://mona.example")
        assertThat(profile.maxLinks).isEqualTo(5)
        assertThat(profile.choices[OwnerChoice.FIRST_STAT]?.value).isEqualTo("star-count")
        assertThat(profile.choices[OwnerChoice.SECOND_STAT]?.value).isEmpty()
    }

    @Test
    fun someone_who_never_wrote_a_profile_reads_an_empty_one() = runTest {
        val profile = api(fake(profile = null)).settings(token, Owner.Me).value()

        assertThat(profile.texts.values.toSet()).containsExactly("")
        assertThat(profile.links).isEmpty()
    }

    @Test
    fun a_change_writes_the_record_back_whole_keeping_its_picture_and_pins() = runTest {
        val fake = fake()
        val changed = api(fake).change(token, Owner.Me, OwnerSettingsChange.Text(OwnerText.PRONOUNS, "she/her")).value()

        val sent = fake.to("com.atproto.repo.putRecord").single()
        assertThat(sent.url.host).isEqualTo("pds.example")
        assertThat(sent.text).contains(""""collection":"org.tangled.actor.profile"""")
        assertThat(sent.text).contains(""""rkey":"self"""")
        assertThat(sent.text).contains(""""pronouns":"she/her"""")
        assertThat(sent.text).contains(""""pinnedRepositories":["did:plc:repo1"]""")
        assertThat(sent.text).contains(""""avatar":{"${'$'}type":"blob"""")
        assertThat(changed.texts[OwnerText.PRONOUNS]).isEqualTo("she/her")
    }

    @Test
    fun a_text_emptied_leaves_the_record_and_links_keep_five_at_most() = runTest {
        val fake = fake()
        api(fake).change(token, Owner.Me, OwnerSettingsChange.Text(OwnerText.LOCATION, " "))
        api(fake).change(token, Owner.Me, OwnerSettingsChange.Links((1..7).map { "https://site$it.example" }))

        val (cleared, linked) = fake.to("com.atproto.repo.putRecord").map { it.text }
        assertThat(cleared).doesNotContain(""""location"""")
        assertThat(Regex("https://site\\d\\.example").findAll(linked).count()).isEqualTo(5)
    }

    @Test
    fun the_two_figures_shown_are_set_one_at_a_time_never_the_same_twice() = runTest {
        val fake = fake()
        api(fake).change(token, Owner.Me, OwnerSettingsChange.Choice(OwnerChoice.SECOND_STAT, "repository-count"))
        api(fake).change(token, Owner.Me, OwnerSettingsChange.Choice(OwnerChoice.SECOND_STAT, "star-count"))

        val (both, once) = fake.to("com.atproto.repo.putRecord").map { it.text }
        assertThat(both).contains(""""stats":["star-count","repository-count"]""")
        assertThat(once).contains(""""stats":["star-count"]""")
    }

    @Test
    fun tangled_has_no_organisations() = runTest {
        assertThat(api(fake()).settings(token, Owner.Organization("tangled.org"))).isEqualTo(ForgeResult.Failure(ForgeError.Unsupported))
    }

    @Test
    fun a_picture_is_kept_as_a_blob_then_named_by_the_profile_record() = runTest {
        val fake = fake()
        api(fake).setAvatar(token, Owner.Me, byteArrayOf(1, 2, 3), "image/jpeg")

        val upload = fake.to("com.atproto.repo.uploadBlob").single()
        assertThat(upload.url.host).isEqualTo("pds.example")
        assertThat(upload.body.contentType.toString()).isEqualTo("image/jpeg")
        val written = fake.to("com.atproto.repo.putRecord").single().text
        assertThat(written).contains(""""avatar":{"${'$'}type":"blob","ref":{"${'$'}link":"bafknew"}""")
        // The rest of the record stays.
        assertThat(written).contains(""""description":"Builds tools"""")
    }

    @Test
    fun a_picture_taken_away_leaves_the_record_without_one() = runTest {
        val fake = fake()
        api(fake).removeAvatar(token, Owner.Me)

        assertThat(fake.to("com.atproto.repo.putRecord").single().text).doesNotContain(""""avatar"""")
    }
}
