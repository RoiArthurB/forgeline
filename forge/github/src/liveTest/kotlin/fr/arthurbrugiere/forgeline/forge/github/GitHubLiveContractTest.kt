package fr.arthurbrugiere.forgeline.forge.github

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
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
        val okhttp = RepoId("square", "okhttp")
        val token = pat.ifBlank { null }

        val repo = api.repo(token, okhttp)
        assertWithMessage("repo: $repo").that(repo).isInstanceOf(ForgeResult.Success::class.java)
        val branch = (repo as ForgeResult.Success).value.defaultBranch
        assertThat(branch).isNotEmpty()

        val readme = api.readme(token, okhttp)
        assertWithMessage("readme: $readme").that((readme as ForgeResult.Success).value?.markdown).isNotEmpty()

        val root = api.contents(token, okhttp, "", branch)
        assertWithMessage("contents: $root").that((root as ForgeResult.Success).value.map { it.name }).contains("README.md")

        val file = api.fileText(token, okhttp, "README.md", branch)
        assertWithMessage("file: $file").that((file as ForgeResult.Success).value).isNotEmpty()

        for (list in listOf(api.openIssues(token, okhttp), api.openPullRequests(token, okhttp), api.releases(token, okhttp), api.workflowRuns(token, okhttp))) {
            assertWithMessage("list: $list").that(list).isInstanceOf(ForgeResult.Success::class.java)
        }
    }

    @Test
    fun the_authenticated_user_endpoint_still_matches() = runBlocking {
        assumeTrue("LIVE_TEST_PAT not set", pat.isNotBlank())

        val result = GitHubAuthApi(client, clientId = "").fetchAuthenticatedUser(pat)

        assertThat(result).isInstanceOf(ForgeResult.Success::class.java)
        assertThat((result as ForgeResult.Success).value.login).isNotEmpty()
    }

    @Test
    fun the_starred_status_query_still_works() = runBlocking {
        assumeTrue("LIVE_TEST_PAT not set", pat.isNotBlank())
        val linux = RepoId("torvalds", "linux")

        val result = GitHubStarApi(client).starredStatus(pat, listOf(linux))

        assertThat(result).isInstanceOf(ForgeResult.Success::class.java)
        assertThat((result as ForgeResult.Success).value).containsKey(linux)
    }
}
