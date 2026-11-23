package fr.arthurbrugiere.forgeline.forge.forgejo

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.Owner
import fr.arthurbrugiere.forgeline.core.model.OwnerChoice
import fr.arthurbrugiere.forgeline.core.model.OwnerChoiceValue
import fr.arthurbrugiere.forgeline.core.model.OwnerSettingsChange
import fr.arthurbrugiere.forgeline.core.model.OwnerSwitch
import fr.arthurbrugiere.forgeline.core.model.OwnerText
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Codeberg's answers to `GET /user/settings` (read on 2026-11-23) and `GET /orgs/{org}`, cut down; Gitea's has no pronouns. */
class ForgejoOwnerSettingsApiTest {
    private val codeberg = Codeberg()

    private val settingsJson = """{"full_name":"Mona","website":"https://mona.example","description":"Builds tools","location":"Paris","pronouns":"she/her",
        "language":"en-US","theme":"forgejo-dark","diff_view_style":"unified","enable_repo_unit_hints":true,"hide_email":true,"hide_pronouns":false,"hide_activity":true}"""
    private val giteaSettingsJson = """{"full_name":"Mona","website":"","description":"","location":"","language":"en-US","theme":"gitea-auto",
        "diff_view_style":"split","hide_email":false,"hide_activity":false}"""
    private val orgJson = """{"id":1635660,"name":"octo","full_name":"Octo","email":"","avatar_url":"https://codeberg.org/avatars/3484","description":"We make tools",
        "website":"https://octo.example","location":"Lyon","visibility":"public","repo_admin_change_team_access":false,"username":"octo"}"""

