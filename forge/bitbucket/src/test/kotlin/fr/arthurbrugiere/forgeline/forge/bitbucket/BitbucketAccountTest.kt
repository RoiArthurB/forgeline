package fr.arthurbrugiere.forgeline.forge.bitbucket

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoDeletion
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSettingsChange
import fr.arthurbrugiere.forgeline.core.model.RepoVisibility
import fr.arthurbrugiere.forgeline.core.model.SearchScope
import fr.arthurbrugiere.forgeline.core.model.SearchSort
import fr.arthurbrugiere.forgeline.core.model.WorkKind
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.Base64

/** Signing in, people and workspaces, search, a repository's settings and forking: answers cut down from Bitbucket's. */
class BitbucketAccountTest {
    private val mock = BitbucketMock()
    private val people = BitbucketPeople()
    private val me = """{"type":"user","uuid":"{a1}","account_id":"557057:845513f1-5072-4be7-a153-0c6391790d01","nickname":"ana","display_name":"Ana Lima","links":{"avatar":{"href":"https://avatar.example/ana.png"}}}"""
    private val workspaces = """{"values":[{"administrator":true,"workspace":{"slug":"ana","uuid":"{a1}"}},{"administrator":false,"workspace":{"slug":"acme","links":{"avatar":{"href":"https://bitbucket.org/workspaces/acme/avatar/"}}}}]}"""

    @Test
    fun signing_in_sends_the_email_and_api_token_as_basic_and_keeps_that() = runTest {
        mock.get("/user", me)

        val tokens = BitbucketAuthApi(mock.http).signInWithPassword(" ana@example.org ", "ATATT3x ").value()

        assertThat(tokens.accessToken).isEqualTo("Basic " + Base64.getEncoder().encodeToString("ana@example.org:ATATT3x".toByteArray()))
        assertThat(tokens.refreshToken).isNull()
        assertThat(tokens.expiresInSeconds).isNull()
        assertThat(mock.requests.single().headers["Authorization"]).isEqualTo(tokens.accessToken)
        val user = BitbucketAuthApi(mock.http).fetchAuthenticatedUser(tokens.accessToken).value()
        assertThat(user.login).isEqualTo("ana")
        assertThat(user.name).isEqualTo("Ana Lima")
    }

    @Test
    fun a_refused_token_is_unauthorized() = runTest {
        mock.get("/user", """{"type":"error","error":{"message":"Unauthorized"}}""", HttpStatusCode.Unauthorized)

        assertThat(BitbucketAuthApi(mock.http).signInWithPassword("ana@example.org", "wrong")).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
    }

    @Test
    fun an_access_token_goes_as_a_bearer() {
        assertThat(bitbucketAuthorization("ATCTT3x")).isEqualTo("Bearer ATCTT3x")
        assertThat(bitbucketAuthorization(TOKEN)).isEqualTo(TOKEN)
    }

    @Test
    fun bitbucket_s_errors_read_as_its_message_and_detail() {
        assertThat(errorMessage("""{"type":"error","error":{"message":"Bad request","detail":"title: This field is required"}}""")).isEqualTo("Bad request: title: This field is required")
    }

    @Test
    fun a_workspace_is_a_profile_by_its_slug() = runTest {
        mock.get("/workspaces/atlassian", """{"type":"workspace","slug":"atlassian","name":"Atlassian","is_personal":false,"links":{"avatar":{"href":"https://bitbucket.org/workspaces/atlassian/avatar/"}},"created_on":"2011-07-08T00:00:00+00:00"}""")

        val profile = BitbucketUserApi(mock.http, people).user(null, "atlassian").value()

        assertThat(profile.login).isEqualTo("atlassian")
        assertThat(profile.name).isEqualTo("Atlassian")
        assertThat(profile.isOrganization).isTrue()
    }

    @Test
    fun a_person_seen_by_their_nickname_is_asked_for_by_their_uuid() = runTest {
        people.user(fr.arthurbrugiere.forgeline.forge.common.ForgeJson.decodeFromString<fr.arthurbrugiere.forgeline.forge.bitbucket.api.Account>("""{"nickname":"Ben Kelley","uuid":"{933f}"}"""))
        mock.get("/users/%7B933f%7D", """{"nickname":"Ben Kelley","display_name":"Ben K.","uuid":"{933f}"}""")

        val profile = BitbucketUserApi(mock.http, people).user(null, "Ben Kelley").value()

        assertThat(profile.login).isEqualTo("Ben Kelley")
        assertThat(profile.name).isEqualTo("Ben K.")
        assertThat(profile.isOrganization).isFalse()
    }

