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
    private val tea = RepoId("gitea", "tea", gitea)

    private fun <T> ForgeResult<T>.value(): T = (this as? ForgeResult.Success)?.value ?: error("$this")

    @Test
    fun gitea_com_tells_a_version_that_says_gitea_not_forgejo() = runBlocking<Unit> {
        // What the sign-in screen's probe tells Gitea from Forgejo by: 1.x, without Forgejo's "+gitea-" suffix.
        val version = http.get("https://gitea.com/api/v1/version").bodyAsText()

        assertThat(version).contains("\"version\":\"1.")
        assertThat(version).doesNotContain("+gitea-")
    }

    @Test
    fun a_repository_its_readme_refs_files_and_conversations_read() = runBlocking<Unit> {
        val api = ForgejoRepoApi(http, gitea)

        val repo = api.repo(null, tea).value()
        assertThat(repo.id).isEqualTo(tea)
        assertThat(repo.stars).isGreaterThan(10)
        assertThat(api.readme(null, tea).value()?.markdown).isNotEmpty()
        val refs = api.refs(null, tea).value()
        assertThat(refs.branches).contains(repo.defaultBranch)
        assertThat(api.contents(null, tea, "", repo.defaultBranch).value().map { it.path }).contains("go.mod")
        assertThat(api.fileText(null, tea, "go.mod", repo.defaultBranch).value()).contains("module ")
        assertThat(api.issues(null, tea).value()).isNotEmpty()
        assertThat(api.pullRequests(null, tea).value().all { it.isPullRequest }).isTrue()
        assertThat(api.releases(null, tea).value()).isNotEmpty()
    }

    @Test
    fun a_person_and_their_repositories_read() = runBlocking<Unit> {
        val users = ForgejoUserApi(http, gitea)

        assertThat(users.contributions(null, "lunny").value().total).isGreaterThan(0)
    }

    @Test
    fun runs_list_with_their_jobs_and_finished_runs_can_be_rerun_but_not_cancelled() = runBlocking<Unit> {
        val actions = ForgejoActionsApi(http, gitea)

        val runs = actions.runs(null, tea).value()
        assertThat(runs).isNotEmpty()
        assertThat(actions.jobs(null, tea, runs.first().id).value()).isNotEmpty()
        assertThat(actions.supportsRerun).isTrue()
        assertThat(actions.supportsCancel).isFalse()
    }
}