    private fun MockRequestHandleScope.route(request: HttpRequestData): HttpResponseData = with(codeberg) {
        when (request.url.encodedPath) {
            "/api/v1/user/settings" -> json(settingsJson)
            "/api/v1/orgs/octo" -> json(orgJson)
            "/api/v1/user" -> json("""{"login":"mona"}""")
            "/api/v1/user/list_blocked", "/api/v1/orgs/octo/list_blocked" -> json("""[{"block_id":42,"created_at":"2026-11-01T00:00:00Z"}]""")
            "/api/v1/users/search" -> json("""{"ok":true,"data":[{"id":42,"login":"spammer","avatar_url":"https://codeberg.org/avatars/42"}]}""")
            "/api/v1/orgs/octo/members" -> json("""[{"id":1,"login":"mona","avatar_url":"https://codeberg.org/avatars/1"},{"id":2,"login":"hubot","avatar_url":""}]""")
            "/api/v1/orgs/octo/teams" -> json("""[{"id":12,"name":"Writers","permission":"write"},{"id":11,"name":"Owners","permission":"owner"}]""")
            "/api/v1/teams/11/members" -> json("""[{"login":"mona"}]""")
            "/api/v1/teams/12/members" -> json("""[{"login":"mona"},{"login":"hubot"}]""")
            "/api/v1/orgs/octo/members/hubot", "/api/v1/teams/12/members/newcomer" -> respond("", io.ktor.http.HttpStatusCode.NoContent)
            "/api/v1/user/blocks" -> json("""[{"id":42,"login":"spammer","avatar_url":"https://gitea.com/avatars/42"}]""")
            "/api/v1/users/mona/orgs/octo/permissions" -> json("""{"is_owner":true,"is_admin":true,"can_write":true,"can_read":true,"can_create_repository":true}""")
            else -> json("""{"message":"Not Found"}""", io.ktor.http.HttpStatusCode.NotFound)
        }
    }

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { route(it) }) =
        ForgejoOwnerSettingsApi(codeberg.client { handler(it) }, ForgeInstance.Codeberg)

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    private val HttpRequestData.sent get() = (body as TextContent).text

    @Test
    fun a_profile_reads_its_texts_what_it_hides_and_how_diffs_show() = runTest {
        val profile = api().settings("tok", Owner.Me).value()

        assertThat(profile.texts).containsExactly(
            OwnerText.NAME, "Mona", OwnerText.BIO, "Builds tools", OwnerText.PRONOUNS, "she/her", OwnerText.LOCATION, "Paris", OwnerText.WEBSITE, "https://mona.example",
        ).inOrder()
        assertThat(profile.switches).containsExactly(OwnerSwitch.HIDE_EMAIL, true, OwnerSwitch.HIDE_PRONOUNS, false, OwnerSwitch.HIDE_ACTIVITY, true).inOrder()
        assertThat(profile.choices).containsExactly(OwnerChoice.DIFF_STYLE, OwnerChoiceValue("unified", listOf("unified", "split")))
        // Forgejo keeps no list of links.
        assertThat(profile.links).isNull()
    }

    @Test
    fun a_gitea_profile_has_no_pronouns_to_offer() = runTest {
        val profile = api { with(codeberg) { json(giteaSettingsJson) } }.settings("tok", Owner.Me).value()

        assertThat(profile.texts).doesNotContainKey(OwnerText.PRONOUNS)
        assertThat(profile.switches).doesNotContainKey(OwnerSwitch.HIDE_PRONOUNS)
    }

    @Test
    fun an_older_gitea_s_settings_in_a_list_of_one_read_the_same() = runTest {
        val profile = api { with(codeberg) { json("[$giteaSettingsJson]") } }.settings("tok", Owner.Me).value()

        assertThat(profile.texts[OwnerText.NAME]).isEqualTo("Mona")
    }

    @Test
    fun a_profile_change_is_one_patch_of_the_settings() = runTest {
        val changed = api().change("tok", Owner.Me, OwnerSettingsChange.Text(OwnerText.PRONOUNS, "they/them")).value()
        api().change("tok", Owner.Me, OwnerSettingsChange.Switch(OwnerSwitch.HIDE_ACTIVITY, false))
        api().change("tok", Owner.Me, OwnerSettingsChange.Choice(OwnerChoice.DIFF_STYLE, "split"))

        val patches = codeberg.requests.filter { it.method == HttpMethod.Patch }
        assertThat(patches.map { it.url.encodedPath }.distinct()).containsExactly("/api/v1/user/settings")
        assertThat(patches.map { it.sent }).containsExactly("""{"pronouns":"they/them"}""", """{"hide_activity":false}""", """{"diff_view_style":"split"}""").inOrder()
        assertThat(changed.texts[OwnerText.NAME]).isEqualTo("Mona")
    }

    @Test
    fun an_organisation_reads_its_texts_who_sees_it_and_its_team_rule() = runTest {
        val org = api().settings("tok", Owner.Organization("octo")).value()

        assertThat(org.texts.keys).containsExactly(OwnerText.NAME, OwnerText.BIO, OwnerText.LOCATION, OwnerText.WEBSITE, OwnerText.EMAIL).inOrder()
        assertThat(org.texts[OwnerText.NAME]).isEqualTo("Octo")
        assertThat(org.choices[OwnerChoice.VISIBILITY]).isEqualTo(OwnerChoiceValue("public", listOf("public", "limited", "private")))
        assertThat(org.switches).containsExactly(OwnerSwitch.REPO_ADMINS_CHANGE_TEAM_ACCESS, false)
    }

    @Test
    fun an_organisation_s_change_is_one_patch_of_it() = runTest {
        api().change("tok", Owner.Organization("octo"), OwnerSettingsChange.Choice(OwnerChoice.VISIBILITY, "limited"))
        api().change("tok", Owner.Organization("octo"), OwnerSettingsChange.Text(OwnerText.BIO, "Tools"))

        val patches = codeberg.requests.filter { it.method == HttpMethod.Patch }
        assertThat(patches.map { it.url.encodedPath }.distinct()).containsExactly("/api/v1/orgs/octo")
        assertThat(patches.map { it.sent }).containsExactly("""{"visibility":"limited"}""", """{"description":"Tools"}""").inOrder()
    }

    @Test
    fun an_organisation_is_administered_by_its_owners() = runTest {
        assertThat(api().administers("tok", "octo")).isEqualTo(ForgeResult.Success(true))
        val member = api {
            with(codeberg) {
                if (it.url.encodedPath.endsWith("/permissions")) json("""{"is_owner":false,"is_admin":false,"can_write":true,"can_read":true}""") else route(it)
            }
        }
        assertThat(member.administers("tok", "octo")).isEqualTo(ForgeResult.Success(false))
    }

    @Test
    fun forgejo_lists_blocked_ids_each_then_looked_up() = runTest {
        val blocked = api().blocked("tok", Owner.Me).value()

        assertThat(blocked.map { it.login }).containsExactly("spammer")
        assertThat(codeberg.requests.single { it.url.encodedPath == "/api/v1/users/search" }.url.parameters["uid"]).isEqualTo("42")
        assertThat(api().blocked("tok", Owner.Organization("octo")).value().map { it.login }).containsExactly("spammer")
    }

    @Test
    fun forgejo_blocks_and_unblocks_at_two_addresses_each_put() = runTest {
        api().setBlocked("tok", Owner.Me, "spammer", blocked = true)
        api().setBlocked("tok", Owner.Organization("octo"), "spammer", blocked = false)

        val writes = codeberg.requests.filter { it.method != HttpMethod.Get }
        assertThat(writes.map { "${it.method.value} ${it.url.encodedPath}" }).containsExactly("PUT /api/v1/user/block/spammer", "PUT /api/v1/orgs/octo/unblock/spammer").inOrder()
    }

    @Test
    fun gitea_lists_blocks_whole_and_unblocks_with_a_delete() = runTest {
        val gitea = ForgejoOwnerSettingsApi(codeberg.client { route(it) }, ForgeInstance.Gitea)

        assertThat(gitea.blocked("tok", Owner.Me).value().map { it.login }).containsExactly("spammer")
        gitea.setBlocked("tok", Owner.Me, "spammer", blocked = false)

        assertThat(codeberg.requests.last().let { "${it.method.value} ${it.url.encodedPath}" }).isEqualTo("DELETE /api/v1/user/blocks/spammer")
        assertThat(codeberg.requests.none { it.url.encodedPath == "/api/v1/users/search" }).isTrue()
    }

    @Test
    fun an_organisation_s_members_are_named_by_their_most_trusted_team() = runTest {
        val members = api().members("tok", "octo").value()

        assertThat(members.members.map { it.login to it.role }).containsExactly("mona" to "11", "hubot" to "12").inOrder()
        assertThat(members.roles).containsExactly("11", "12").inOrder()
        assertThat(members.roleNames).containsExactly("11", "Owners", "12", "Writers")
        assertThat(members.changesRoles).isFalse()
        assertThat(members.invitesByEmail).isFalse()
    }

    @Test
    fun someone_is_added_to_a_team_and_a_member_removed_from_the_organisation() = runTest {
        api().invite("tok", "octo", "newcomer", "12")
        api().removeMember("tok", "octo", "hubot")

        val writes = codeberg.requests.filter { it.method != HttpMethod.Get }
        assertThat(writes.map { "${it.method.value} ${it.url.encodedPath}" }).containsExactly("PUT /api/v1/teams/12/members/newcomer", "DELETE /api/v1/orgs/octo/members/hubot").inOrder()
    }
}
