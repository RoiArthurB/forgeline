package fr.arthurbrugiere.forgeline.collections

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.collections.ImportReport
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeCollectionsRepository
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import fr.arthurbrugiere.forgeline.core.testing.repoSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionsViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule(StandardTestDispatcher())

    private val repository = FakeCollectionsRepository()
    private val accounts = FakeAccountRepository()

    @Test
    fun a_collection_made_opens_and_only_github_accounts_import() = runTest(main.testDispatcher) {
        accounts.signIn(ForgeInstance.Codeberg, ForgeUser("me", null, null), "t")
        val github = accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "t")
        val viewModel = CollectionsViewModel(repository, accounts).also { it.state.launchIn(backgroundScope) }
        val opened = mutableListOf<String>()
        backgroundScope.launch { viewModel.created.toList(opened) }
        advanceUntilIdle()

        viewModel.create("  ")
        viewModel.create("Matrix tools")
        advanceUntilIdle()

        assertThat(viewModel.state.value.collections!!.map { it.name }).containsExactly("Matrix tools")
        assertThat(opened).containsExactly("c1")
        assertThat(viewModel.state.value.importable).containsExactly(github)
    }

    @Test
    fun an_import_says_what_it_brought_or_why_not() = runTest(main.testDispatcher) {
        val github = accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "t")
        repository.importAnswer = ForgeResult.Success(ImportReport(lists = 3, added = 12))
        val viewModel = CollectionsViewModel(repository, accounts).also { it.state.launchIn(backgroundScope) }

        viewModel.import(github)
        advanceUntilIdle()
        assertThat(viewModel.state.value.imported).isEqualTo(ImportReport(3, 12))
        assertThat(viewModel.state.value.importing).isNull()

        viewModel.importShown()
        repository.importAnswer = ForgeResult.Failure(ForgeError.Network)
        viewModel.import(github)
        advanceUntilIdle()
        assertThat(viewModel.state.value.importFailed).isEqualTo(ForgeError.Network)
    }

    @Test
    fun one_collection_renames_takes_out_puts_back_and_deletes() = runTest(main.testDispatcher) {
        val id = repository.create("Tools")
        repository.add(id, repoSummary("a/one"))
        val viewModel = CollectionViewModel(id, repository).also { it.state.launchIn(backgroundScope) }
        advanceUntilIdle()
        assertThat(viewModel.state.value.repos!!.map { it.id.fullName }).containsExactly("a/one")

        viewModel.rename("My tools")
        viewModel.remove(repoSummary("a/one").id)
        advanceUntilIdle()
        assertThat(viewModel.state.value.name).isEqualTo("My tools")
        assertThat(viewModel.state.value.repos).isEmpty()

        viewModel.restore(repoSummary("a/one"))
        advanceUntilIdle()
        assertThat(viewModel.state.value.repos).hasSize(1)

        viewModel.delete()
        advanceUntilIdle()
        assertThat(viewModel.state.value.name).isNull()
    }

    @Test
    fun from_a_repository_s_page_it_goes_in_and_out_and_in_a_new_one() = runTest(main.testDispatcher) {
        val repo = repoSummary("a/one")
        val tools = repository.create("Tools")
        val viewModel = CollectionPickerViewModel(repo.id, repository).also { it.state.launchIn(backgroundScope) }
        advanceUntilIdle()

        viewModel.toggle(tools, repo)
        advanceUntilIdle()
        assertThat(viewModel.state.value.holding).containsExactly(tools)

        viewModel.toggle(tools, repo)
        viewModel.createWith("Fresh", repo)
        advanceUntilIdle()
        val fresh = repository.observeAll().first().single { it.name == "Fresh" }.id
        assertThat(viewModel.state.value.holding).containsExactly(fresh)
    }
}
