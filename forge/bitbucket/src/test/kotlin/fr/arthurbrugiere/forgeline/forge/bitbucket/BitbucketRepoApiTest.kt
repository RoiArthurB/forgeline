package fr.arthurbrugiere.forgeline.forge.bitbucket

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.model.IssueQuery
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.RepoAccess
import fr.arthurbrugiere.forgeline.core.model.RepoFileType
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** atlassian/atlassian-connect-express as Bitbucket answered on 2026-11-18, cut down. */
class BitbucketRepoApiTest {
    private val mock = BitbucketMock()
    private val api = BitbucketRepoApi(mock.http)

    @Test
    fun a_repository_reads_with_what_bitbucket_counts_apart() = runTest {
        mock.get(ACE, fixture("repo.json"))
        mock.get("$ACE/forks", """{"size":3}""")
        mock.get("$ACE/watchers", """{"size":48}""")
        mock.get("$ACE/pullrequests", """{"size":5}""")
        mock.get("/user/workspaces/atlassian/permissions/repositories", """{"values":[{"permission":"admin"}]}""")

        val (details, rights) = api.repoAndRights(TOKEN, ace).value()

        assertThat(details.id).isEqualTo(ace)
        assertThat(details.description).startsWith("atlassian-connect-express (ACE)")
        assertThat(details.defaultBranch).isEqualTo("master")
        assertThat(details.forks).isEqualTo(3)
        assertThat(details.watchers).isEqualTo(48)
        assertThat(details.openPulls).isEqualTo(5)
        assertThat(details.stars).isEqualTo(0)
        // Gone from Bitbucket in 2026.
        assertThat(details.hasIssues).isFalse()
        assertThat(details.hasWiki).isFalse()
        assertThat(details.ownerAvatarUrl).startsWith("https://bytebucket.org/ravatar/")
        assertThat(rights?.access).isEqualTo(RepoAccess.ADMIN)
    }

    @Test
    fun signed_out_a_repository_reads_without_rights_and_its_counts_are_optional() = runTest {
        mock.get(ACE, fixture("repo.json"))

        val (details, rights) = api.repoAndRights(null, ace).value()

        assertThat(details.forks).isEqualTo(0)
        assertThat(details.openPulls).isNull()
        assertThat(rights).isNull()
    }

    @Test
    fun a_folder_lists_its_folders_first() = runTest {
        mock.get("$ACE/src/master/", fixture("src_root.json"))

        val files = api.contents(null, ace, "", "master").value()

        assertThat(files.first().type).isEqualTo(RepoFileType.DIR)
        assertThat(files.map { it.type }).isInOrder(compareBy<RepoFileType> { it != RepoFileType.DIR })
        assertThat(files.filter { it.type == RepoFileType.FILE }.all { it.size > 0 }).isTrue()
    }

    @Test
    fun a_sub_folder_s_path_keeps_its_slashes() = runTest {
        mock.get("$ACE/src/master/lib/store/", """{"values":[{"type":"commit_file","path":"lib/store/redis.js","size":120}]}""")

        val files = api.contents(null, ace, "lib/store", "master").value()

        assertThat(files.single().name).isEqualTo("redis.js")
    }

    @Test
    fun the_readme_is_the_root_s_readme_file() = runTest {
        mock.get(ACE, fixture("repo.json"))
        mock.get("$ACE/src/master/", """{"values":[{"type":"commit_directory","path":"docs"},{"type":"commit_file","path":"README.txt","size":3},{"type":"commit_file","path":"README.md","size":9}]}""")
        mock.on(HttpMethod.Get, "$ACE/src/master/README.md") { respond("# ACE\n") }

        val readme = api.readme(null, ace).value()!!

        assertThat(readme.path).isEqualTo("README.md")
        assertThat(readme.markdown).isEqualTo("# ACE\n")
    }

    @Test
    fun branches_and_tags_follow_every_page() = runTest {
        // The second page is asked at the address Bitbucket gave.
        mock.on(HttpMethod.Get, "$ACE/refs/branches") { request ->
            if (request.url.parameters["page"] == "2") json("""{"values":[{"name":"Feature"}]}""") else json("""{"values":[{"name":"master"},{"name":"develop"}],"next":"https://api.bitbucket.org/2.0$ACE/refs/branches?page=2"}""")
        }
        mock.get("$ACE/refs/tags", fixture("tags.json"))

        val refs = api.refs(null, ace).value()

        assertThat(refs.branches).containsExactly("develop", "Feature", "master").inOrder()
        assertThat(refs.tags).hasSize(2)
    }

    @Test
    fun pull_requests_list_by_state_and_words_without_their_descriptions() = runTest {
        mock.get("$ACE/pullrequests", """{"values":[{"id":604,"title":"Bump","state":"OPEN","author":{"nickname":"bot"},"created_on":"2026-11-15T07:22:17+00:00","comment_count":0}]}""")

        val open = api.pullRequests(null, ace, IssueQuery(text = "prettier")).value()
        api.pullRequests(null, ace, IssueQuery(open = false, page = 2)).value()

        assertThat(open.single().isPullRequest).isTrue()
        assertThat(open.single().state).isEqualTo(IssueState.OPEN)
        val (first, second) = mock.requests.map { it.url.parameters }
        assertThat(first.getAll("state")).containsExactly("OPEN")
        assertThat(first["q"]).isEqualTo("(title ~ \"prettier\" OR description ~ \"prettier\")")
        assertThat(first["fields"]).isEqualTo("-values.summary,-values.rendered,-values.description,-values.links")
        assertThat(second.getAll("state")).containsExactly("MERGED", "DECLINED", "SUPERSEDED").inOrder()
        assertThat(second["page"]).isEqualTo("2")
    }

    @Test
    fun a_repository_lists_no_issues() = runTest {
        assertThat(api.issues(null, ace).value()).isEmpty()
        assertThat(mock.requests).isEmpty()
    }

    @Test
    fun tags_stand_for_releases_with_their_archives() = runTest {
        mock.get("$ACE/refs/tags", fixture("tags.json"))

        val releases = api.releases(null, ace).value()

        val first = releases.first()
        assertThat(first.isLatest).isTrue()
        assertThat(first.zipUrl).isEqualTo("https://bitbucket.org/atlassian/atlassian-connect-express/get/${first.tag}.zip")
        assertThat(first.webUrl).isEqualTo("https://bitbucket.org/atlassian/atlassian-connect-express/src/${first.tag}/")
        assertThat(first.publishedAt).isNotNull()
    }

    @Test
    fun pipelines_list_as_runs_newest_first() = runTest {
        mock.get("$ACE/pipelines", """{"values":[${fixture("pipeline.json")}]}""")

        val runs = api.workflowRuns(null, ace).value()

        assertThat(runs.single().id).isEqualTo(3201)
        assertThat(mock.requests.single().url.parameters["sort"]).isEqualTo("-created_on")
    }

    @Test
    fun files_are_addressed_on_bitbucket_org() {
        assertThat(api.rawBaseUrl(ace, "master")).isEqualTo("https://bitbucket.org/atlassian/atlassian-connect-express/raw/master/")
        assertThat(api.blobBaseUrl(ace, "master")).isEqualTo("https://bitbucket.org/atlassian/atlassian-connect-express/src/master/")
    }
}
