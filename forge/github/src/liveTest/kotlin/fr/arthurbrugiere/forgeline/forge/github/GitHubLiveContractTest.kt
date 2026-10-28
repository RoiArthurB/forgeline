package fr.arthurbrugiere.forgeline.forge.github

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import fr.arthurbrugiere.forgeline.core.forge.ActionsLogParser
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.JobLogLocator
import fr.arthurbrugiere.forgeline.core.forge.JobLogSteps
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.LogFormat
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RunConclusion
import fr.arthurbrugiere.forgeline.core.model.RunStatus
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
    fun issue_forms_still_come_beside_markdown_templates() = runBlocking {
        assumeTrue("LIVE_TEST_PAT not set", pat.isNotBlank())

        // facebook/react keeps one Markdown template and its bug reports as forms.
        val result = GitHubIssueApi(client).issueTemplates(pat, RepoId("facebook", "react"))

        val templates = (result as? ForgeResult.Success)?.value
        assertWithMessage("templates: $result").that(templates).isNotNull()
        val forms = templates!!.filter { it.key.endsWith(".yml") || it.key.endsWith(".yaml") }
        assertWithMessage("forms: ${templates.map { it.key }}").that(forms).isNotEmpty()
        assertWithMessage("form bodies").that(forms.all { it.body.orEmpty().startsWith("### ") }).isTrue()
        assertWithMessage("markdown templates").that(templates.any { it.key.endsWith(".md") }).isTrue()
        // Its config.yml sends support questions elsewhere.
        val chooser = (GitHubIssueApi(client).issueChooser(pat, RepoId("facebook", "react")) as? ForgeResult.Success)?.value
        assertWithMessage("chooser links: $chooser").that(chooser?.links).isNotEmpty()
    }

    /**
     * RoiArthurB/GAMA-GC-Benchmark writes its report to the run's summary, which no API gives, and keeps it in a "report"
     * artifact: the latest one kept still lists, downloads and reads.
     */
    @Test
    fun a_run_s_report_reads_from_its_artifact() = runBlocking {
        assumeTrue("LIVE_TEST_PAT not set", pat.isNotBlank())
        val benchmark = RepoId("RoiArthurB", "GAMA-GC-Benchmark")
        val actions = GitHubActionsApi(client)
        val runs = (GitHubRepoApi(client).workflowRuns(pat, benchmark) as ForgeResult.Success).value.filter { it.conclusion == RunConclusion.SUCCESS }
        val report = runs.firstNotNullOfOrNull { run -> (actions.artifacts(pat, benchmark, run.id) as? ForgeResult.Success)?.value?.firstOrNull { it.name == "report" && !it.expired } }
        assumeTrue("no report artifact kept", report != null)

        val documents = actions.artifactDocuments(pat, benchmark, report!!)

        assertWithMessage("documents: $documents").that(documents).isInstanceOf(ForgeResult.Success::class.java)
        val read = (documents as ForgeResult.Success).value
        assertWithMessage("paths").that(read.map { it.path }).contains("report/report.md")
        assertWithMessage("report").that(read.first { it.path == "report/report.md" }.markdown).startsWith("# GAMA garbage-collector benchmark")
    }

    /** Read only: gama-platform/gama#1265's review threads still read, the reactions on their remarks with them. */
    @Test
    fun a_pull_request_s_review_threads_still_read() = runBlocking {
        assumeTrue("LIVE_TEST_PAT not set", pat.isNotBlank())
        val pull = fr.arthurbrugiere.forgeline.core.model.IssueRef(RepoId("gama-platform", "gama"), 1265, isPullRequest = true)

        val threads = GitHubPullRequestApi(client).threads(pat, pull)

        assertWithMessage("threads: $threads").that(threads).isInstanceOf(ForgeResult.Success::class.java)
        val read = (threads as ForgeResult.Success).value
        assertWithMessage("threads").that(read.size).isAtLeast(5)
        assertWithMessage("remarks").that(read.flatMap { it.comments }.all { it.id > 0 && it.body.isNotBlank() }).isTrue()
        // Which of its files the reader viewed: none asked to be, but the question still reads.
        val viewed = GitHubPullRequestApi(client).viewedFiles(pat, pull)
        assertWithMessage("viewed: $viewed").that(viewed).isInstanceOf(ForgeResult.Success::class.java)
    }

    /**
     * gama-platform/gama#765's comments say, through GraphQL, whether they are hidden and whether the reader may hide
     * them: the owner's account is a member of gama-platform, which may (checked 2026-10-25).
     */
    @Test
    fun a_conversation_s_comments_still_say_whether_they_can_be_hidden() = runBlocking {
        assumeTrue("LIVE_TEST_PAT not set", pat.isNotBlank())
        val page = GitHubIssueApi(client).timeline(pat, fr.arthurbrugiere.forgeline.core.model.IssueRef(RepoId("gama-platform", "gama"), 765), 1)

        assertWithMessage("timeline: $page").that(page).isInstanceOf(ForgeResult.Success::class.java)
        val comments = (page as ForgeResult.Success).value.items.filterIsInstance<fr.arthurbrugiere.forgeline.core.model.TimelineItem.Comment>()
        assertWithMessage("comments").that(comments).isNotEmpty()
        assertWithMessage("comment nodes").that(comments.all { it.nodeId != null }).isTrue()
        assertWithMessage("may hide").that(comments.all { it.canHide }).isTrue()
    }

    /** Read only: gama-platform/gama names Task, Bug and Feature, and #1239 is a Bug (2026-10-25). */
    @Test
    fun issue_types_still_read() = runBlocking {
        assumeTrue("LIVE_TEST_PAT not set", pat.isNotBlank())
        val api = GitHubIssueApi(client)
        val gama = RepoId("gama-platform", "gama")

        val types = api.issueTypes(pat, gama)
        val issue = api.issue(pat, fr.arthurbrugiere.forgeline.core.model.IssueRef(gama, 1239))

        assertWithMessage("types: $types").that((types as ForgeResult.Success).value.map { it.name }).containsAtLeast("Task", "Bug", "Feature")
        assertWithMessage("type of #1239").that((issue as ForgeResult.Success).value.type?.name).isEqualTo("Bug")
    }

    /** Read only: cli/cli#10093 is split into sub-issues, #14512 among them, which names it as its parent (2026-10-25). */
    @Test
    fun sub_issues_and_their_parent_still_read() = runBlocking {
        assumeTrue("LIVE_TEST_PAT not set", pat.isNotBlank())
        val api = GitHubIssueApi(client)

        val epic = api.subIssues(pat, fr.arthurbrugiere.forgeline.core.model.IssueRef(RepoId("cli", "cli"), 10093))
        val child = api.subIssues(pat, fr.arthurbrugiere.forgeline.core.model.IssueRef(RepoId("cli", "cli"), 14512))

        assertWithMessage("epic: $epic").that((epic as ForgeResult.Success).value.children.map { it.ref.number }).contains(14512)
        assertWithMessage("child: $child").that((child as ForgeResult.Success).value.parent?.ref?.number).isEqualTo(10093)
    }

    /** Read only: gama-platform/gama#999 names #1020 as the pull request that would close it (2026-10-25). */
    @Test
    fun the_pull_requests_that_close_an_issue_are_still_told() = runBlocking {
        assumeTrue("LIVE_TEST_PAT not set", pat.isNotBlank())

        val pulls = GitHubIssueApi(client).closingPulls(pat, fr.arthurbrugiere.forgeline.core.model.IssueRef(RepoId("gama-platform", "gama"), 999))

        assertWithMessage("closing: $pulls").that(pulls).isInstanceOf(ForgeResult.Success::class.java)
        assertWithMessage("#1020").that((pulls as ForgeResult.Success).value.map { it.ref.number }).contains(1020)
    }

    /** gama-platform/gama#1118 was closed by a commit: the timeline still names it, and the commit still reads. */
    @Test
    fun an_issue_closed_by_a_commit_still_says_which() = runBlocking {
        assumeTrue("LIVE_TEST_PAT not set", pat.isNotBlank())
        val issue = fr.arthurbrugiere.forgeline.core.model.IssueRef(RepoId("gama-platform", "gama"), 1118)

        val page = GitHubIssueApi(client).timeline(pat, issue, 1)

        assertWithMessage("timeline: $page").that(page).isInstanceOf(ForgeResult.Success::class.java)
        val closed = (page as ForgeResult.Success).value.items.filterIsInstance<fr.arthurbrugiere.forgeline.core.model.TimelineItem.StateChanged>().mapNotNull { it.commit }
        assertWithMessage("closing commit").that(closed.map { it.sha.take(7) }).contains("c2a0103")
        assertWithMessage("its message").that(closed.first { it.sha.startsWith("c2a0103") }.message).startsWith("Detect duplicate default facet definitions")
    }

    /** Read only: what commenting needs (the discussion's id) and what the reader may pick as its answer still come. */
    @Test
    fun a_discussion_still_says_what_it_is_named_by_and_what_the_reader_may_do() = runBlocking {
        assumeTrue("LIVE_TEST_PAT not set", pat.isNotBlank())
        val api = GitHubRepoApi(client)
        val next = RepoId("vercel", "next.js")
        val listed = (api.discussions(pat, next) as? ForgeResult.Success)?.value?.items?.firstOrNull { it.comments > 0 }
        assertWithMessage("a discussion with comments").that(listed).isNotNull()

        val read = api.discussion(pat, next, listed!!.number)

        assertWithMessage("discussion: $read").that(read).isInstanceOf(ForgeResult.Success::class.java)
        val discussion = (read as ForgeResult.Success).value
        assertWithMessage("id").that(discussion.id).isNotEmpty()
        // Someone else's discussion: nothing of it is the reader's to pick.
        assertWithMessage("answers to pick").that(discussion.comments.none { it.canMarkAnswer || it.canUnmarkAnswer }).isTrue()
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
    fun whether_a_repository_is_starred_and_watched_is_still_one_query() = runBlocking {
        assumeTrue("LIVE_TEST_PAT not set", pat.isNotBlank())

        val viewer = GitHubStarApi(client).viewer(pat, RepoId("torvalds", "linux"))

        // Both known: a field renamed, or the enum changed, would leave one unknown.
        assertThat(viewer.starred).isNotNull()
        assertThat(viewer.watching).isNotNull()
    }

    @Test
    fun the_received_events_feed_still_parses() = runBlocking {
        // Anonymous: someone else's public feed, the same shape as the signed-in one.
        val result = GitHubFeedApi(client).receivedEvents(pat.ifBlank { null }, "torvalds")

        assertWithMessage("received events: $result").that(result).isInstanceOf(ForgeResult.Success::class.java)
        val page = (result as ForgeResult.Success).value
        assertThat(page.events).isNotEmpty()
        // torvalds follows nobody, so his feed is only his own repositories' and can fit one page of 100: whether a
        // next page exists is his activity, not the contract. Reading the Link header is GitHubFeedApiTest's job.
        assertThat(page.nextPage).isAnyOf(null, 2)
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
    fun first_issues_of_several_repositories_come_from_one_search() = runBlocking {
        val repos = listOf(RepoId("home-assistant", "core"), RepoId("godotengine", "godot"), RepoId("rust-lang", "rust"))
        val found = GitHubSearchApi(client).firstIssues(pat.ifBlank { null }, repos)
        assertWithMessage("first issues: $found").that(found).isInstanceOf(ForgeResult.Success::class.java)
        val issues = (found as ForgeResult.Success).value
        assertThat(issues).isNotEmpty()
        assertThat(issues.map { it.repo }.toSet()).isNotEmpty()
        assertThat(repos).containsAtLeastElementsIn(issues.map { it.repo }.toSet())
        assertThat(issues.all { result -> result.issue.labels.any { fr.arthurbrugiere.forgeline.core.model.FirstIssueLabels.matches(it.name) } }).isTrue()
    }

    @Test
    fun a_topic_still_lists_its_repositories_most_starred_first() = runBlocking {
        val topic = GitHubSearchApi(client).topic(pat.ifBlank { null }, "kotlin")
        assertWithMessage("topic: $topic").that(topic).isInstanceOf(ForgeResult.Success::class.java)
        val stars = (topic as ForgeResult.Success).value.items.map { it.stars }
        assertThat(stars.size).isAtLeast(10)
        assertThat(stars).isInOrder(Comparator.reverseOrder<Int>())
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
        // Anyone can open a pull request here, and some change nothing (#11578, 2026-10-18): the newest that changes a
        // file is read. Five empty in a row says the files no longer parse.
        val (ref, files) = open.take(5).firstNotNullOfOrNull { pull ->
            val ref = fr.arthurbrugiere.forgeline.core.model.IssueRef(repo, pull.number, isPullRequest = true)
            pulls.files(token, ref).read("files of #${pull.number}").takeIf { it.files.isNotEmpty() }?.let { ref to it }
        } ?: throw AssertionError("none of #${open.take(5).map { it.number }} changes a file")
        // A text file's change reads into numbered lines.
        val patched = files.files.firstOrNull { it.patch != null }
        if (patched != null) assertWithMessage("hunks of ${patched.path}").that(fr.arthurbrugiere.forgeline.core.model.parsePatch(patched.patch!!)).isNotEmpty()
        assertWithMessage("commits").that(pulls.commits(token, ref).read("commits")).isNotEmpty()
        // A check keeps its report under its id, when one ran here.
        pulls.checks(token, ref).read("checks").firstNotNullOfOrNull { it.checkId }?.let { id ->
            assertWithMessage("report of check $id").that(pulls.checkReport(token, repo, id).read("check report").name).isNotEmpty()
        }

        val history = pulls.history(token, repo, ref = null, path = null).read("history")
        assertWithMessage("history").that(history.commits).isNotEmpty()
        val commit = pulls.commit(token, repo, history.commits.first().sha).read("commit")
        assertThat(commit.commit.sha).isEqualTo(history.commits.first().sha)
        System.err.println("GITHUB #${ref.number}: ${files.files.size} files, history of ${history.commits.size}, next page ${history.nextPage}")
    }

    /** Read only: nothing is changed on GitHub from here. Someone who doesn't administer the repository isn't told how it merges. */
    @Test
    fun a_repository_s_settings_still_read_signed_in() = runBlocking {
        assumeTrue("LIVE_TEST_PAT not set", pat.isNotBlank())
        val settings = GitHubRepoSettingsApi(client).settings(pat, RepoId("octocat", "Hello-World")).read("settings")

        assertThat(settings.defaultBranch).isEqualTo("master")
        assertWithMessage("switches: ${settings.switches}").that(settings.switches.keys)
            .containsAtLeast(fr.arthurbrugiere.forgeline.core.model.RepoSwitch.ISSUES, fr.arthurbrugiere.forgeline.core.model.RepoSwitch.WIKI)
        System.err.println("GITHUB settings: $settings")
        // Someone else's repository: GitHub says its website is none of the reader's business, or that there is none.
        val pages = GitHubRepoSettingsApi(client).pages(pat, RepoId("octocat", "Hello-World"))
        System.err.println("GITHUB pages: $pages")
        assertWithMessage("pages: $pages").that(pages).isNotEqualTo(ForgeResult.Failure(fr.arthurbrugiere.forgeline.core.forge.ForgeError.Unreadable))
    }

    @Test
    fun blame_still_reads_signed_in() = runBlocking {
        assumeTrue("LIVE_TEST_PAT not set", pat.isNotBlank())
        val blame = GitHubPullRequestApi(client).blame(pat, RepoId("octocat", "Hello-World"), "master", "README").read("blame")

        assertWithMessage("blame").that(blame).isNotEmpty()
        assertThat(blame.first().startLine).isEqualTo(1)
    }

    @Test
    fun a_job_log_is_read_in_parts_and_its_steps_found_from_their_times() = runBlocking {
        assumeTrue("LIVE_TEST_PAT not set", pat.isNotBlank())
        // This repository's own CI: public, and recent enough that its logs are kept.
        val forgeline = RepoId("RoiArthurB", "forgeline")
        val run = (GitHubRepoApi(client).workflowRuns(pat, forgeline) as ForgeResult.Success).value
            .first { it.status == RunStatus.COMPLETED && it.workflowName == "CI" }
        val actions = GitHubActionsApi(client)
        val job = (actions.jobs(pat, forgeline, run.id) as ForgeResult.Success).value.first { it.steps.size > 3 }

        val opened = actions.openJobLog(pat, forgeline, job.id)
        assertWithMessage("open: $opened").that(opened).isInstanceOf(ForgeResult.Success::class.java)
        val reader = (opened as ForgeResult.Success).value
        assertThat(reader.size).isGreaterThan(1_000L)
        // Read from storage with the range kept across GitHub's redirect.
        val head = (reader.read(0L..99L) as ForgeResult.Success).value
        assertThat(head.bytes.size).isEqualTo(100)

        val ran = job.steps.sortedBy { it.number }.filter { it.startedAt != null && it.conclusion != RunConclusion.SKIPPED }
        val starts = (JobLogLocator(reader, LogFormat.GITHUB).locate(ran.drop(1).map { it.startedAt!! }) as ForgeResult.Success).value
        // Each step found begins on a line that opens one.
        for (start in starts) {
            val line = String((reader.read(start until start + 200) as ForgeResult.Success).value.bytes).substringBefore('\n')
            assertWithMessage("step at $start: $line").that(JobLogSteps.opening(ActionsLogParser.contentOf(line), LogFormat.GITHUB)).isNotNull()
        }
    }

    /** A GitHub wiki page written in Markdown still reads as a file of the wiki's repository, with no token. */
    @Test
    fun a_wiki_page_still_reads_from_the_wiki_s_repository() = runBlocking {
        val page = GitHubRepoApi(client).wikiPage(null, RepoId("gama-platform", "gama"), "Home")

        assertWithMessage("page: $page").that(page).isInstanceOf(ForgeResult.Success::class.java)
        assertThat((page as ForgeResult.Success).value.body).contains("GAMA")
    }
}
