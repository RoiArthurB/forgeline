package fr.arthurbrugiere.forgeline.core.data.search

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import fr.arthurbrugiere.forgeline.core.testing.FakeSearchApi
import fr.arthurbrugiere.forgeline.core.testing.repoSummary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SearchRepositoryTest {
    private val api = FakeSearchApi(pageSize = 2)
    private val codebergApi = FakeSearchApi(pageSize = 2)
    private val clients = FakeForgeClients(search = api).also { it.put(ForgeInstance.Codeberg, FakeForgeClients(search = codebergApi)) }
    private val accounts = FakeAccountRepository()
    private val repository = SearchRepository(clients, accounts)

    private suspend fun signInToCodeberg() = accounts.signIn(ForgeInstance.Codeberg, ForgeUser("me", null, null), "t-codeberg")

    @Test
    fun signed_out_searches_the_three_public_forges_anonymously() = runTest {
        val gitlabApi = FakeSearchApi(pageSize = 2).apply { repositories = listOf(repoSummary("c/gl1", forge = ForgeInstance.GitLab)) }
        clients.put(ForgeInstance.GitLab, FakeForgeClients(search = gitlabApi))
        api.repositories = listOf(repoSummary("a/gh1"))
        codebergApi.repositories = listOf(repoSummary("ziglang/zig", forge = ForgeInstance.Codeberg))

        val page = (repository.repositories("forge") as ForgeResult.Success).value

        assertThat(api.tokens).containsExactly(null)
        assertThat(codebergApi.tokens).containsExactly(null)
        assertThat(gitlabApi.tokens).containsExactly(null)
        assertThat(page.forges).containsExactly(ForgeInstance.GitHub, ForgeInstance.Codeberg, ForgeInstance.GitLab).inOrder()
        assertThat(page.items.map { it.id.fullName }).containsExactly("a/gh1", "ziglang/zig", "c/gl1").inOrder()
    }

    @Test
    fun signed_in_to_github_alone_still_searches_the_other_public_forges() = runTest {
        // Regression: signing in to GitHub left Codeberg and gitlab.com out of Search.
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "t-me")

        assertThat(repository.forges.first()).containsExactly(ForgeInstance.GitHub, ForgeInstance.Codeberg, ForgeInstance.GitLab).inOrder()
    }

    @Test
    fun signed_in_searches_the_public_forges_then_each_other_server() = runTest {
        val own = ForgeInstance(fr.arthurbrugiere.forgeline.core.model.ForgeType.FORGEJO, "git.example.org")
        accounts.signIn(own, ForgeUser("me", null, null), "t-own")

        assertThat(repository.forges.first()).containsExactly(ForgeInstance.GitHub, ForgeInstance.Codeberg, ForgeInstance.GitLab, own).inOrder()
    }

    @Test
    fun signed_in_searches_with_the_token() = runTest {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "t-me")

        repository.issues("bug")
        repository.users("octo")

        assertThat(api.tokens).containsExactly("t-me", "t-me")
        assertThat(api.calls).containsExactly("issues:bug@1", "users:octo@1").inOrder()
    }

    @Test
    fun a_codeberg_account_adds_codebergs_results_interleaved_by_rank() = runTest {
        signInToCodeberg()
        val gh = listOf(repoSummary("a/gh1"), repoSummary("a/gh2"), repoSummary("a/gh3"))
        val cb = listOf(repoSummary("b/cb1", forge = ForgeInstance.Codeberg))
        api.repositories = gh
        codebergApi.repositories = cb

        val first = (repository.repositories("tool") as ForgeResult.Success).value

        assertThat(first.items).containsExactly(gh[0], cb[0], gh[1]).inOrder()
        assertThat(first.totalCount).isEqualTo(4)
        assertThat(first.forges).containsExactly(ForgeInstance.GitHub, ForgeInstance.Codeberg, ForgeInstance.GitLab).inOrder()
        assertThat(codebergApi.tokens).containsExactly("t-codeberg")
        // Only GitHub has more: the next page asks it alone.
        assertThat(first.next).isEqualTo(SearchCursor(mapOf(ForgeInstance.GitHub to 2)))

        val second = (repository.repositories("tool", first.next) as ForgeResult.Success).value

        assertThat(second.items).containsExactly(gh[2])
        assertThat(second.next).isNull()
        assertThat(codebergApi.calls).containsExactly("repos:tool@1")
    }

    @Test
    fun one_forge_failing_leaves_the_others_results() = runTest {
        signInToCodeberg()
        api.repositories = listOf(repoSummary("a/gh1"))
        codebergApi.failure = ForgeError.Http(500, null)

        val page = (repository.repositories("tool") as ForgeResult.Success).value

        assertThat(page.items).containsExactly(repoSummary("a/gh1"))
    }

    @Test
    fun a_forge_failing_where_the_others_found_nothing_says_why() = runTest {
        // Signed out: GitHub's limit reached, and Codeberg and gitlab.com without a match, isn't "nothing found".
        api.failure = ForgeError.RateLimited(null)

        assertThat(repository.repositories("tool")).isEqualTo(ForgeResult.Failure(ForgeError.RateLimited(null)))
    }

    @Test
    fun every_forge_failing_fails_the_search() = runTest {
        signInToCodeberg()
        api.failure = ForgeError.Network
        codebergApi.failure = ForgeError.Http(500, null)

        assertThat(repository.repositories("tool")).isEqualTo(ForgeResult.Failure(ForgeError.Network))
    }

    @Test
    fun a_search_can_ask_one_forge_alone() = runTest {
        signInToCodeberg()
        api.repositories = listOf(repoSummary("a/gh1"))
        codebergApi.repositories = listOf(repoSummary("b/cb1", forge = ForgeInstance.Codeberg))

        val page = (repository.repositories("tool", only = ForgeInstance.Codeberg) as ForgeResult.Success).value

        assertThat(page.items.map { it.id.forge }).containsExactly(ForgeInstance.Codeberg)
        assertThat(page.forges).containsExactly(ForgeInstance.Codeberg)
        assertThat(api.calls).isEmpty()
        assertThat(repository.forges.first()).containsExactly(ForgeInstance.GitHub, ForgeInstance.Codeberg, ForgeInstance.GitLab).inOrder()
    }

    @Test
    fun a_topic_is_looked_for_on_the_three_public_forges_signed_out() = runTest {
        val gitlabApi = FakeSearchApi(pageSize = 2).apply { topicRepositories = listOf(repoSummary("c/gl1", forge = ForgeInstance.GitLab)) }
        clients.put(ForgeInstance.GitLab, FakeForgeClients(search = gitlabApi))
        api.topicRepositories = listOf(repoSummary("a/gh1"), repoSummary("a/gh2"))
        codebergApi.topicRepositories = listOf(repoSummary("b/cb1", forge = ForgeInstance.Codeberg))

        val page = (repository.topic("selfhosted") as ForgeResult.Success).value

        assertThat(page.forges).containsExactly(ForgeInstance.GitHub, ForgeInstance.Codeberg, ForgeInstance.GitLab).inOrder()
        assertThat(page.items.map { it.id.fullName }).containsExactly("a/gh1", "b/cb1", "c/gl1", "a/gh2").inOrder()
        assertThat(codebergApi.tokens).containsExactly(null)
        assertThat(codebergApi.calls).containsExactly("topic:selfhosted@1")
    }

    @Test
    fun a_topic_is_looked_for_on_a_server_signed_in_to_and_on_one_forge_alone() = runTest {
        val server = ForgeInstance(fr.arthurbrugiere.forgeline.core.model.ForgeType.FORGEJO, "git.example.org")
        val serverApi = FakeSearchApi().apply { topicRepositories = listOf(repoSummary("d/mine", forge = server)) }
        clients.put(server, FakeForgeClients(search = serverApi))
        accounts.signIn(server, ForgeUser("me", null, null), "t-server")

        assertThat(repository.forges.first()).containsExactly(ForgeInstance.GitHub, ForgeInstance.Codeberg, ForgeInstance.GitLab, server).inOrder()
        val page = (repository.topic("selfhosted", only = server) as ForgeResult.Success).value

        assertThat(page.items.map { it.id.fullName }).containsExactly("d/mine")
        assertThat(serverApi.tokens).containsExactly("t-server")
        assertThat(api.calls).isEmpty()
    }

    // Codeberg turns signed-out repository searches away (2026-11-12): the other forges' results come, Codeberg is named.

    @Test
    fun a_forge_turning_a_signed_out_search_away_is_named_not_a_failure() = runTest {
        api.repositories = listOf(repoSummary("a/gh1"))
        codebergApi.failure = ForgeError.Unauthorized

        val page = (repository.repositories("tool") as ForgeResult.Success).value

        assertThat(page.items.map { it.id.fullName }).containsExactly("a/gh1")
        assertThat(page.refused).containsExactly(ForgeInstance.Codeberg)
    }

    @Test
    fun the_refusing_forge_searched_alone_answers_with_nothing_found_there_and_its_name() = runTest {
        codebergApi.failure = ForgeError.Unauthorized

        val page = (repository.topic("rust", only = ForgeInstance.Codeberg) as ForgeResult.Success).value

        assertThat(page.items).isEmpty()
        assertThat(page.refused).containsExactly(ForgeInstance.Codeberg)
    }

    @Test
    fun signed_in_the_same_answer_is_a_failure_the_sign_in_ended() = runTest {
        signInToCodeberg()
        codebergApi.failure = ForgeError.Unauthorized

        val result = repository.repositories("tool", only = ForgeInstance.Codeberg)

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
    }

    @Test
    fun signing_in_somewhere_is_announced() = runTest {
        assertThat(repository.signedIn.first()).isEmpty()

        signInToCodeberg()

        assertThat(repository.signedIn.first()).containsExactly(ForgeInstance.Codeberg)
    }
}
