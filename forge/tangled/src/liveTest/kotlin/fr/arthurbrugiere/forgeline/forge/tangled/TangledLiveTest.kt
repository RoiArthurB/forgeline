package fr.arthurbrugiere.forgeline.forge.tangled

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueQuery
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.RepoFileType
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The Tangled client against Tangled's real index, without an account. Its calls are generated from Tangled's lexicons
 * (lexicons/, refreshed by tools/specs/fetch.py), some still under `org.tangled.temp`: if these fail, Tangled renamed or
 * reshaped one of them, or its index answers something its lexicons don't promise.
 */
class TangledLiveTest {
    private val http = tangledHttpClient(OkHttp.create())
    private val xrpc = TangledXrpc(http)
    private val identity = TangledIdentity(http, xrpc)

    /** Tangled's own code, on Tangled. */
    private val core = RepoId("tangled.org", "core", ForgeInstance.Tangled)

    private fun <T> ForgeResult<T>.value(): T = (this as? ForgeResult.Success)?.value ?: error("$this")

    @Test
    fun a_handle_resolves_to_a_did() = runBlocking<Unit> {
        assertThat(identity.did("tangled.org").value()).startsWith("did:")
    }

    @Test
    fun a_repository_its_readme_refs_and_files_read() = runBlocking<Unit> {
        val api = TangledRepoApi(xrpc, identity)

        val repo = api.repo(null, core).value()
        assertThat(repo.id).isEqualTo(core)
        assertThat(repo.stars).isGreaterThan(10)
        assertThat(api.languages(null, core).value()).isNotEmpty()
        assertThat(api.readme(null, core).value()?.markdown).isNotEmpty()
        val refs = api.refs(null, core).value()
        assertThat(refs.branches).contains(repo.defaultBranch)
        val root = api.contents(null, core, "", repo.defaultBranch).value()
        assertThat(root.map { it.path }).contains("go.mod")
        assertThat(root.first().type).isEqualTo(RepoFileType.DIR)
        assertThat(api.fileText(null, core, "go.mod", repo.defaultBranch).value()).contains("module ")
    }

    @Test
    fun trending_lists_repositories() = runBlocking<Unit> {
        val trending = TangledTrendingApi(xrpc, identity).trending(TrendingPeriod.MONTHLY).value()

        assertThat(trending).isNotEmpty()
        assertThat(trending.all { it.id.forge == ForgeInstance.Tangled }).isTrue()
    }

    @Test
    fun a_person_and_their_repositories_read() = runBlocking<Unit> {
        val users = TangledUserApi(xrpc, identity)

        assertThat(users.user(null, "tangled.org").value().login).isEqualTo("tangled.org")
        assertThat(users.repos(null, "tangled.org", mine = false, organization = false).value().map { it.id.name }).contains("core")
    }

    @Test
    fun a_profile_record_reads_and_people_have_their_pictures() = runBlocking<Unit> {
        // oppi.li keeps a Tangled profile with a description (as of 2026-11-05), and a picture on Bluesky or Tangled.
        val profile = TangledUserApi(xrpc, identity).user(null, "oppi.li").value()

        assertThat(profile.bio).isNotEmpty()
        assertThat(profile.avatarUrl).startsWith("https://")
        // Trending's owners are named with a picture's CID, or nothing: most have one.
        val trending = TangledTrendingApi(xrpc, identity).trending(TrendingPeriod.MONTHLY).value()
        assertThat(trending.count { it.ownerAvatarUrl != null }).isGreaterThan(0)
    }

    private val apis = TangledApis(http)

    @Test
    fun issues_list_and_read_with_their_comments() = runBlocking<Unit> {
        val open = apis.repos.issues(null, core, IssueQuery(open = true)).value()
        assertThat(open).isNotEmpty()
        assertThat(open.none { it.isPullRequest }).isTrue()
        val closed = apis.repos.issues(null, core, IssueQuery(open = false)).value()
        assertThat(closed.none { it.state == IssueState.OPEN }).isTrue()

        // A long-lived issue with comments: the tickets' keys are their numbers.
        val busiest = (open + closed).maxBy { it.comments ?: 0 }
        val issue = apis.issues.issue(null, IssueRef(core, busiest.number)).value()
        assertThat(issue.title).isEqualTo(busiest.title)
        assertThat(issue.author?.login).isNotEmpty()
        val timeline = apis.issues.timeline(null, IssueRef(core, busiest.number), 1).value()
        assertThat(timeline.items.filterIsInstance<TimelineItem.Comment>()).isNotEmpty()
        assertThat(apis.repos.issues(null, core, IssueQuery(open = false, text = "editorconfig")).value()).isNotEmpty()
    }

    @Test
    fun a_pull_request_reads_its_patch_files_commits_and_checks() = runBlocking<Unit> {
        val merged = apis.repos.pullRequests(null, core, IssueQuery(open = false)).value()
        assertThat(merged.all { it.isPullRequest }).isTrue()
        val ref = IssueRef(core, merged.first { it.state == IssueState.MERGED }.number, isPullRequest = true)

        val details = apis.issues.issue(null, ref).value()
        assertThat(details.pullRequest?.baseRef).isNotEmpty()
        val files = apis.pulls.files(null, ref, 1).value().files
        assertThat(files).isNotEmpty()
        assertThat(files.mapNotNull { it.patch }.first()).startsWith("@@ -")
        val commits = apis.pulls.commits(null, ref).value()
        assertThat(commits).isNotEmpty()
        // Tangled's own code runs on its spindle, at least on a patch's last commit.
        assertThat(apis.pulls.checks(null, ref).value()).isNotEmpty()
        val states = apis.pulls.commitChecks(null, ref).value()
        assertThat(states).containsKey(commits.last().sha)
    }

    @Test
    fun the_history_a_commit_and_a_blame_read_from_the_knot() = runBlocking<Unit> {
        val history = apis.pulls.history(null, core, null, null, 1).value()
        assertThat(history.commits).isNotEmpty()
        assertThat(history.nextPage).isEqualTo(2)
        assertThat(apis.pulls.history(null, core, null, null, 2).value().commits.first().sha).isNotEqualTo(history.commits.first().sha)

        val commit = apis.pulls.commit(null, core, history.commits.first().sha).value()
        assertThat(commit.files).isNotEmpty()
        assertThat(apis.pulls.blame(null, core, "master", "go.mod").value()).isNotEmpty()
    }

    @Test
    fun search_finds_tickets_repositories_and_people_and_the_feed_reads() = runBlocking<Unit> {
        assertThat(apis.search.issues(null, "editorconfig", 1).value().items).isNotEmpty()
        assertThat(apis.search.repositories(null, "tangled", 1).value().items).isNotEmpty()
        assertThat(apis.search.users(null, "oppi", 1).value().items.map { it.login }).contains("oppi.li")
        assertThat(apis.feed.receivedEvents(null, "oppi.li", 1).value().events).isNotNull()
    }
}
