package fr.arthurbrugiere.forgeline.forge.forgejo

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Forgeline's own Forgejo client against the real Codeberg, anonymously: if these fail, parsing drifted from the API. */
class ForgejoClientLiveTest {
    private val http = forgejoHttpClient(OkHttp.create())
    private val forgejo = RepoId("forgejo", "forgejo", ForgeInstance.Codeberg)

    private fun <T> ForgeResult<T>.value(): T = (this as? ForgeResult.Success)?.value ?: error("$this")

    @Test
    fun a_repository_its_readme_and_refs_read() = runBlocking<Unit> {
        val api = ForgejoRepoApi(http, ForgeInstance.Codeberg)

        val repo = api.repo(null, forgejo).value()
        assertThat(repo.id).isEqualTo(forgejo)
        assertThat(repo.stars).isGreaterThan(1000)
        assertThat(api.readme(null, forgejo).value()?.markdown).isNotEmpty()
        assertThat(api.refs(null, forgejo).value().branches).contains(repo.defaultBranch)
        assertThat(api.openIssues(null, forgejo).value()).isNotEmpty()
        assertThat(api.openPullRequests(null, forgejo).value().all { it.isPullRequest }).isTrue()
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
        assertThat(api.jobLog(null, website, job.id).value().entries).isNotEmpty()
        assertThat(api.workflows(null, website).value()).isNotEmpty()
    }
}
