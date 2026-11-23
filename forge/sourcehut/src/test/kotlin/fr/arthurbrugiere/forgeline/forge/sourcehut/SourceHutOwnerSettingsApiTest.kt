package fr.arthurbrugiere.forgeline.forge.sourcehut

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.Owner
import fr.arthurbrugiere.forgeline.core.model.OwnerSettingsChange
import fr.arthurbrugiere.forgeline.core.model.OwnerText
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** Answers shaped as meta.sr.ht's schema (`api/graph/schema.graphqls`, read 2026-11-23) describes them. */
class SourceHutOwnerSettingsApiTest {
    private fun <T> ForgeResult<T>.value(): T = if (this is ForgeResult.Success) value else error("$this")

    private val me = """{"username":"mona","email":"mona@example.org","url":"https://mona.example","location":"Paris","bio":"Hacker","pronouns":null}"""

    private fun fake() = FakeSourceHut(
        listOf(
            "updateUser" to { request ->
                val input = request.variables["input"]!!.jsonObject
                val bio = input["bio"]?.let { if (it is JsonNull) "null" else "\"${it.jsonPrimitive.content}\"" } ?: "\"Hacker\""
                """{"data":{"updateUser":{"username":"mona","email":"mona@example.org","url":"https://mona.example","location":"Paris","bio":$bio,"pronouns":null}}}"""
            },
            "me" to { _ -> """{"data":{"me":$me}}""" },
        ),
    )

    @Test
    fun a_profile_is_read_from_meta_sr_ht() = runTest {
        val fake = fake()
        val profile = SourceHutOwnerSettingsApi(fake.client).settings(TOKEN, Owner.Me).value()

        assertThat(fake.requests.single().host).isEqualTo("meta.sr.ht")
        assertThat(profile.texts).containsExactly(
            OwnerText.BIO, "Hacker", OwnerText.PRONOUNS, "", OwnerText.LOCATION, "Paris", OwnerText.WEBSITE, "https://mona.example", OwnerText.EMAIL, "mona@example.org",
        ).inOrder()
    }

    @Test
    fun a_change_sends_that_one_field_and_an_emptied_one_is_cleared() = runTest {
        val fake = fake()
        val api = SourceHutOwnerSettingsApi(fake.client)

        assertThat(api.change(TOKEN, Owner.Me, OwnerSettingsChange.Text(OwnerText.BIO, "Writes C")).value().texts[OwnerText.BIO]).isEqualTo("Writes C")
        assertThat(api.change(TOKEN, Owner.Me, OwnerSettingsChange.Text(OwnerText.BIO, "  ")).value().texts[OwnerText.BIO]).isEmpty()

        val (written, cleared) = fake.requests.map { it.variables["input"]!!.jsonObject }
        assertThat(written.keys).containsExactly("bio")
        assertThat(cleared["bio"]).isEqualTo(JsonNull)
    }

    @Test
    fun an_email_is_never_cleared_and_sourcehut_has_no_organisations() = runTest {
        val api = SourceHutOwnerSettingsApi(fake().client)

        assertThat(api.change(TOKEN, Owner.Me, OwnerSettingsChange.Text(OwnerText.EMAIL, ""))).isEqualTo(ForgeResult.Failure(ForgeError.Unsupported))
        assertThat(api.settings(TOKEN, Owner.Organization("~mona"))).isEqualTo(ForgeResult.Failure(ForgeError.Unsupported))
    }
}
