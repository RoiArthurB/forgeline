package fr.arthurbrugiere.forgeline.forge.forgejo

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoId
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The Forgejo client against the real gitea.com, anonymously. Gitea was checked against its API description only
 * (v1.24 to v1.27): if these fail, Gitea answers differently from Forgejo where Forgeline assumed it doesn't.
 */
class GiteaLiveTest {
    private val http = forgejoHttpClient(OkHttp.create())
    private val gitea = ForgeInstance.Gitea
    // gitea/tea was archived on 2026-10-29 with every issue closed, and continues as gitea/cli.
    private val cli = RepoId("gitea", "cli", gitea)
    private val tea = RepoId("gitea", "tea", gitea)

    private fun <T> ForgeResult<T>.value(): T = (this as? ForgeResult.Success)?.value ?: error("$this")

    @Test
    fun gitea_com_tells_a_version_that_says_gitea_not_forgejo() = runBlocking<Unit> {
        // What the sign-in screen's probe tells Gitea from Forgejo by: x.y.z, without Forgejo's "+gitea-" suffix. Not
        // always 1.x: gitea.com answered "29.0.0+dev-160-g55186ca39b" on 2026-11-09.
        val version = http.get("https://gitea.com/api/v1/version").bodyAsText()

        assertThat(version).containsMatch(""""version":"\d+\.\d+\.\d+""")
        assertThat(version).doesNotContain("+gitea-")
    }

    @Test
    fun a_repository_its_readme_refs_files_and_conversations_read() = runBlocking<Unit> {
        val api = ForgejoRepoApi(http, gitea)

        val repo = api.repo(null, cli).value()
        assertThat(repo.id).isEqualTo(cli)
        assertThat(repo.stars).isGreaterThan(10)
        assertThat(api.readme(null, cli).value()?.markdown).isNotEmpty()
        val refs = api.refs(null, cli).value()
        assertThat(refs.branches).contains(repo.defaultBranch)
        assertThat(api.contents(null, cli, "", repo.defaultBranch).value().map { it.path }).contains("go.mod")
        assertThat(api.fileText(null, cli, "go.mod", repo.defaultBranch).value()).contains("module ")
        assertThat(api.issues(null, cli).value()).isNotEmpty()
        assertThat(api.pullRequests(null, cli).value().all { it.isPullRequest }).isTrue()
        assertThat(api.releases(null, cli).value()).isNotEmpty()
    }

    @Test
    fun a_person_and_their_repositories_read() = runBlocking<Unit> {
        val users = ForgejoUserApi(http, gitea)

        assertThat(users.contributions(null, "lunny").value().total).isGreaterThan(0)
    }

    @Test
    fun runs_list_with_their_jobs_and_finished_runs_can_be_rerun_but_not_cancelled() = runBlocking<Unit> {
        val actions = ForgejoActionsApi(http, gitea)

        val runs = actions.runs(null, cli).value()
        assertThat(runs).isNotEmpty()
        assertThat(actions.jobs(null, cli, runs.first().id).value()).isNotEmpty()
        assertThat(actions.supportsRerun).isTrue()
        assertThat(actions.supportsCancel).isFalse()
    }

    /** gitea/tea#1155 was pushed to, set to merge itself once its checks passed, merged and its branch deleted (checked 2026-11-05). */
    @Test
    fun a_pull_request_s_pushes_and_auto_merge_still_read() = runBlocking<Unit> {
        val items = ForgejoIssueApi(http, gitea).timeline(null, fr.arthurbrugiere.forgeline.core.model.IssueRef(tea, 1155), 1).value().items
        val events = items.filterIsInstance<fr.arthurbrugiere.forgeline.core.model.TimelineItem.Event>().map { it.event }

        assertThat(events).containsAtLeast(
            fr.arthurbrugiere.forgeline.core.model.ConversationEvent.PUSHED,
            fr.arthurbrugiere.forgeline.core.model.ConversationEvent.AUTO_MERGE_ENABLED,
            fr.arthurbrugiere.forgeline.core.model.ConversationEvent.BRANCH_DELETED,
        )
    }
}
