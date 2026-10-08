package fr.arthurbrugiere.forgeline.forge.github

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.IssueQuery
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.RepoFileType
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RunConclusion
import fr.arthurbrugiere.forgeline.core.model.RunStatus
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant

/** Fixtures are real api.github.com responses for paperclipai/paperclip captured on 2026-09-26. */
class GitHubRepoApiTest {
    private val requests = java.util.concurrent.CopyOnWriteArrayList<HttpRequestData>()
    private val paperclip = RepoId("paperclipai", "paperclip")

    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/github/repo/$name")) { name }.readText()

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        GitHubRepoApi(gitHubHttpClient(MockEngine { requests += it; handler(it) }))

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    @Test
    fun a_repository_with_its_issues_switched_off_says_so() = runTest {
        val repo = api { json(fixture("repo.json").replace("\"has_issues\":true", "\"has_issues\":false")) }.repo(null, paperclip).value()

        assertThat(repo.hasIssues).isFalse()
    }

    @Test
    fun parses_repository_details() = runTest {
        val repo = api { json(fixture("repo.json")) }.repo(null, paperclip).value()

        assertThat(repo.id).isEqualTo(paperclip)
        assertThat(repo.defaultBranch).isEqualTo("master")
        assertThat(repo.license).isEqualTo("MIT")
        assertThat(repo.language).isEqualTo("TypeScript")
        assertThat(repo.stars).isGreaterThan(80_000)
        assertThat(repo.ownerAvatarUrl).startsWith("https://avatars.githubusercontent.com/")
        assertThat(repo.isArchived).isFalse()
        assertThat(repo.hasIssues).isTrue()
        assertThat(requests.single().url.toString()).isEqualTo("https://api.github.com/repos/paperclipai/paperclip")
    }

    @Test
    fun a_renamed_repo_reports_its_canonical_name() = runTest {
        // GitHub redirects old names to the moved repo; its body carries the new owner/name,
        // which callers must use afterwards (search rejects old names with 422).
        val moved = fixture("repo.json")
            .replace("\"full_name\":\"paperclipai/paperclip\"", "\"full_name\":\"newowner/paperclip\"")
            .replaceFirst("\"login\":\"paperclipai\"", "\"login\":\"newowner\"")

        val repo = api { json(moved) }.repo(null, paperclip).value()

        assertThat(repo.id).isEqualTo(RepoId("newowner", "paperclip"))
    }

    @Test
    fun anonymous_calls_send_no_credentials_and_signed_in_calls_do() = runTest {
        val api = api { json(fixture("repo.json")) }

        api.repo(null, paperclip)
        api.repo("ghp_token", paperclip)

        assertThat(requests[0].headers[HttpHeaders.Authorization]).isNull()
        assertThat(requests[1].headers[HttpHeaders.Authorization]).isEqualTo("Bearer ghp_token")
        assertThat(requests.all { it.headers["X-GitHub-Api-Version"] == "2022-11-28" }).isTrue()
    }