    @Test
    fun a_workspace_s_repositories_and_every_one_the_person_reaches() = runTest {
        mock.get("/user/workspaces", workspaces)
        mock.get("/repositories/ana", """{"values":[{"full_name":"ana/notes","slug":"notes","is_private":true,"updated_on":"2026-11-10T00:00:00+00:00"}]}""")
        mock.get("/repositories/acme", """{"values":[{"full_name":"acme/site","slug":"site","updated_on":"2026-11-12T00:00:00+00:00"}]}""")
        val users = BitbucketUserApi(mock.http, people)

        assertThat(users.repos(null, "acme").value().map { it.id.fullName }).containsExactly("acme/site")
        val reachable = users.reachableRepos(TOKEN).value()
        assertThat(reachable.map { it.id.fullName }).containsExactly("acme/site", "ana/notes").inOrder()
        assertThat(reachable[1].isPrivate).isTrue()
        assertThat(users.starred(null, "ana").value()).isEmpty()
        assertThat(users.isFollowing(TOKEN, "acme")).isEqualTo(ForgeResult.Failure(ForgeError.Unsupported))
    }

    @Test
    fun organizations_are_the_workspaces_besides_one_s_own() = runTest {
        mock.get("/user", me)
        mock.get("/user/workspaces", workspaces)

        val organizations = BitbucketUserApi(mock.http, people).organizations(TOKEN).value()

        assertThat(organizations.map { it.login }).containsExactly("acme")
        assertThat(organizations.single().forge).isEqualTo(ForgeInstance.Bitbucket)
    }

    @Test
    fun repositories_are_searched_by_name_in_the_person_s_workspaces() = runTest {
        mock.get("/user/workspaces", workspaces)
        mock.get("/repositories/ana", """{"size":1,"values":[{"full_name":"ana/site-notes","updated_on":"2026-11-01T00:00:00+00:00"}]}""")
        mock.get("/repositories/acme", """{"size":1,"values":[{"full_name":"acme/site","updated_on":"2026-11-12T00:00:00+00:00"}],"next":"x?page=2"}""")
        val search = BitbucketSearchApi(mock.http, people)

        val page = search.repositories(TOKEN, "site", sort = SearchSort.UPDATED).value()

        assertThat(page.items.map { it.id.fullName }).containsExactly("acme/site", "ana/site-notes").inOrder()
        assertThat(page.totalCount).isEqualTo(2)
        assertThat(page.nextPage).isEqualTo(2)
        val asked = mock.requests.first { it.url.encodedPath.endsWith("/repositories/acme") }.url.parameters
        assertThat(asked["q"]).isEqualTo("name ~ \"site\"")
        assertThat(asked["sort"]).isEqualTo("-updated_on")
        assertThat(search.sorts(SearchScope.REPOSITORIES)).containsExactly(SearchSort.BEST_MATCH, SearchSort.UPDATED)
    }

    @Test
    fun signed_out_nothing_is_searched() = runTest {
        val search = BitbucketSearchApi(mock.http, people)

        assertThat(search.repositories(null, "site")).isEqualTo(ForgeResult.Failure(ForgeError.Unsupported))
        assertThat(search.users(TOKEN, "ana")).isEqualTo(ForgeResult.Failure(ForgeError.Unsupported))
        assertThat(mock.requests).isEmpty()
    }

    @Test
    fun one_s_own_pull_requests_are_found_in_each_workspace() = runTest {
        mock.get("/user", me)
        mock.get("/user/workspaces", workspaces)
        mock.get("/workspaces/acme/pullrequests/%7Ba1%7D", """{"values":[{"id":9,"title":"Fix","state":"OPEN","created_on":"2026-11-10T00:00:00+00:00","destination":{"repository":{"full_name":"acme/site"}}}]}""")
        mock.get("/workspaces/ana/pullrequests/%7Ba1%7D", """{"values":[]}""")

        val work = BitbucketSearchApi(mock.http, people).work(TOKEN, "ana", WorkKind.OWN_PULL_REQUESTS).value()

        assertThat(work.single().repo).isEqualTo(RepoId("acme", "site", ForgeInstance.Bitbucket))
        assertThat(work.single().issue.number).isEqualTo(9)
        assertThat(BitbucketSearchApi(mock.http, people).work(TOKEN, "ana", WorkKind.ASSIGNED).value()).isEmpty()
    }

