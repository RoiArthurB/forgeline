package fr.arthurbrugiere.forgeline.forge.github

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
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

/** Answers cut down from api.github.com's (REST, version 2022-11-28), for a profile and an organisation its reader owns. */
class GitHubOwnerSettingsApiTest {
    private val requests = java.util.concurrent.CopyOnWriteArrayList<HttpRequestData>()

    private val userJson = """{"login":"mona","name":"Mona Lisa","company":"@octo","blog":"https://mona.example","location":"Paris",
        "email":null,"hireable":true,"bio":"Builds tools","twitter_username":null,"public_repos":8}"""
    private val socialJson = """[{"provider":"mastodon","url":"https://mastodon.social/@mona"}]"""
    private val orgJson = """{"login":"octo","name":"Octo","company":null,"blog":"https://octo.example","location":"Lyon","email":"hi@octo.example",
        "twitter_username":"octo","description":"We make tools","billing_email":"bills@octo.example","has_organization_projects":true,
        "has_repository_projects":false,"default_repository_permission":"read","members_can_create_public_repositories":true,
        "members_can_create_private_repositories":false,"members_can_create_pages":true,"members_can_fork_private_repositories":false,
        "web_commit_signoff_required":false}"""

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK, scopes: String? = "admin:org, repo, user") =
        respond(body, status, headersOf(*listOfNotNull(HttpHeaders.ContentType to listOf("application/json"), scopes?.let { "X-OAuth-Scopes" to listOf(it) }).toTypedArray()))

    private fun MockRequestHandleScope.answer(request: HttpRequestData): HttpResponseData = when (request.url.encodedPath) {
        "/user" -> json(userJson)
        "/user/social_accounts" -> if (request.method == HttpMethod.Get) json(socialJson) else respond("", HttpStatusCode.NoContent)
        "/orgs/octo" -> json(orgJson)
        "/user/interaction-limits", "/orgs/octo/interaction-limits" -> when (request.method) {
            HttpMethod.Get -> json("""{"limit":"collaborators_only","origin":"user","expires_at":"2026-11-30T10:00:00Z"}""")
            else -> respond("", HttpStatusCode.NoContent)
        }
        "/orgs/octo/members" -> json(
            if (request.url.parameters["role"] == "admin") """[{"login":"mona","avatar_url":"https://avatars.example/mona"}]""" else """[{"login":"hubot","avatar_url":null}]""",
        )
        "/orgs/octo/invitations" -> if (request.method == HttpMethod.Get) {
            json("""[{"id":7,"login":null,"email":"new@octo.example","role":"direct_member"}]""")
        } else {
            json("""{"id":8}""", HttpStatusCode.Created)
        }
        "/users/newcomer" -> json("""{"login":"newcomer","id":4242}""")
        "/orgs/octo/memberships/hubot" -> json("""{"role":"admin","state":"active"}""")
        "/orgs/octo/invitations/7" -> respond("", HttpStatusCode.NoContent)
        "/user/blocks", "/orgs/octo/blocks" -> json("""[{"login":"spammer","avatar_url":"https://avatars.example/spammer","type":"User"}]""")
        "/user/blocks/spammer", "/orgs/octo/blocks/spammer" -> respond("", HttpStatusCode.NoContent)
        "/user/memberships/orgs/octo" -> json("""{"role":"admin","state":"active"}""")
        "/graphql" -> if ("changeUserStatus" in request.sent) {
            json("""{"data":{"changeUserStatus":{"status":{"message":"Writing"}}}}""")
        } else {
            json("""{"data":{"viewer":{"status":{"emoji":":coffee:","message":"Writing","indicatesLimitedAvailability":true,"expiresAt":"2026-11-24T10:00:00Z"}}}}""")
        }
        else -> json("""{"message":"Not Found"}""", HttpStatusCode.NotFound)
    }

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { answer(it) }) =
        GitHubOwnerSettingsApi(gitHubHttpClient(MockEngine { requests += it; handler(it) }), clock = java.time.Clock.fixed(java.time.Instant.parse("2026-11-23T10:00:00Z"), java.time.ZoneOffset.UTC))

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    private val HttpRequestData.call get() = "${method.value} ${url.encodedPath}"

    private val HttpRequestData.sent get() = (body as TextContent).text

    @Test
    fun a_profile_reads_its_texts_whether_its_person_is_for_hire_and_their_social_accounts() = runTest {
        val profile = api().settings("tok", Owner.Me).value()

        assertThat(profile.texts.keys).containsExactly(
            OwnerText.NAME, OwnerText.BIO, OwnerText.COMPANY, OwnerText.LOCATION, OwnerText.WEBSITE, OwnerText.TWITTER, OwnerText.EMAIL,
        ).inOrder()
        assertThat(profile.texts[OwnerText.NAME]).isEqualTo("Mona Lisa")
        assertThat(profile.texts[OwnerText.WEBSITE]).isEqualTo("https://mona.example")
        // Null on GitHub's side is nothing written.
        assertThat(profile.texts[OwnerText.TWITTER]).isEmpty()
        assertThat(profile.switches).containsExactly(OwnerSwitch.HIREABLE, true)
        assertThat(profile.links).containsExactly("https://mastodon.social/@mona")
        assertThat(profile.maxLinks).isEqualTo(4)
    }

    @Test
    fun a_profile_text_is_patched_under_github_s_name_for_it() = runTest {
        api().change("tok", Owner.Me, OwnerSettingsChange.Text(OwnerText.WEBSITE, "https://new.example"))
        api().change("tok", Owner.Me, OwnerSettingsChange.Switch(OwnerSwitch.HIREABLE, false))

        val patches = requests.filter { it.method == HttpMethod.Patch }
        assertThat(patches.map { it.call }).containsExactly("PATCH /user", "PATCH /user")
        assertThat(patches[0].sent).isEqualTo("""{"blog":"https://new.example"}""")
        assertThat(patches[1].sent).isEqualTo("""{"hireable":false}""")
    }

    @Test
    fun links_add_what_is_new_and_remove_what_was_let_go() = runTest {
        api().change("tok", Owner.Me, OwnerSettingsChange.Links(listOf("https://bsky.app/profile/mona.example")))

        // The profile is read again after, its status from the GraphQL API among it.
        val writes = requests.filter { it.method != HttpMethod.Get && it.url.encodedPath != "/graphql" }
        assertThat(writes.map { it.call }).containsExactly("DELETE /user/social_accounts", "POST /user/social_accounts").inOrder()
        assertThat(writes[0].sent).isEqualTo("""{"account_urls":["https://mastodon.social/@mona"]}""")
        assertThat(writes[1].sent).isEqualTo("""{"account_urls":["https://bsky.app/profile/mona.example"]}""")
    }

    @Test
    fun a_sign_in_without_the_user_scope_is_told_to_sign_in_again() = runTest {
        val refused = api { if (it.method == HttpMethod.Patch) json("""{"message":"Not Found"}""", HttpStatusCode.NotFound, scopes = "read:user, repo") else answer(it) }
            .change("tok", Owner.Me, OwnerSettingsChange.Text(OwnerText.NAME, "Mona"))

        assertThat(refused).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
    }

    @Test
    fun a_refusal_with_the_scope_is_the_forge_s_own() = runTest {
        val refused = api { if (it.method == HttpMethod.Patch) json("""{"message":"Validation Failed"}""", HttpStatusCode.UnprocessableEntity) else answer(it) }
            .change("tok", Owner.Me, OwnerSettingsChange.Text(OwnerText.EMAIL, "unverified@mona.example"))

        assertThat((refused as ForgeResult.Failure).error).isInstanceOf(ForgeError.Http::class.java)
    }

    @Test
    fun an_organisation_reads_its_texts_rules_and_default_permission() = runTest {
        val org = api().settings("tok", Owner.Organization("octo")).value()

        assertThat(org.texts[OwnerText.BIO]).isEqualTo("We make tools")
        assertThat(org.texts[OwnerText.BILLING_EMAIL]).isEqualTo("bills@octo.example")
        assertThat(org.switches[OwnerSwitch.MEMBERS_CREATE_PUBLIC_REPOS]).isTrue()
        assertThat(org.switches[OwnerSwitch.MEMBERS_CREATE_PRIVATE_REPOS]).isFalse()
        assertThat(org.switches[OwnerSwitch.REPOSITORY_PROJECTS]).isFalse()
        assertThat(org.choices[OwnerChoice.DEFAULT_REPO_PERMISSION]).isEqualTo(OwnerChoiceValue("read", listOf("none", "read", "write", "admin")))
        assertThat(org.links).isNull()
    }

    @Test
    fun an_organisation_read_by_someone_not_its_owner_has_no_billing_email() = runTest {
        val org = api { if (it.url.encodedPath == "/orgs/octo") json(orgJson.replace(""""billing_email":"bills@octo.example",""", "")) else answer(it) }
            .settings("tok", Owner.Organization("octo")).value()

        assertThat(org.texts).doesNotContainKey(OwnerText.BILLING_EMAIL)
    }

    @Test
    fun an_organisation_s_change_is_one_patch_answered_with_its_settings() = runTest {
        val org = api().change("tok", Owner.Organization("octo"), OwnerSettingsChange.Choice(OwnerChoice.DEFAULT_REPO_PERMISSION, "write")).value()
        api().change("tok", Owner.Organization("octo"), OwnerSettingsChange.Switch(OwnerSwitch.COMMIT_SIGNOFF, true))

        val patches = requests.filter { it.method == HttpMethod.Patch }
        assertThat(patches.map { it.call }).containsExactly("PATCH /orgs/octo", "PATCH /orgs/octo")
        assertThat(patches[0].sent).isEqualTo("""{"default_repository_permission":"write"}""")
        assertThat(patches[1].sent).isEqualTo("""{"web_commit_signoff_required":true}""")
        assertThat(org.texts[OwnerText.NAME]).isEqualTo("Octo")
    }

    @Test
    fun an_organisation_is_administered_by_its_active_admins() = runTest {
        assertThat(api().administers("tok", "octo")).isEqualTo(ForgeResult.Success(true))
        assertThat(api { json("""{"role":"member","state":"active"}""") }.administers("tok", "octo")).isEqualTo(ForgeResult.Success(false))
        assertThat(api { json("""{"role":"admin","state":"pending"}""") }.administers("tok", "octo")).isEqualTo(ForgeResult.Success(false))
        assertThat(api { json("""{"message":"Not Found"}""", HttpStatusCode.NotFound) }.administers("tok", "elsewhere")).isEqualTo(ForgeResult.Success(false))
        // A sign-in that may not read organisations can't tell: it is to sign in again.
        assertThat(api { json("""{"message":"Not Found"}""", HttpStatusCode.NotFound, scopes = "repo, user") }.administers("tok", "octo"))
            .isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
    }

    @Test
    fun a_profile_reads_its_status_from_the_graphql_api() = runTest {
        val status = api().settings("tok", Owner.Me).value().status

        assertThat(status).isEqualTo(
            fr.arthurbrugiere.forgeline.core.model.OwnerStatus(":coffee:", "Writing", busy = true, expiresAt = java.time.Instant.parse("2026-11-24T10:00:00Z")),
        )
    }

    @Test
    fun a_status_is_changed_until_when_it_is_asked_to_clear() = runTest {
        val status = fr.arthurbrugiere.forgeline.core.model.OwnerStatus(":coffee:", "Writing", busy = true)
        api().change("tok", Owner.Me, OwnerSettingsChange.Status(status, fr.arthurbrugiere.forgeline.core.model.StatusDuration.HOUR_3))

        val mutation = requests.single { it.url.encodedPath == "/graphql" && "changeUserStatus" in it.sent }.sent
        assertThat(mutation).contains(""""input":{"emoji":":coffee:","message":"Writing","limitedAvailability":true,"expiresAt":"2026-11-23T13:00:00Z"}""")
    }

    @Test
    fun an_empty_status_is_cleared() = runTest {
        api().change("tok", Owner.Me, OwnerSettingsChange.Status(fr.arthurbrugiere.forgeline.core.model.OwnerStatus()))

        val mutation = requests.single { it.url.encodedPath == "/graphql" && "changeUserStatus" in it.sent }.sent
        assertThat(mutation).contains(""""input":{"emoji":null,"message":null,"limitedAvailability":false,"expiresAt":null}""")
    }

    @Test
    fun an_organisation_has_no_status() = runTest {
        assertThat(api().settings("tok", Owner.Organization("octo")).value().status).isNull()
    }

    @Test
    fun one_s_blocks_and_an_organisation_s_are_listed_put_and_deleted() = runTest {
        val blocked = api().blocked("tok", Owner.Me).value()
        assertThat(blocked.map { it.login }).containsExactly("spammer")
        assertThat(api().blocked("tok", Owner.Organization("octo")).value().single().avatarUrl).isEqualTo("https://avatars.example/spammer")

        api().setBlocked("tok", Owner.Me, "spammer", blocked = true)
        api().setBlocked("tok", Owner.Organization("octo"), "spammer", blocked = false)

        assertThat(requests.filter { it.method != HttpMethod.Get }.map { it.call }).containsExactly("PUT /user/blocks/spammer", "DELETE /orgs/octo/blocks/spammer").inOrder()
    }

    @Test
    fun an_organisation_s_blocks_want_admin_org() = runTest {
        val refused = api { json("""{"message":"Not Found"}""", HttpStatusCode.NotFound, scopes = "repo, user") }.blocked("tok", Owner.Organization("octo"))

        assertThat(refused).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
    }

    @Test
    fun an_organisation_s_owners_members_and_invitations_read_apart() = runTest {
        val members = api().members("tok", "octo").value()

        assertThat(members.members.map { it.login to it.role }).containsExactly("mona" to "admin", "hubot" to "member")
        assertThat(members.invitations.single()).isEqualTo(fr.arthurbrugiere.forgeline.core.model.OrgInvitation("7", "new@octo.example", "direct_member"))
        assertThat(members.roles).containsExactly("admin", "member").inOrder()
        assertThat(members.inviteRoles).contains("direct_member")
        assertThat(members.invitesByEmail).isTrue()
    }

    @Test
    fun a_member_s_role_is_put_and_a_member_removed_with_their_membership() = runTest {
        api().setRole("tok", "octo", "hubot", "admin")
        api().removeMember("tok", "octo", "hubot")

        val writes = requests.filter { it.method != HttpMethod.Get }
        assertThat(writes.map { it.call }).containsExactly("PUT /orgs/octo/memberships/hubot", "DELETE /orgs/octo/memberships/hubot").inOrder()
        assertThat(writes[0].sent).isEqualTo("""{"role":"admin"}""")
    }

    @Test
    fun someone_is_invited_by_their_id_looked_up_or_by_email() = runTest {
        api().invite("tok", "octo", "newcomer", "direct_member")
        api().invite("tok", "octo", "friend@octo.example", "admin")
        api().cancelInvitation("tok", "octo", "7")

        val posts = requests.filter { it.method == HttpMethod.Post }
        assertThat(posts.map { it.sent }).containsExactly(
            """{"invitee_id":4242,"role":"direct_member"}""", """{"email":"friend@octo.example","role":"admin"}""",
        ).inOrder()
        assertThat(requests.last().call).isEqualTo("DELETE /orgs/octo/invitations/7")
    }

    @Test
    fun an_interaction_limit_reads_as_who_may_and_for_about_how_long() = runTest {
        val choices = api().settings("tok", Owner.Me).value().choices

        assertThat(choices[OwnerChoice.INTERACTION_LIMIT]?.value).isEqualTo("collaborators_only")
        assertThat(choices[OwnerChoice.INTERACTION_LIMIT]?.options).containsExactly("", "existing_users", "contributors_only", "collaborators_only").inOrder()
        // A week from the fixed clock's 2026-11-23.
        assertThat(choices[OwnerChoice.INTERACTION_LIMIT_DURATION]?.value).isEqualTo("one_week")
    }

    @Test
    fun no_limit_reads_as_everyone_and_a_sign_in_without_the_scope_offers_none() = runTest {
        val none = api { if (it.url.encodedPath == "/orgs/octo/interaction-limits") respond("", HttpStatusCode.NoContent) else answer(it) }
            .settings("tok", Owner.Organization("octo")).value()
        assertThat(none.choices[OwnerChoice.INTERACTION_LIMIT]?.value).isEmpty()

        val unreadable = api { if (it.url.encodedPath == "/user/interaction-limits") json("""{"message":"Forbidden"}""", HttpStatusCode.Forbidden) else answer(it) }
            .settings("tok", Owner.Me).value()
        assertThat(unreadable.choices).doesNotContainKey(OwnerChoice.INTERACTION_LIMIT)
    }

    @Test
    fun a_limit_is_put_with_how_long_it_holds_and_none_deletes_it() = runTest {
        api().change("tok", Owner.Organization("octo"), OwnerSettingsChange.Choice(OwnerChoice.INTERACTION_LIMIT, "contributors_only"))
        api().change("tok", Owner.Me, OwnerSettingsChange.Choice(OwnerChoice.INTERACTION_LIMIT_DURATION, "six_months"))
        api().change("tok", Owner.Me, OwnerSettingsChange.Choice(OwnerChoice.INTERACTION_LIMIT, ""))

        val writes = requests.filter { it.method == HttpMethod.Put || it.method == HttpMethod.Delete }
        assertThat(writes.map { it.call }).containsExactly(
            "PUT /orgs/octo/interaction-limits", "PUT /user/interaction-limits", "DELETE /user/interaction-limits",
        ).inOrder()
        assertThat(writes[0].sent).isEqualTo("""{"limit":"contributors_only","expiry":"one_week"}""")
        assertThat(writes[1].sent).isEqualTo("""{"limit":"collaborators_only","expiry":"six_months"}""")
    }

    @Test
    fun an_organisation_is_deleted_and_never_renamed() = runTest {
        val api = api { if (it.method == HttpMethod.Delete) json("{}", HttpStatusCode.Accepted) else answer(it) }

        assertThat(api.deleteOrganization("tok", "octo")).isEqualTo(ForgeResult.Success(fr.arthurbrugiere.forgeline.core.model.RepoDeletion.DELETED))
        assertThat(api.renamesOrganizations).isFalse()
        assertThat(requests.single { it.method == HttpMethod.Delete }.call).isEqualTo("DELETE /orgs/octo")
    }
}
