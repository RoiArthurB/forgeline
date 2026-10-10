package fr.arthurbrugiere.forgeline.forge.github

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/** If one of these fails, GitHub changed something the app relies on: fix the client, then refresh the fixtures. */
class GitHubLiveContractTest {
    private val client = gitHubHttpClient(OkHttp.create())
    private val pat = System.getenv("LIVE_TEST_PAT").orEmpty()

    @Test
    fun the_trending_page_still_parses_for_every_period() = runBlocking {
        for (period in TrendingPeriod.entries) {
            val result = GitHubTrendingApi(client).trending(period)

            assertWithMessage("$period: $result").that(result).isInstanceOf(ForgeResult.Success::class.java)
            val repos = (result as ForgeResult.Success).value
            assertWithMessage("$period repo count").that(repos.size).isAtLeast(5)
            assertWithMessage("$period names").that(repos.all { it.id.owner.isNotBlank() && it.id.name.isNotBlank() }).isTrue()
            assertWithMessage("$period stars").that(repos.all { it.stars > 0 }).isTrue()
            assertWithMessage("$period period stars").that(repos.count { it.periodStars > 0 }).isAtLeast(repos.size / 2)
            assertWithMessage("$period descriptions").that(repos.count { it.description != null }).isAtLeast(repos.size / 2)
            assertWithMessage("$period languages").that(repos.count { it.language != null && it.languageColor != null }).isAtLeast(1)
            assertWithMessage("$period built by").that(repos.count { it.builtBy.isNotEmpty() }).isAtLeast(repos.size / 2)
        }
    }

    @Test
    fun the_repository_endpoints_still_match() = runBlocking {
        val api = GitHubRepoApi(client)
        // Deliberately an old name: square/okhttp moved, so this also checks rename handling.
        // Details follow the redirect and report the canonical name, which later calls must use
        // (search rejects old names with 422).
        val okhttp = RepoId("square", "okhttp")
        val token = pat.ifBlank { null }

        val repo = api.repo(token, okhttp)
        assertWithMessage("repo: $repo").that(repo).isInstanceOf(ForgeResult.Success::class.java)
        val details = (repo as ForgeResult.Success).value
        val branch = details.defaultBranch
        assertThat(branch).isNotEmpty()
        val canonical = details.id

        val readme = api.readme(token, canonical)
        assertWithMessage("readme: $readme").that((readme as ForgeResult.Success).value?.markdown).isNotEmpty()

        val root = api.contents(token, canonical, "", branch)
        assertWithMessage("contents: $root").that((root as ForgeResult.Success).value.map { it.name }).contains("README.md")

        val file = api.fileText(token, canonical, "README.md", branch)
        assertWithMessage("file: $file").that((file as ForgeResult.Success).value).isNotEmpty()

        for (list in listOf(api.issues(token, canonical), api.pullRequests(token, canonical), api.releases(token, canonical), api.workflowRuns(token, canonical))) {
            assertWithMessage("list: $list").that(list).isInstanceOf(ForgeResult.Success::class.java)
        }
    }

    @Test
    fun the_issue_timeline_and_user_endpoints_still_match() = runBlocking {
        val token = pat.ifBlank { null }
        // cli/cli issue #1: old, closed and stable.
        val ref = fr.arthurbrugiere.forgeline.core.model.IssueRef(RepoId("cli", "cli"), 1)
        val issues = GitHubIssueApi(client)

        val issue = issues.issue(token, ref)
        assertWithMessage("issue: $issue").that(issue).isInstanceOf(ForgeResult.Success::class.java)
        val timeline = issues.timeline(token, ref, page = 1)
        assertWithMessage("timeline: $timeline").that(timeline).isInstanceOf(ForgeResult.Success::class.java)

        val users = GitHubUserApi(client)
        val octocat = users.user(token, "octocat")
        assertWithMessage("user: $octocat").that((octocat as ForgeResult.Success).value.login).isEqualTo("octocat")
        assertWithMessage("repos").that((users.repos(token, "octocat") as ForgeResult.Success).value).isNotEmpty()
        assertWithMessage("starred").that(users.starred(token, "octocat")).isInstanceOf(ForgeResult.Success::class.java)
    }

