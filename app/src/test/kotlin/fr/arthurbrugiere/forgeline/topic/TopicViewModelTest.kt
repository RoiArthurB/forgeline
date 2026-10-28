package fr.arthurbrugiere.forgeline.topic

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.search.SearchRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import fr.arthurbrugiere.forgeline.core.testing.FakeSearchApi
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import fr.arthurbrugiere.forgeline.core.testing.repoSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TopicViewModelTest {
    @get:Rule
    val main = MainDispatcherRule(StandardTestDispatcher())

    private val github = FakeSearchApi(pageSize = 2).apply { topicRepositories = listOf(repoSummary("a/one"), repoSummary("a/two"), repoSummary("a/three")) }
    private val codeberg = FakeSearchApi(pageSize = 2).apply { topicRepositories = listOf(repoSummary("b/one", forge = ForgeInstance.Codeberg)) }
    private val gitlab = FakeSearchApi(pageSize = 2)
    private val clients = FakeForgeClients(search = github).also {
        it.put(ForgeInstance.Codeberg, FakeForgeClients(search = codeberg))
        it.put(ForgeInstance.GitLab, FakeForgeClients(search = gitlab))
    }

    private fun viewModel() = TopicViewModel("selfhosted", SearchRepository(clients, FakeAccountRepository()))

    @Test
    fun a_topic_opens_on_every_public_forge_s_most_starred_mixed() = runTest(main.testDispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.repos.map { it.id.fullName }).containsExactly("a/one", "b/one", "a/two").inOrder()
        assertThat(state.totalCount).isEqualTo(4)
        assertThat(state.forges).containsExactly(ForgeInstance.GitHub, ForgeInstance.Codeberg, ForgeInstance.GitLab).inOrder()
        assertThat(state.showForge).isTrue()

        viewModel.loadMore()
        advanceUntilIdle()

        assertThat(viewModel.state.value.repos.map { it.id.fullName }).containsExactly("a/one", "b/one", "a/two", "a/three").inOrder()
        assertThat(viewModel.state.value.hasMore).isFalse()
    }

    @Test
    fun one_forge_picked_is_asked_alone_from_its_first_page() = runTest(main.testDispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.selectForge(ForgeInstance.Codeberg)
        advanceUntilIdle()

        assertThat(viewModel.state.value.repos.map { it.id.fullName }).containsExactly("b/one")
        assertThat(viewModel.state.value.showForge).isFalse()
        assertThat(codeberg.calls).containsExactly("topic:selfhosted@1", "topic:selfhosted@1")
    }

    @Test
    fun every_forge_failing_says_so_and_a_retry_asks_again() = runTest(main.testDispatcher) {
        listOf(github, codeberg, gitlab).forEach { it.failure = ForgeError.Network }
        val viewModel = viewModel()
        advanceUntilIdle()
        assertThat(viewModel.state.value.error).isEqualTo(ForgeError.Network)

        listOf(github, codeberg, gitlab).forEach { it.failure = null }
        viewModel.retry()
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isNull()
        assertThat(viewModel.state.value.repos).isNotEmpty()
    }
}
