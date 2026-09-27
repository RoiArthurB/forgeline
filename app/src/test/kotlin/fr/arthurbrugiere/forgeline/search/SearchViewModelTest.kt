package fr.arthurbrugiere.forgeline.search

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.search.SearchRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.SearchScope
import fr.arthurbrugiere.forgeline.core.model.UserSummary
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeSearchApi
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import fr.arthurbrugiere.forgeline.core.testing.repoSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val api = FakeSearchApi(pageSize = 2).apply {
        repositories = listOf(repoSummary("acme/rocket"), repoSummary("acme/fuel"), repoSummary("acme/pad"))
        users = listOf(UserSummary("octocat", null, isOrganization = false))
    }

    private fun test(block: suspend TestScope.() -> Unit) = runTest(mainDispatcherRule.testDispatcher) { block() }

    private fun TestScope.viewModel(saved: SavedStateHandle = SavedStateHandle()) =
        SearchViewModel(saved, SearchRepository(api, FakeAccountRepository())).also { it.state.launchIn(backgroundScope) }

    @Test
    fun nothing_is_searched_until_submitted() = test {
        val viewModel = viewModel()

        viewModel.onQueryChange("rocket")
        advanceUntilIdle()

        assertThat(api.calls).isEmpty()
        assertThat(viewModel.state.value.query).isEqualTo("rocket")
        assertThat(viewModel.state.value.results.submitted).isFalse()
    }

    @Test
    fun submitting_searches_the_current_scope() = test {
        val viewModel = viewModel()
        viewModel.onQueryChange("  rocket ")

        viewModel.submit()
        advanceUntilIdle()

        val results = viewModel.state.value.results
        assertThat(api.calls).containsExactly("repos:rocket@1")
        assertThat(results.items).hasSize(2)
        assertThat(results.totalCount).isEqualTo(3)
        assertThat(results.hasMore).isTrue()
    }

    @Test
    fun blank_queries_are_ignored() = test {
        val viewModel = viewModel()
        viewModel.onQueryChange("   ")

        viewModel.submit()
        advanceUntilIdle()

        assertThat(api.calls).isEmpty()
    }

    @Test
    fun switching_scope_searches_it_once_for_the_same_query() = test {
        val viewModel = viewModel()
        viewModel.onQueryChange("octo")
        viewModel.submit()
        advanceUntilIdle()

        viewModel.selectScope(SearchScope.USERS)
        advanceUntilIdle()
        assertThat(viewModel.state.value.results.items).containsExactly(SearchResult.User(UserSummary("octocat", null, false)))

        viewModel.selectScope(SearchScope.REPOSITORIES)
        viewModel.selectScope(SearchScope.USERS)
        advanceUntilIdle()
        assertThat(api.calls).containsExactly("repos:octo@1", "users:octo@1").inOrder()
    }

    @Test
    fun a_new_query_replaces_every_scope() = test {
        val viewModel = viewModel()
        viewModel.onQueryChange("octo")
        viewModel.submit()
        viewModel.selectScope(SearchScope.USERS)
        advanceUntilIdle()

        viewModel.onQueryChange("rocket")
        viewModel.submit()
        viewModel.selectScope(SearchScope.REPOSITORIES)
        advanceUntilIdle()

        assertThat(api.calls).containsExactly("repos:octo@1", "users:octo@1", "users:rocket@1", "repos:rocket@1").inOrder()
    }

    @Test
    fun more_results_are_appended_once_at_a_time() = test {
        val viewModel = viewModel()
        viewModel.onQueryChange("acme")
        viewModel.submit()
        advanceUntilIdle()

        viewModel.loadMore()
        viewModel.loadMore()
        advanceUntilIdle()

        val results = viewModel.state.value.results
        assertThat(results.items.map { (it as SearchResult.Repository).repo.id.name }).containsExactly("rocket", "fuel", "pad").inOrder()
        assertThat(results.hasMore).isFalse()
        assertThat(api.calls).containsExactly("repos:acme@1", "repos:acme@2").inOrder()
    }

    @Test
    fun failures_are_shown_and_retried() = test {
        api.failure = ForgeError.RateLimited(null)
        val viewModel = viewModel()
        viewModel.onQueryChange("acme")
        viewModel.submit()
        advanceUntilIdle()
        assertThat(viewModel.state.value.results.error).isEqualTo(ForgeError.RateLimited(null))

        api.failure = null
        viewModel.retry()
        advanceUntilIdle()
        assertThat(viewModel.state.value.results.error).isNull()
        assertThat(viewModel.state.value.results.items).hasSize(2)
    }

    @Test
    fun the_query_and_scope_survive_process_death() = test {
        val saved = SavedStateHandle()
        val first = viewModel(saved)
        first.onQueryChange("octo")
        first.submit()
        first.selectScope(SearchScope.USERS)
        advanceUntilIdle()

        val restored = viewModel(saved)
        advanceUntilIdle()

        assertThat(restored.state.value.query).isEqualTo("octo")
        assertThat(restored.state.value.scope).isEqualTo(SearchScope.USERS)
        assertThat(restored.state.value.results.items).hasSize(1)
    }
}