    @Test
    fun the_authenticated_user_endpoint_still_matches() = runBlocking {
        assumeTrue("LIVE_TEST_PAT not set", pat.isNotBlank())

        val result = GitHubAuthApi(client, clientId = "").fetchAuthenticatedUser(pat)

        assertThat(result).isInstanceOf(ForgeResult.Success::class.java)
        assertThat((result as ForgeResult.Success).value.login).isNotEmpty()
    }

    @Test
    fun the_notifications_endpoint_still_matches() = runBlocking {
        assumeTrue("LIVE_TEST_PAT not set", pat.isNotBlank())

        val result = GitHubNotificationsApi(client).threads(pat, ifModifiedSince = null, maxPages = 1)

        assertWithMessage("notifications: $result").that(result).isInstanceOf(ForgeResult.Success::class.java)
        assertThat((result as ForgeResult.Success).value.lastModified).isNotNull()
    }

    @Test
    fun the_starred_status_query_still_works() = runBlocking {
        assumeTrue("LIVE_TEST_PAT not set", pat.isNotBlank())
        val linux = RepoId("torvalds", "linux")

        val result = GitHubStarApi(client).starredStatus(pat, listOf(linux))

        assertThat(result).isInstanceOf(ForgeResult.Success::class.java)
        assertThat((result as ForgeResult.Success).value).containsKey(linux)
    }

    @Test
    fun the_received_events_feed_still_parses() = runBlocking {
        // Anonymous: someone else's public feed, the same shape as the signed-in one.
        val result = GitHubFeedApi(client).receivedEvents(pat.ifBlank { null }, "torvalds")

        assertWithMessage("received events: $result").that(result).isInstanceOf(ForgeResult.Success::class.java)
        val page = (result as ForgeResult.Success).value
        assertThat(page.events).isNotEmpty()
        assertThat(page.nextPage).isEqualTo(2)
    }

    @Test
    fun search_still_matches() = runBlocking {
        val api = GitHubSearchApi(client)
        val token = pat.ifBlank { null }

        val repos = api.repositories(token, "repo:torvalds/linux")
        assertWithMessage("repositories: $repos").that(repos).isInstanceOf(ForgeResult.Success::class.java)
        assertThat((repos as ForgeResult.Success).value.items.map { it.id }).contains(RepoId("torvalds", "linux"))

        // Not square/okhttp: it was renamed, and search rejects old names.
        val issues = api.issues(token, "repo:JetBrains/kotlin is:pr is:merged")
        assertWithMessage("issues: $issues").that(issues).isInstanceOf(ForgeResult.Success::class.java)
        assertThat((issues as ForgeResult.Success).value.items.first().issue.state).isEqualTo(IssueState.MERGED)

        val users = api.users(token, "user:github")
        assertWithMessage("users: $users").that(users).isInstanceOf(ForgeResult.Success::class.java)
        assertThat((users as ForgeResult.Success).value.items.single().isOrganization).isTrue()
    }

    @Test
    fun the_starred_repositories_queries_are_still_accepted() = runBlocking {
        assumeTrue("LIVE_TEST_PAT not set", pat.isNotBlank())
        // The stars are listed over REST, then asked about in aliased GraphQL batches: a query GitHub stops accepting
        // (or starts timing out on, as one large request did) must show here, not as a silently empty Feed.
        val started = System.currentTimeMillis()
        val activity = GitHubFeedApi(client).starredActivity(pat, java.time.Instant.now().minus(java.time.Duration.ofDays(30)))
        val took = System.currentTimeMillis() - started

        assertWithMessage("starred activity: $activity").that(activity).isInstanceOf(ForgeResult.Success::class.java)
        System.err.println("STARRED ${(activity as ForgeResult.Success).value.size} events in $took ms")
        activity.value.sortedByDescending { it.createdAt }.take(8).forEach { System.err.println("STARRED ${it.createdAt} ${it.repo.fullName} ${it.action} by ${it.actor.login}") }
    }

