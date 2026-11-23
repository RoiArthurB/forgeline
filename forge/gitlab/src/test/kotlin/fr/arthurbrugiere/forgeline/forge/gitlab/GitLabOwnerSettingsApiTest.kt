package fr.arthurbrugiere.forgeline.forge.gitlab

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.Owner
import fr.arthurbrugiere.forgeline.core.model.OwnerChoice
import fr.arthurbrugiere.forgeline.core.model.OwnerChoiceValue
import fr.arthurbrugiere.forgeline.core.model.OwnerSettingsChange
import fr.arthurbrugiere.forgeline.core.model.OwnerSwitch
import fr.arthurbrugiere.forgeline.core.model.OwnerText
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** gitlab.com's answers for `gitlab-org` and a reader's preferences, read on 2026-11-23 and cut down. */
class GitLabOwnerSettingsApiTest {
    private val requests = java.util.concurrent.CopyOnWriteArrayList<HttpRequestData>()

    private val groupJson = """{"id":9970,"name":"GitLab.org","path":"gitlab-org","description":"Open source software","visibility":"public",
        "request_access_enabled":false,"project_creation_level":"maintainer","subgroup_creation_level":"owner","require_two_factor_authentication":true,
        "share_with_group_lock":false,"emails_enabled":true,"emails_disabled":false,"mentions_disabled":true,"lfs_enabled":true}"""
    private val preferencesJson = """{"id":608804,"user_id":2371813,"view_diffs_file_by_file":false,"show_whitespace_in_diffs":true,
        "pass_user_identities_to_ci_jwt":false}"""

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun MockRequestHandleScope.route(request: HttpRequestData): HttpResponseData = when (request.url.encodedPath) {
        "/api/v4/groups/gitlab-org", "/api/v4/groups/group%2Fsub" -> json(groupJson)
        "/api/v4/user/preferences" -> json(preferencesJson)
        "/api/v4/user/avatar" -> json("""{"avatar_url":"https://gitlab.example/avatar.png"}""")
        "/api/v4/groups/gitlab-org/members" -> if (request.method == HttpMethod.Get) {
            json("""[{"id":1,"username":"mona","avatar_url":"https://gitlab.example/mona","access_level":50},{"id":2,"username":"hubot","avatar_url":null,"access_level":30}]""")
        } else {
            json("""{"id":3}""", HttpStatusCode.Created)
        }
        "/api/v4/groups/gitlab-org/invitations" -> if (request.method == HttpMethod.Get) {
            json("""[{"id":9,"invite_email":"new@example.org","access_level":20}]""")
        } else {
            json("""{"status":"success"}""", HttpStatusCode.Created)
        }
        "/api/v4/groups/gitlab-org/members/2", "/api/v4/groups/gitlab-org/invitations/new@example.org" -> json("{}")
        "/api/v4/user/status" -> json("""{"emoji":"bear","message":"I use Code btw","availability":"busy","message_html":"I use Code btw","clear_status_at":null}""")
        "/api/v4/user" -> json("""{"id":2371813,"username":"mona"}""")
        "/api/v4/groups/gitlab-org/members/all/2371813" -> json("""{"id":2371813,"access_level":50}""")
        else -> json("""{"message":"404 Not found"}""", HttpStatusCode.NotFound)
    }

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { route(it) }) =
        GitLabOwnerSettingsApi(gitlabHttpClient(MockEngine { requests += it; handler(it) }), ForgeInstance.GitLab)

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    private val HttpRequestData.sent get() = (body as TextContent).text

    @Test
    fun a_group_reads_its_texts_rules_and_choices() = runTest {
        val group = api().settings("tok", Owner.Organization("gitlab-org")).value()

        assertThat(group.texts).containsExactly(OwnerText.NAME, "GitLab.org", OwnerText.BIO, "Open source software").inOrder()
        assertThat(group.switches).containsExactly(
            OwnerSwitch.REQUEST_ACCESS, false, OwnerSwitch.REQUIRE_TWO_FACTOR, true, OwnerSwitch.SHARE_WITH_GROUP_LOCK, false,
            OwnerSwitch.EMAILS, true, OwnerSwitch.MENTIONS, false, OwnerSwitch.LFS, true,
        ).inOrder()
        assertThat(group.choices[OwnerChoice.VISIBILITY]).isEqualTo(OwnerChoiceValue("public", listOf("public", "internal", "private")))
        assertThat(group.choices[OwnerChoice.PROJECT_CREATION]?.value).isEqualTo("maintainer")
        assertThat(group.choices[OwnerChoice.SUBGROUP_CREATION]?.value).isEqualTo("owner")
    }

    @Test
    fun an_older_gitlab_s_emails_disabled_reads_the_other_way_round() = runTest {
        val group = api { json(groupJson.replace(""""emails_enabled":true,""", "").replace(""""emails_disabled":false""", """"emails_disabled":true""")) }
            .settings("tok", Owner.Organization("gitlab-org")).value()

        assertThat(group.switches[OwnerSwitch.EMAILS]).isFalse()
    }

    @Test
    fun a_subgroup_is_asked_for_by_its_whole_path() = runTest {
        api().settings("tok", Owner.Organization("group/sub"))

        assertThat(requests.single().url.encodedPath).isEqualTo("/api/v4/groups/group%2Fsub")
    }

    @Test
    fun a_group_s_change_puts_its_one_field() = runTest {
        api().change("tok", Owner.Organization("gitlab-org"), OwnerSettingsChange.Text(OwnerText.BIO, "Tools"))
        api().change("tok", Owner.Organization("gitlab-org"), OwnerSettingsChange.Switch(OwnerSwitch.MENTIONS, true))
        api().change("tok", Owner.Organization("gitlab-org"), OwnerSettingsChange.Choice(OwnerChoice.PROJECT_CREATION, "developer"))

        val puts = requests.filter { it.method == HttpMethod.Put }
        assertThat(puts.map { it.url.encodedPath }.distinct()).containsExactly("/api/v4/groups/gitlab-org")
        assertThat(puts.map { it.sent }).containsExactly(
            """{"description":"Tools"}""", """{"mentions_disabled":false}""", """{"project_creation_level":"developer"}""",
        ).inOrder()
    }

    @Test
    fun a_profile_has_only_its_diff_preferences_and_sends_both_on_each_change() = runTest {
        val profile = api().settings("tok", Owner.Me).value()
        assertThat(profile.texts).isEmpty()
        assertThat(profile.switches).containsExactly(OwnerSwitch.DIFFS_FILE_BY_FILE, false, OwnerSwitch.SHOW_WHITESPACE, true).inOrder()

        api().change("tok", Owner.Me, OwnerSettingsChange.Switch(OwnerSwitch.DIFFS_FILE_BY_FILE, true))

        val put = requests.single { it.method == HttpMethod.Put }
        assertThat(put.url.encodedPath).isEqualTo("/api/v4/user/preferences")
        assertThat(put.sent).isEqualTo("""{"view_diffs_file_by_file":true,"show_whitespace_in_diffs":true}""")
    }

    @Test
    fun what_only_an_administrator_changes_on_a_profile_is_not_offered() = runTest {
        assertThat(api().change("tok", Owner.Me, OwnerSettingsChange.Text(OwnerText.BIO, "Hi"))).isEqualTo(ForgeResult.Failure(ForgeError.Unsupported))
        assertThat(requests).isEmpty()
    }

    @Test
    fun a_group_is_administered_by_its_owners_and_no_one_else() = runTest {
        assertThat(api().administers("tok", "gitlab-org")).isEqualTo(ForgeResult.Success(true))
        val maintainer = api { if (it.url.encodedPath.contains("/members/all/")) json("""{"id":2371813,"access_level":40}""") else route(it) }
        assertThat(maintainer.administers("tok", "gitlab-org")).isEqualTo(ForgeResult.Success(false))
        // Not a member at all.
        assertThat(api().administers("tok", "elsewhere")).isEqualTo(ForgeResult.Success(false))
    }

    @Test
    fun a_profile_reads_its_status() = runTest {
        val status = api().settings("tok", Owner.Me).value().status

        assertThat(status).isEqualTo(fr.arthurbrugiere.forgeline.core.model.OwnerStatus("bear", "I use Code btw", busy = true))
    }

    @Test
    fun a_status_is_put_with_gitlab_s_emoji_name_and_its_duration() = runTest {
        val status = fr.arthurbrugiere.forgeline.core.model.OwnerStatus(":coffee:", "Writing", busy = false)
        api().change("tok", Owner.Me, OwnerSettingsChange.Status(status, fr.arthurbrugiere.forgeline.core.model.StatusDuration.DAY_1))
        api().change("tok", Owner.Me, OwnerSettingsChange.Status(fr.arthurbrugiere.forgeline.core.model.OwnerStatus()))

        val (set, cleared) = requests.filter { it.method == HttpMethod.Put }
        assertThat(set.url.encodedPath).isEqualTo("/api/v4/user/status")
        assertThat(set.sent).isEqualTo("""{"emoji":"coffee","message":"Writing","availability":"not_set","clear_status_after":"1_day"}""")
        // What a PUT doesn't name, it clears.
        assertThat(cleared.sent).isEqualTo("""{"availability":"not_set"}""")
    }

    @Test
    fun a_group_s_members_say_their_access_level_and_emails_are_asked_to_join() = runTest {
        val members = api().members("tok", "gitlab-org").value()

        assertThat(members.members.map { Triple(it.id, it.login, it.role) }).containsExactly(Triple("1", "mona", "50"), Triple("2", "hubot", "30")).inOrder()
        assertThat(members.invitations.single()).isEqualTo(fr.arthurbrugiere.forgeline.core.model.OrgInvitation("new@example.org", "new@example.org", "20"))
        assertThat(members.roles).containsExactly("10", "15", "20", "30", "40", "50").inOrder()
    }

    @Test
    fun a_member_is_changed_and_removed_by_id_and_someone_added_by_username_or_invited_by_email() = runTest {
        api().setRole("tok", "gitlab-org", "2", "40")
        api().removeMember("tok", "gitlab-org", "2")
        api().invite("tok", "gitlab-org", "newcomer", "30")
        api().invite("tok", "gitlab-org", "friend@example.org", "10")
        api().cancelInvitation("tok", "gitlab-org", "new@example.org")

        val writes = requests.filter { it.method != HttpMethod.Get }
        assertThat(writes.map { "${it.method.value} ${it.url.encodedPath}" }).containsExactly(
            "PUT /api/v4/groups/gitlab-org/members/2", "DELETE /api/v4/groups/gitlab-org/members/2", "POST /api/v4/groups/gitlab-org/members",
            "POST /api/v4/groups/gitlab-org/invitations", "DELETE /api/v4/groups/gitlab-org/invitations/new@example.org",
        ).inOrder()
        assertThat(writes[0].sent).isEqualTo("""{"access_level":40}""")
        assertThat(writes[2].sent).isEqualTo("""{"username":"newcomer","access_level":30}""")
        assertThat(writes[3].sent).isEqualTo("""{"email":"friend@example.org","access_level":10}""")
    }

    @Test
    fun a_picture_is_a_file_part_put_to_one_s_avatar_or_the_group() = runTest {
        api().setAvatar("tok", Owner.Me, byteArrayOf(1, 2, 3), "image/jpeg")
        api().setAvatar("tok", Owner.Organization("gitlab-org"), byteArrayOf(4, 5), "image/png")

        val (mine, group) = requests.filter { it.method == HttpMethod.Put }
        assertThat(mine.url.encodedPath).isEqualTo("/api/v4/user/avatar")
        assertThat(group.url.encodedPath).isEqualTo("/api/v4/groups/gitlab-org")
        val part = (mine.body as io.ktor.http.content.OutgoingContent).contentType
        assertThat(part?.contentType).isEqualTo("multipart")
        assertThat(api().avatarRemovable(Owner.Me)).isFalse()
    }
}
