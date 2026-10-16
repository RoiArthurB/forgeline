package fr.arthurbrugiere.forgeline.you

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.yours.YourReposRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.UserSummary
import fr.arthurbrugiere.forgeline.core.model.YourRepos
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class YouViewModelTest {
    @get:Rule
    val main = MainDispatcherRule(StandardTestDispatcher())

    private val stored = MutableStateFlow<YourRepos?>(null)
    private val forced = mutableListOf<Boolean>()
    private var answer: ForgeResult<Unit> = ForgeResult.Success(Unit)
    private val repository = object : YourReposRepository {
        override fun observe() = stored
        override suspend fun refresh(force: Boolean) = answer.also { forced += force }
    }

    private fun repo(full: String) = full.split('/').let { (o, n) -> RepoSummary(RepoId(o, n), null, null, 0, 0, false, null) }

    private val all = YourRepos.of(
        listOf(ForgeUser("me", null, null) to ForgeInstance.GitHub),
        listOf(repo("me/forgeline"), repo("ACROSS-Lab/GamaWebPlayer"), repo("gama-platform/gama")),
        listOf(UserSummary("ACROSS-Lab", null, true), UserSummary("gama-platform", null, true)),
        1L,
    )

    @Test
    fun what_is_typed_and_the_owner_picked_narrow_the_list_together() = runTest(main.testDispatcher) {
        stored.value = all
        val viewModel = YouViewModel(repository, main.testDispatcher)
        viewModel.state.launchIn(backgroundScope)
        advanceUntilIdle()
        assertThat(viewModel.state.value.shown).hasSize(3)

        viewModel.search("gama")
        advanceUntilIdle()
        assertThat(viewModel.state.value.shown.map { it.id.fullName }).containsExactly("ACROSS-Lab/GamaWebPlayer", "gama-platform/gama")

        viewModel.pick(fr.arthurbrugiere.forgeline.core.model.RepoFilter.Of(all.owners.first { it.login == "ACROSS-Lab" }))
        advanceUntilIdle()
        assertThat(viewModel.state.value.shown.map { it.id.fullName }).containsExactly("ACROSS-Lab/GamaWebPlayer")

        // The same owner again shows everyone's.
        viewModel.pick(fr.arthurbrugiere.forgeline.core.model.RepoFilter.Of(all.owners.first { it.login == "ACROSS-Lab" }))
        advanceUntilIdle()
        assertThat(viewModel.state.value.filter).isEqualTo(fr.arthurbrugiere.forgeline.core.model.RepoFilter.All)
    }

    @Test
    fun opening_reads_the_list_if_stale_and_pulling_reads_it_again() = runTest(main.testDispatcher) {
        val viewModel = YouViewModel(repository, main.testDispatcher)
        advanceUntilIdle()
        viewModel.refresh()
        advanceUntilIdle()

        assertThat(forced).containsExactly(false, true).inOrder()
    }

    @Test
    fun a_failed_read_is_said() = runTest(main.testDispatcher) {
        answer = ForgeResult.Failure(ForgeError.Network)
        val viewModel = YouViewModel(repository, main.testDispatcher)
        viewModel.state.launchIn(backgroundScope)
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isEqualTo(ForgeError.Network)
    }
}