    @Test
    fun the_signed_in_person_s_work_still_reads() = runBlocking {
        assumeTrue("LIVE_TEST_PAT not set", pat.isNotBlank())
        val me = (GitHubAuthApi(client, clientId = "").fetchAuthenticatedUser(pat) as ForgeResult.Success).value

        for (kind in fr.arthurbrugiere.forgeline.core.model.WorkKind.entries) {
            val found = GitHubSearchApi(client).work(pat, me.login, kind)
            // GitHub refuses a search it can't read (422), so a success says the qualifiers still mean something.
            assertWithMessage("$kind: $found").that(found).isInstanceOf(ForgeResult.Success::class.java)
            val items = (found as ForgeResult.Success).value
            assertWithMessage("$kind open").that(items.all { it.issue.state == IssueState.OPEN }).isTrue()
            if (kind != fr.arthurbrugiere.forgeline.core.model.WorkKind.ASSIGNED) assertWithMessage("$kind pull requests").that(items.all { it.issue.isPullRequest }).isTrue()
        }
    }

    private fun <T> ForgeResult<T>.read(what: String): T {
        assertWithMessage("$what: $this").that(this).isInstanceOf(ForgeResult.Success::class.java)
        return (this as ForgeResult.Success).value
    }

    /** Anonymous, on a public repository: a handful of requests out of the sixty an hour GitHub allows without an account. */
    @Test
    fun a_pull_request_s_files_commits_and_checks_and_a_history_still_match() = runBlocking {
        val repo = RepoId("octocat", "Hello-World")
        val token = pat.ifBlank { null }
        val pulls = GitHubPullRequestApi(client)
        val open = GitHubRepoApi(client).pullRequests(token, repo, fr.arthurbrugiere.forgeline.core.model.IssueQuery()).read("pull requests")
        assumeTrue("no open pull request to read", open.isNotEmpty())
        val ref = fr.arthurbrugiere.forgeline.core.model.IssueRef(repo, open.first().number, isPullRequest = true)

        val files = pulls.files(token, ref).read("files")
        assertWithMessage("files of #${ref.number}").that(files.files).isNotEmpty()
        // A text file's change reads into numbered lines.
        val patched = files.files.firstOrNull { it.patch != null }
        if (patched != null) assertWithMessage("hunks of ${patched.path}").that(fr.arthurbrugiere.forgeline.core.model.parsePatch(patched.patch!!)).isNotEmpty()
        assertWithMessage("commits").that(pulls.commits(token, ref).read("commits")).isNotEmpty()
        pulls.checks(token, ref).read("checks")

        val history = pulls.history(token, repo, ref = null, path = null).read("history")
        assertWithMessage("history").that(history.commits).isNotEmpty()
        val commit = pulls.commit(token, repo, history.commits.first().sha).read("commit")
        assertThat(commit.commit.sha).isEqualTo(history.commits.first().sha)
        System.err.println("GITHUB #${ref.number}: ${files.files.size} files, history of ${history.commits.size}, next page ${history.nextPage}")
    }

    @Test
    fun blame_still_reads_signed_in() = runBlocking {
        assumeTrue("LIVE_TEST_PAT not set", pat.isNotBlank())
        val blame = GitHubPullRequestApi(client).blame(pat, RepoId("octocat", "Hello-World"), "master", "README").read("blame")

        assertWithMessage("blame").that(blame).isNotEmpty()
        assertThat(blame.first().startLine).isEqualTo(1)
    }
}
