package fr.arthurbrugiere.forgeline.forge.forgejo

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Forgeline's own Forgejo client against the real Codeberg, anonymously: if these fail, parsing drifted from the API. */
class ForgejoClientLiveTest {
    private val http = forgejoHttpClient(OkHttp.create())
    private val forgejo = RepoId("forgejo", "forgejo", ForgeInstance.Codeberg)

    private fun <T> ForgeResult<T>.value(): T = (this as? ForgeResult.Success)?.value ?: error("$this")

    @Test
    fun a_topic_lists_codeberg_s_repositories_tagged_with_it_most_starred_first() = runBlocking<Unit> {
        val page = ForgejoSearchApi(http, ForgeInstance.Codeberg).topic(null, "selfhosted").value()

        assertThat(page.items.size).isAtLeast(2)
        assertThat(page.items.map { it.stars }).isInOrder(Comparator.reverseOrder<Int>())
        assertThat(page.nextPage).isEqualTo(2)
    }

    @Test
    fun a_repository_its_readme_and_refs_read() = runBlocking<Unit> {
        val api = ForgejoRepoApi(http, ForgeInstance.Codeberg)

        val repo = api.repo(null, forgejo).value()
        assertThat(repo.id).isEqualTo(forgejo)
        assertThat(repo.stars).isGreaterThan(1000)
        assertThat(api.readme(null, forgejo).value()?.markdown).isNotEmpty()
        val refs = api.refs(null, forgejo).value()
        assertThat(refs.branches).contains(repo.defaultBranch)
        // Folders, at the root and below, on a branch with a slash in its name.
        assertThat(api.contents(null, forgejo, "", repo.defaultBranch).value().map { it.path }).containsAtLeast("services", "go.mod")
        val branch = refs.branches.first { '/' in it }
        val services = api.contents(null, forgejo, "services", branch).value()
        assertWithMessage("services on $branch").that(services.filter { it.type == fr.arthurbrugiere.forgeline.core.model.RepoFileType.DIR }.map { it.path }).contains("services/actions")
        assertThat(api.issues(null, forgejo).value()).isNotEmpty()
        assertThat(api.pullRequests(null, forgejo).value().all { it.isPullRequest }).isTrue()
    }

    @Test
    fun a_symlink_and_a_submodule_read_as_what_they_are() = runBlocking<Unit> {
        // Real ones, found 2026-10-25: localsend's CHANGELOG.md is a link, fuzzel keeps wlr-protocols as a submodule.
        val api = ForgejoRepoApi(http, ForgeInstance.Codeberg)
        val localsend = RepoId("localsend", "localsend", ForgeInstance.Codeberg)
        val fuzzel = RepoId("dnkl", "fuzzel", ForgeInstance.Codeberg)
        val type = { files: List<fr.arthurbrugiere.forgeline.core.model.RepoFile>, path: String -> files.single { it.path == path }.type }

        val root = api.contents(null, localsend, "", api.repo(null, localsend).value().defaultBranch).value()
        assertThat(type(root, "CHANGELOG.md")).isEqualTo(fr.arthurbrugiere.forgeline.core.model.RepoFileType.SYMLINK)
        val external = api.contents(null, fuzzel, "external", api.repo(null, fuzzel).value().defaultBranch).value()
        assertThat(type(external, "external/wlr-protocols")).isEqualTo(fr.arthurbrugiere.forgeline.core.model.RepoFileType.SUBMODULE)
    }

    @Test
    fun an_issue_closed_by_a_commit_says_which() = runBlocking<Unit> {
        // dnkl/foot#2467 was closed by f3226bc on 2026-09-18.
        val issue = IssueRef(RepoId("dnkl", "foot", ForgeInstance.Codeberg), 2467)

        val items = ForgejoIssueApi(http, ForgeInstance.Codeberg).timeline(null, issue, page = 1).value().items

        val commit = items.filterIsInstance<TimelineItem.StateChanged>().firstNotNullOf { it.commit }
        assertThat(commit.sha).startsWith("f3226bc")
        assertThat(commit.message).isEqualTo("ime: ignore enter events with a NULL surface")
        assertThat(commit.webUrl).isEqualTo("https://codeberg.org/dnkl/foot/commit/${commit.sha}")
    }

    @Test
    fun a_repository_nothing_was_pushed_to_says_so() = runBlocking<Unit> {
        // A real one, found 2026-10-25; its tree answers "sha not found", as a missing branch's does.
        val empty = RepoId("Scronglus", "empty", ForgeInstance.Codeberg)

        assertThat(ForgejoRepoApi(http, ForgeInstance.Codeberg).repo(null, empty).value().isEmpty).isTrue()
        assertThat(ForgejoRepoApi(http, ForgeInstance.Codeberg).repo(null, forgejo).value().isEmpty).isFalse()
    }

    @Test
    fun a_merged_pull_request_and_its_conversation_read() = runBlocking<Unit> {
        val api = ForgejoIssueApi(http, ForgeInstance.Codeberg)
        val pull = IssueRef(forgejo, 14597)

        assertThat(api.issue(null, pull).value().pullRequest?.isMerged).isTrue()
        assertThat(api.timeline(null, pull, page = 1).value().items).isNotEmpty()
    }