    @Test
    fun a_missing_repository_is_an_http_404() = runTest {
        val result = api { json("""{"message":"Not Found"}""", HttpStatusCode.NotFound) }.repo(null, paperclip)

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Http(404, "Not Found")))
    }

    @Test
    fun decodes_the_readme_and_keeps_its_path() = runTest {
        val readme = api { json(fixture("readme.json")) }.readme(null, paperclip).value()!!

        assertThat(readme.path).isEqualTo("README.md")
        assertThat(readme.markdown).startsWith("<p align=\"center\">")
        assertThat(readme.markdown).contains("# Paperclip is the app people use to manage AI agents for work.")
    }

    @Test
    fun a_readme_can_be_read_at_another_ref() = runTest {
        api { json(fixture("readme.json")) }.readme(null, paperclip, ref = "v2026.916.1")

        assertThat(requests.single().url.toString()).isEqualTo("https://api.github.com/repos/paperclipai/paperclip/readme?ref=v2026.916.1")
    }

    @Test
    fun lists_every_branch_and_tag_in_one_request_each() = runTest {
        // Fixtures are trimmed from real matching-refs answers (977 branches, 1664 tags on 2026-09-29).
        val refs = api {
            json(fixture(if (it.url.encodedPath.endsWith("/heads")) "matching_refs_heads.json" else "matching_refs_tags.json"))
        }.refs(null, paperclip).value()

        assertThat(refs.branches).containsExactly(
            "LOOA-700-recovery-tightloop",
            "LOOA-956-paperclip-feature-skill-usage-analytics-in-the-skill-browser-and-studio",
            "master",
            "PAP-10015-per-use-join-leave-projects-and-agents",
            "PAP-10026-team-s-based-paperclip-not-always-a-fixed-ceo",
        ).inOrder()
        // Newest versions first, comparing numbers as numbers.
        assertThat(refs.tags).containsExactly(
            "v2026.916.1", "v2026.916.0", "v2026.831.1",
            "@paperclipai/adapter-claude-local@0.2.4", "@paperclipai/adapter-claude-local@0.2.3", "@paperclipai/adapter-claude-local@0.2.2",
        ).inOrder()
        assertThat(requests.map { it.url.encodedPath }).containsExactly(
            "/repos/paperclipai/paperclip/git/matching-refs/heads",
            "/repos/paperclipai/paperclip/git/matching-refs/tags",
        )
    }

    @Test
    fun tags_compare_their_numbers_as_numbers() = runTest {
        val tags = """[{"ref":"refs/tags/v1.9.0"},{"ref":"refs/tags/v1.10.0"},{"ref":"refs/tags/v1.2.0"}]"""

        val refs = api { json(if (it.url.encodedPath.endsWith("/heads")) "[]" else tags) }.refs(null, paperclip).value()

        assertThat(refs.tags).containsExactly("v1.10.0", "v1.9.0", "v1.2.0").inOrder()
    }

    @Test
    fun an_empty_repository_has_no_refs() = runTest {
        // GitHub answers 409 "Git Repository is empty" for the git database endpoints.
        val result = api { json("""{"message":"Git Repository is empty."}""", HttpStatusCode.Conflict) }.refs(null, paperclip)

        assertThat(result.value().branches).isEmpty()
        assertThat(result.value().tags).isEmpty()
    }

    @Test
    fun a_repo_without_readme_has_none() = runTest {
        val result = api { json("""{"message":"Not Found"}""", HttpStatusCode.NotFound) }.readme(null, paperclip)

        assertThat(result).isEqualTo(ForgeResult.Success(null))
    }

    @Test
    fun lists_a_directory_with_folders_first() = runTest {
        val entries = api { json(fixture("contents_root.json")) }.contents(null, paperclip, "", "master").value()

        assertThat(entries).hasSize(47)
        val firstFile = entries.indexOfFirst { it.type == RepoFileType.FILE }
        assertThat(entries.take(firstFile).all { it.type == RepoFileType.DIR }).isTrue()
        assertThat(entries.drop(firstFile).none { it.type == RepoFileType.DIR }).isTrue()
        assertThat(entries.first().name).isEqualTo(".agents")
        assertThat(requests.single().url.toString()).isEqualTo("https://api.github.com/repos/paperclipai/paperclip/contents?ref=master")
    }

    @Test
    fun paths_are_encoded_segment_by_segment() = runTest {
        api { json("[]") }.contents(null, paperclip, "docs/my guide", "feature/x")

        assertThat(requests.single().url.toString())
            .isEqualTo("https://api.github.com/repos/paperclipai/paperclip/contents/docs/my%20guide?ref=feature%2Fx")
    }

    @Test
    fun reads_a_text_file() = runTest {
        val text = api { json(fixture("file_package_json.json")) }.fileText(null, paperclip, "package.json", "master").value()

        assertThat(text).startsWith("{")
        assertThat(text).contains("\"name\"")
    }

    @Test
    fun files_too_large_for_the_contents_api_are_reported() = runTest {
        val tooLarge = """{"type":"file","encoding":"none","content":"","size":5000000,"path":"big.bin","name":"big.bin"}"""

        val result = api { json(tooLarge) }.fileText(null, paperclip, "big.bin", "master")

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Http(413, "File too large to preview")))
    }

    @Test
    fun open_issues_come_from_search_so_pull_requests_never_crowd_them_out() = runTest {
        val issues = api { json(fixture("search_issues.json")) }.issues(null, paperclip).value()

        assertThat(issues).hasSize(5)
        assertThat(issues.first().number).isEqualTo(14127)
        assertThat(issues.all { !it.isPullRequest && it.state == IssueState.OPEN }).isTrue()
        assertThat(issues.first().comments).isNotNull()
        val url = requests.single().url
        assertThat(url.encodedPath).isEqualTo("/search/issues")
        assertThat(url.parameters["q"]).isEqualTo("repo:paperclipai/paperclip is:issue is:open")
        assertThat(url.parameters["sort"]).isEqualTo("created")
    }

    @Test
    fun lists_open_pull_requests() = runTest {
        val pulls = api { json(fixture("pulls.json")) }.pullRequests(null, paperclip).value()

        assertThat(pulls.map { it.number }).containsExactly(14129, 14128, 14126, 14125).inOrder()
        assertThat(pulls.all { it.isPullRequest }).isTrue()
        assertThat(pulls.first().author?.login).isEqualTo("basil-k-aji-dev")
        assertThat(pulls.first().comments).isNull()
    }

    @Test
    fun lists_releases_newest_first() = runTest {
        val releases = api { json(fixture("releases.json")) }.releases(null, paperclip).value()

        assertThat(releases.map { it.tag }).containsExactly("v2026.916.1", "v2026.916.0", "v2026.831.1").inOrder()
        assertThat(releases.first().publishedAt).isEqualTo(Instant.parse("2026-09-21T21:22:44Z"))
        assertThat(releases.first().body).isNotEmpty()
    }

    @Test
    fun lists_workflow_runs_with_status_and_conclusion() = runTest {
        val runs = api { json(fixture("runs.json")) }.workflowRuns(null, paperclip).value()

        assertThat(runs.map { it.status to it.conclusion }).containsExactly(
            RunStatus.QUEUED to null,
            RunStatus.COMPLETED to RunConclusion.SKIPPED,
            RunStatus.COMPLETED to RunConclusion.FAILURE,
            RunStatus.COMPLETED to RunConclusion.SKIPPED,
        ).inOrder()
        assertThat(runs.first().event).isEqualTo("pull_request")
        assertThat(runs.first().runNumber).isEqualTo(39953)
    }

    @Test
    fun readme_urls_point_at_raw_files_and_blob_pages() {
        val api = api { error("no request") }

        assertThat(api.rawBaseUrl(paperclip, "master")).isEqualTo("https://raw.githubusercontent.com/paperclipai/paperclip/master/")
        assertThat(api.blobBaseUrl(paperclip, "master")).isEqualTo("https://github.com/paperclipai/paperclip/blob/master/")
    }

    // Regression: only network errors were caught, so an answer the app couldn't read crashed it.

    @Test
    fun an_answer_missing_what_the_api_promises_is_a_failure_not_a_crash() = runTest {
        val result = api { json("""{"name":"paperclip"}""") }.repo(null, paperclip)

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Unreadable))
    }

    @Test
    fun a_web_page_where_data_was_expected_is_a_failure_not_a_crash() = runTest {
        val result = api { respond("<html>Maintenance</html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html")) }.repo(null, paperclip)

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Unreadable))
    }

    @Test
    fun a_date_that_is_not_one_is_a_failure_not_a_crash() = runTest {
        val body = """{"name":"paperclip","owner":{"login":"paperclipai"},"default_branch":"master","pushed_at":"yesterday"}"""

        assertThat(api { json(body) }.repo(null, paperclip)).isEqualTo(ForgeResult.Failure(ForgeError.Unreadable))
    }

    private val q get() = requests.last().url.parameters["q"]

    @Test
    fun closed_issues_are_asked_for_newest_first() = runTest {
        api { json(fixture("search_issues.json")) }.issues(null, paperclip, IssueQuery(open = false))

        assertThat(q).isEqualTo("repo:paperclipai/paperclip is:issue is:closed")
        assertThat(requests.last().url.parameters["sort"]).isEqualTo("created")
        assertThat(requests.last().url.parameters["order"]).isEqualTo("desc")
    }

    @Test
    fun words_are_looked_for_among_the_repository_s_issues_best_match_first() = runTest {
        api { json(fixture("search_issues.json")) }.issues(null, paperclip, IssueQuery(text = "  heartbeat recovery "))

        assertThat(q).isEqualTo("repo:paperclipai/paperclip is:issue is:open heartbeat recovery")
        // No sort: GitHub's own ranking of the match.
        assertThat(requests.last().url.parameters["sort"]).isNull()
        assertThat(requests.last().url.parameters["per_page"]).isEqualTo("30")
    }

    @Test
    fun closed_pull_requests_say_which_were_merged() = runTest {
        val merged = fixture("pulls.json").replaceFirst("\"merged_at\":null", "\"merged_at\":\"2026-09-26T10:00:00Z\"").replace("\"state\":\"open\"", "\"state\":\"closed\"")
        val pulls = api { json(merged) }.pullRequests(null, paperclip, IssueQuery(open = false)).value()

        assertThat(pulls.first().state).isEqualTo(IssueState.MERGED)
        assertThat(pulls.drop(1).map { it.state }.distinct()).containsExactly(IssueState.CLOSED)
        assertThat(requests.single().url.encodedPath).isEqualTo("/repos/paperclipai/paperclip/pulls")
        assertThat(requests.single().url.parameters["state"]).isEqualTo("closed")
    }

    @Test
    fun words_among_pull_requests_go_through_search_which_says_merged_differently() = runTest {
        // GitHub's search shape: a pull request carries its merge under "pull_request" (checked on cli/cli, 2026-10-02).
        val found = """{"items":[
            {"number":5143,"title":"Fix pager","state":"closed","user":{"login":"octocat"},"comments":3,"created_at":"2022-02-10T16:09:17Z","labels":[],
             "pull_request":{"merged_at":"2022-02-14T16:09:17Z"}},
            {"number":14155,"title":"Pager again","state":"closed","user":{"login":"hubot"},"comments":0,"created_at":"2026-09-10T16:09:17Z","labels":[],
             "pull_request":{"merged_at":null}}]}"""

        val pulls = api { json(found) }.pullRequests(null, paperclip, IssueQuery(open = false, text = "pager")).value()

        assertThat(pulls.map { it.state }).containsExactly(IssueState.MERGED, IssueState.CLOSED).inOrder()
        assertThat(pulls.all { it.isPullRequest }).isTrue()
        assertThat(requests.single().url.encodedPath).isEqualTo("/search/issues")
        assertThat(q).isEqualTo("repo:paperclipai/paperclip is:pr is:closed pager")
    }

    // Fixture: cli/cli's pinned issue, captured 2026-10-02.
    @Test
    fun pinned_issues_come_from_graphql() = runTest {
        val pinned = api { json(fixture("pinned.json")) }.pinnedIssues("tok", RepoId("cli", "cli")).value()

        val issue = pinned.single()
        assertThat(issue.number).isEqualTo(13118)
        assertThat(issue.title).startsWith("Upcoming PGP signing key rotation")
        assertThat(issue.state).isEqualTo(IssueState.OPEN)
        assertThat(issue.author?.login).isEqualTo("babakks")
        assertThat(issue.comments).isEqualTo(43)
        assertThat(issue.labels.map { it.name }).containsExactly("enhancement", "packaging").inOrder()
        assertThat(issue.isPullRequest).isFalse()
        val request = requests.single()
        assertThat(request.url.toString()).isEqualTo("https://api.github.com/graphql")
        assertThat(request.headers[HttpHeaders.Authorization]).isEqualTo("Bearer tok")
        assertThat((request.body as io.ktor.http.content.TextContent).text).contains(""""variables":{"owner":"cli","name":"cli"}""")
    }

    @Test
    fun a_repository_that_pins_nothing_answers_none() = runTest {
        assertThat(api { json("""{"data":{"repository":{"pinnedIssues":{"nodes":[]}}}}""") }.pinnedIssues("tok", paperclip).value()).isEmpty()
        // A repository GraphQL can't see answers no data at all.
        assertThat(api { json("""{"data":{"repository":null},"errors":[{"type":"NOT_FOUND","message":"no"}]}""") }.pinnedIssues("tok", paperclip).value()).isEmpty()
    }

    @Test
    fun signed_out_pinned_issues_are_not_asked_for() = runTest {
        // GitHub's GraphQL API answers nobody without a token.
        assertThat(api { error("no request expected") }.pinnedIssues(null, paperclip).value()).isEmpty()
        assertThat(requests).isEmpty()
    }

    @Test
    fun a_release_lists_its_files_its_source_and_its_page() = runTest {
        val release = api { json(fixture("releases.json")) }.releases(null, paperclip).value().first()

        val asset = release.assets.first()
        assertThat(asset.name).isEqualTo("feature-catalog.json")
        assertThat(asset.sizeBytes).isEqualTo(2329)
        assertThat(asset.downloads).isEqualTo(144)
        assertThat(asset.url).isEqualTo("https://github.com/paperclipai/paperclip/releases/download/v2026.916.1/feature-catalog.json")
        // The site's archive links, which a browser can fetch; the API's own need its headers.
        assertThat(release.zipUrl).isEqualTo("https://github.com/paperclipai/paperclip/archive/refs/tags/v2026.916.1.zip")
        assertThat(release.tarUrl).isEqualTo("https://github.com/paperclipai/paperclip/archive/refs/tags/v2026.916.1.tar.gz")
        assertThat(release.webUrl).isEqualTo("https://github.com/paperclipai/paperclip/releases/tag/v2026.916.1")
    }

    @Test
    fun the_newest_release_that_isn_t_a_pre_release_is_the_latest() = runTest {
        val releases = api { json(fixture("releases.json")) }.releases(null, paperclip).value()
        assertThat(releases.map { it.isLatest }).containsExactly(true, false, false).inOrder()

        val candidateFirst = fixture("releases.json").replaceFirst("\"prerelease\":false", "\"prerelease\":true")
        assertThat(api { json(candidateFirst) }.releases(null, paperclip).value().map { it.isLatest }).containsExactly(false, true, false).inOrder()
    }

    @Test
    fun one_release_is_asked_for_by_its_tag_even_one_with_slashes() = runTest {
        val one = kotlinx.serialization.json.Json.parseToJsonElement(fixture("releases.json")).let { (it as kotlinx.serialization.json.JsonArray).first().toString() }
        val api = api { json(one) }

        val release = api.release(null, paperclip, "v2026.916.1").value()
        api.release(null, paperclip, "desktop/v1.2")

        assertThat(release.tag).isEqualTo("v2026.916.1")
        assertThat(release.assets).isNotEmpty()
        // Whether it is the latest is only known from the list.
        assertThat(release.isLatest).isFalse()
        assertThat(requests.map { it.url.encodedPath }).containsExactly(
            "/repos/paperclipai/paperclip/releases/tags/v2026.916.1", "/repos/paperclipai/paperclip/releases/tags/desktop/v1.2",
        ).inOrder()
    }

    @Test
    fun a_tag_without_a_release_is_not_found() = runTest {
        val result = api { json("""{"message":"Not Found"}""", HttpStatusCode.NotFound) }.release(null, paperclip, "nope")

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Http(404, "Not Found")))
    }

    @Test
    fun a_release_s_reactions_are_counted() = runTest {
        val reacted = """[{"tag_name":"v1","html_url":"https://github.com/paperclipai/paperclip/releases/tag/v1","assets":[],
            "reactions":{"total_count":7,"+1":4,"-1":0,"laugh":0,"hooray":2,"confused":0,"heart":0,"rocket":1,"eyes":0}}]"""

        val release = api { json(reacted) }.releases(null, paperclip).value().single()

        assertThat(release.reactions).containsExactly(Reaction.THUMBS_UP, 4, Reaction.HOORAY, 2, Reaction.ROCKET, 1)
    }

    @Test
    fun a_further_page_of_issues_and_of_pull_requests_is_asked_for_by_its_number() = runTest {
        // Regression: the lists stopped at their first 30, with no way to the rest.
        api { json(fixture("search_issues.json")) }.issues(null, paperclip, IssueQuery(page = 3))
        assertThat(requests.last().url.parameters["page"]).isEqualTo("3")
        assertThat(requests.last().url.parameters["per_page"]).isEqualTo("30")

        api { json("[]") }.pullRequests(null, paperclip, IssueQuery(open = false, page = 2))
        assertThat(requests.last().url.encodedPath).isEqualTo("/repos/paperclipai/paperclip/pulls")
        assertThat(requests.last().url.parameters["page"]).isEqualTo("2")

        api { json(fixture("search_issues.json")) }.issues(null, paperclip)
        assertThat(requests.last().url.parameters["page"]).isEqualTo("1")
    }

    // The shapes GitHub's GraphQL API answered on 2026-10-08 (vercel/next.js), shortened.
    private val discussionList = """{"data":{"repository":{"discussions":{"pageInfo":{"hasNextPage":true,"endCursor":"Y3Vyc29y"},"nodes":[
        {"number":99839,"title":"Turbopack Error","createdAt":"2026-10-08T02:05:19Z","upvoteCount":1,"isAnswered":null,"category":{"name":"Turbopack Error Report"},"author":{"login":"Shaffan23","avatarUrl":"https://avatars.example/1"},"comments":{"totalCount":0}},
        {"number":99836,"title":"App Router PDF downloads","createdAt":"2026-10-07T22:12:26Z","upvoteCount":4,"isAnswered":false,"category":{"name":"Help"},"author":null,"comments":{"totalCount":12}}]}}}}"""

    private val oneDiscussion = """{"data":{"repository":{"discussion":{"number":7,"title":"How do I page?","body":"I can't find it.","createdAt":"2026-10-01T10:00:00Z","upvoteCount":3,"isAnswered":true,
        "category":{"name":"Q&A"},"author":{"login":"alice","avatarUrl":null},"comments":{"totalCount":2,"nodes":[
        {"id":"c1","body":"Use the cursor.","createdAt":"2026-10-01T11:00:00Z","upvoteCount":5,"isAnswer":true,"author":{"login":"bob","avatarUrl":null},
         "replies":{"totalCount":40,"nodes":[{"id":"r1","body":"Thanks!","createdAt":"2026-10-01T12:00:00Z","upvoteCount":0,"isAnswer":false,"author":{"login":"alice","avatarUrl":null}}]}},
        {"id":"c2","body":"Same question.","createdAt":"2026-10-02T11:00:00Z","upvoteCount":0,"isAnswer":false,"author":null,"replies":{"totalCount":0,"nodes":[]}}]}}}}}"""

    private fun body() = (requests.last().body as io.ktor.http.content.TextContent).text

    @Test
    fun discussions_are_listed_with_what_asks_for_the_next_page() = runTest {
        val page = api { json(discussionList) }.discussions("tok", paperclip).value()

        assertThat(page.next).isEqualTo("Y3Vyc29y")
        assertThat(page.items.map { it.number }).containsExactly(99839, 99836).inOrder()
        val first = page.items[0]
        assertThat(first.category).isEqualTo("Turbopack Error Report")
        assertThat(first.author?.login).isEqualTo("Shaffan23")
        // A category that takes no answer says nothing of one.
        assertThat(first.isAnswered).isNull()
        assertThat(page.items[1].isAnswered).isFalse()
        assertThat(page.items[1].comments).isEqualTo(12)
        assertThat(page.items[1].author).isNull()
        assertThat(requests.single().url.encodedPath).isEqualTo("/graphql")
        assertThat(body()).contains("\"owner\":\"paperclipai\"")
        assertThat(body()).doesNotContain("\"after\"")
    }

    @Test
    fun the_next_page_of_discussions_is_asked_for_after_the_last_one_and_the_last_page_has_none() = runTest {
        val last = """{"data":{"repository":{"discussions":{"pageInfo":{"hasNextPage":false,"endCursor":"end"},"nodes":[]}}}}"""

        val page = api { json(last) }.discussions("tok", paperclip, after = "Y3Vyc29y").value()

        assertThat(body()).contains("\"after\":\"Y3Vyc29y\"")
        assertThat(page.next).isNull()
        assertThat(page.items).isEmpty()
    }

    @Test
    fun a_discussion_comes_with_its_comments_their_replies_and_its_answer() = runTest {
        val discussion = api { json(oneDiscussion) }.discussion("tok", paperclip, 7).value()

        assertThat(discussion.summary.title).isEqualTo("How do I page?")
        assertThat(discussion.summary.isAnswered).isTrue()
        assertThat(discussion.body).isEqualTo("I can't find it.")
        assertThat(discussion.comments.map { it.id }).containsExactly("c1", "c2").inOrder()
        assertThat(discussion.comments[0].isAnswer).isTrue()
        assertThat(discussion.comments[0].replies.single().body).isEqualTo("Thanks!")
        assertThat(discussion.comments[1].author).isNull()
        // Forty replies, one read: the rest is on the forge's site.
        assertThat(discussion.comments[0].replyCount).isEqualTo(40)
        assertThat(discussion.isPartial).isTrue()
        assertThat(body()).contains("\"number\":7")
    }

    @Test
    fun a_discussion_that_is_not_there_is_not_found() = runTest {
        // GitHub answers 200 holding nothing, with the reason beside it.
        val gone = """{"data":{"repository":{"discussion":null}},"errors":[{"type":"NOT_FOUND","message":"Could not resolve to a Discussion with the number of 9."}]}"""

        assertThat(api { json(gone) }.discussion("tok", paperclip, 9)).isEqualTo(ForgeResult.Failure(ForgeError.Http(404, "Not Found")))
    }

    @Test
    fun signed_out_discussions_are_not_asked_for() = runTest {
        // GraphQL answers nobody signed out.
        assertThat(api { json(discussionList) }.discussions(null, paperclip)).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
        assertThat(api { json(oneDiscussion) }.discussion(null, paperclip, 7)).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
        assertThat(requests).isEmpty()
    }

    @Test
    fun a_revoked_token_fails_discussions_as_unauthorized() = runTest {
        val result = api { respond("""{"message":"Bad credentials"}""", HttpStatusCode.Unauthorized) }.discussions("tok", paperclip)

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
    }
}