    @Test
    fun review_requests_are_looked_for_by_the_person_s_uuid() = runTest {
        mock.get("/user", me)
        mock.get("/user/workspaces", """{"values":[{"workspace":{"slug":"acme"}}]}""")
        mock.get("/repositories/acme", """{"values":[{"full_name":"acme/site"}]}""")
        mock.get("/repositories/acme/site/pullrequests", """{"values":[{"id":3,"title":"Review me","state":"OPEN","created_on":"2026-11-10T00:00:00+00:00","destination":{"repository":{"full_name":"acme/site"}}}]}""")

        val work = BitbucketSearchApi(mock.http, people).work(TOKEN, "ana", WorkKind.REVIEW_REQUESTED).value()

        assertThat(work.single().issue.title).isEqualTo("Review me")
        assertThat(mock.requests.last().url.parameters["q"]).isEqualTo("state = \"OPEN\" AND reviewers.uuid = \"{a1}\"")
    }

    @Test
    fun settings_read_and_change_one_field_at_a_time() = runTest {
        mock.get(ACE, fixture("repo.json"))
        mock.write(HttpMethod.Put, ACE, """{"full_name":"atlassian/atlassian-connect-express","description":"New","is_private":true,"mainbranch":{"name":"master"}}""")
        val settings = BitbucketRepoSettingsApi(mock.http)

        val read = settings.settings(TOKEN, ace).value()
        assertThat(read.defaultBranch).isEqualTo("master")
        assertThat(read.visibility).isEqualTo(RepoVisibility.PUBLIC)

        assertThat(settings.change(TOKEN, ace, RepoSettingsChange.Description("New")).value().description).isEqualTo("New")
        assertThat(mock.sent(HttpMethod.Put, ACE).toString()).isEqualTo("""{"description":"New"}""")
        settings.change(TOKEN, ace, RepoSettingsChange.Visibility(RepoVisibility.PRIVATE)).value()
        assertThat(mock.sent(HttpMethod.Put, ACE).toString()).isEqualTo("""{"is_private":true}""")
        settings.change(TOKEN, ace, RepoSettingsChange.DefaultBranch("develop")).value()
        assertThat(mock.sent(HttpMethod.Put, ACE).toString()).isEqualTo("""{"mainbranch":{"name":"develop"}}""")
        settings.change(TOKEN, ace, RepoSettingsChange.Website("https://ace.example")).value()
        assertThat(mock.sent(HttpMethod.Put, ACE).toString()).isEqualTo("""{"website":"https://ace.example"}""")
        assertThat(settings.change(TOKEN, ace, RepoSettingsChange.Topics(listOf("x")))).isEqualTo(ForgeResult.Failure(ForgeError.Unsupported))
    }

    @Test
    fun renaming_moves_the_repository_and_deleting_removes_it() = runTest {
        mock.write(HttpMethod.Put, ACE, """{"full_name":"atlassian/ace","slug":"ace","name":"ACE"}""")
        mock.write(HttpMethod.Delete, ACE, "", HttpStatusCode.NoContent)
        val settings = BitbucketRepoSettingsApi(mock.http)

        assertThat(settings.rename(TOKEN, ace, "ACE").value()).isEqualTo(RepoId("atlassian", "ace", ForgeInstance.Bitbucket))
        assertThat(mock.sent(HttpMethod.Put, ACE).toString()).isEqualTo("""{"name":"ACE"}""")
        assertThat(settings.delete(TOKEN, ace).value()).isEqualTo(RepoDeletion.DELETED)
        assertThat(mock.writes).containsExactly("PUT $ACE", "DELETE $ACE").inOrder()
    }

    @Test
    fun a_fork_goes_to_the_person_s_own_workspace() = runTest {
        mock.write(HttpMethod.Post, "$ACE/forks", """{"full_name":"ana/atlassian-connect-express","slug":"atlassian-connect-express","workspace":{"slug":"ana"}}""", HttpStatusCode.Created)
        val stars = BitbucketStarApi(mock.http)

        assertThat(stars.fork(TOKEN, ace).value()).isEqualTo(RepoId("ana", "atlassian-connect-express", ForgeInstance.Bitbucket))
        assertThat(mock.writes).containsExactly("POST $ACE/forks")
        assertThat(stars.setStarred(TOKEN, ace, true)).isEqualTo(ForgeResult.Failure(ForgeError.Unsupported))
    }

    @Test
    fun a_forge_without_trending_feed_or_notifications() {
        val apis = BitbucketApis(mock.http)
        assertThat(apis.trending).isNull()
        assertThat(apis.trendingMeter).isNull()
        assertThat(apis.auth.supportsPasswordSignIn).isTrue()
        assertThat(apis.auth.supportsDeviceFlow).isFalse()
    }
}