    @Test
    fun a_profile_reads() = runBlocking<Unit> {
        val org = ForgejoUserApi(http, ForgeInstance.Codeberg).user(null, "forgejo").value()

        assertThat(org.isOrganization).isTrue()
        assertThat(org.publicRepos).isGreaterThan(0)
    }

    @Test
    fun public_actions_read_signed_out_and_the_run_list_stays_small() = runBlocking<Unit> {
        // Without `page`, Codeberg once answered every run of a repository (56 MB for 4,235 runs).
        val website = RepoId("forgejo", "website", ForgeInstance.Codeberg)
        val api = ForgejoActionsApi(http, ForgeInstance.Codeberg)

        val runs = api.runs(null, website).value()
        assertThat(runs.size).isAtMost(20)
        val finished = runs.first { it.conclusion != null }
        assertThat(finished.webUrl).endsWith("/actions/runs/${finished.runNumber}")
        val job = api.jobs(null, website, finished.id).value().first()
        assertThat(api.job(null, website, finished.id, job.id).value().name).isEqualTo(job.name)
        val log = java.io.ByteArrayOutputStream().also { api.downloadJobLog(null, website, job.id, it).value() }.toByteArray()
        val steps = fr.arthurbrugiere.forgeline.core.forge.JobLogSteps.index(log.inputStream(), api.logFormat)
        assertWithMessage("steps of job ${job.id}").that(steps.count { it.title != null }).isAtLeast(1)
        // Codeberg serves a log in parts (206 with its Content-Range, seen 2026-10-25): a part is the log's own bytes.
        val opened = api.openJobLog(null, website, job.id).value()
        assertThat(opened.size).isEqualTo(log.size.toLong())
        assertThat(opened.read(0L..99L).value().bytes).isEqualTo(log.copyOfRange(0, 100))
        assertThat(api.workflows(null, website).value()).isNotEmpty()
    }

    @Test
    fun a_pull_request_s_files_commits_and_checks_and_a_history_read() = runBlocking<Unit> {
        val pulls = ForgejoPullRequestApi(http, ForgeInstance.Codeberg)
        val open = ForgejoRepoApi(http, ForgeInstance.Codeberg).pullRequests(null, forgejo, fr.arthurbrugiere.forgeline.core.model.IssueQuery()).value()
        val ref = fr.arthurbrugiere.forgeline.core.model.IssueRef(forgejo, open.first().number, isPullRequest = true)

        // The whole diff, read file by file: Forgejo's own list of files has no changes in it.
        val files = pulls.files(null, ref).value()
        assertWithMessage("files of #${ref.number}").that(files.files).isNotEmpty()
        val patched = files.files.firstOrNull { it.patch != null }
        if (patched != null) {
            assertWithMessage("hunks of ${patched.path}").that(fr.arthurbrugiere.forgeline.core.model.parsePatch(patched.patch!!)).isNotEmpty()
            assertWithMessage("counts of ${patched.path}").that(patched.additions + patched.deletions).isGreaterThan(0)
        }
        assertWithMessage("commits").that(pulls.commits(null, ref).value()).isNotEmpty()
        val checks = pulls.checks(null, ref).value()

        val history = pulls.history(null, forgejo, ref = null, path = "go.mod").value()
        assertWithMessage("history of go.mod").that(history.commits).isNotEmpty()
        assertWithMessage("a long history has a next page").that(history.nextPage).isEqualTo(2)
        val commit = pulls.commit(null, forgejo, history.commits.first().sha).value()
        // A commit in go.mod's history changed go.mod.
        assertWithMessage("files of ${commit.commit.shortSha}").that(commit.files.map { it.path }).contains("go.mod")
        System.err.println("CODEBERG #${ref.number}: ${files.files.size} files, ${checks.size} checks")
    }

    /** dnkl/foot's wiki: its pages listed without the sidebar and footer, its first page read. */
    @Test
    fun a_wiki_lists_its_pages_and_reads_one() = runBlocking<Unit> {
        val foot = RepoId("dnkl", "foot", ForgeInstance.Codeberg)
        val api = ForgejoRepoApi(http, ForgeInstance.Codeberg)

        val pages = api.wikiPages(null, foot)
        assertWithMessage("pages: $pages").that(pages).isInstanceOf(fr.arthurbrugiere.forgeline.core.forge.ForgeResult.Success::class.java)
        val listed = (pages as fr.arthurbrugiere.forgeline.core.forge.ForgeResult.Success).value
        assertWithMessage("Home listed").that(listed.map { it.key }).contains("Home")
        assertWithMessage("no sidebar").that(listed.none { it.title.startsWith("_") }).isTrue()

        val home = api.wikiPage(null, foot, "Home")
        // Not in the message: a page holds `%s`, which the message would read as placeholders.
        assertWithMessage("home: ${(home as? fr.arthurbrugiere.forgeline.core.forge.ForgeResult.Failure)?.error}").that(home).isInstanceOf(fr.arthurbrugiere.forgeline.core.forge.ForgeResult.Success::class.java)
        assertWithMessage("home text").that((home as fr.arthurbrugiere.forgeline.core.forge.ForgeResult.Success).value.body).contains("Troubleshooting")
    }
}
