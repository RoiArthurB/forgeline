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
}
