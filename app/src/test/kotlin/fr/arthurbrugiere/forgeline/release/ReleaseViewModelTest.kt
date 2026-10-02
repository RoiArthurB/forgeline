package fr.arthurbrugiere.forgeline.release

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.Release
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.testing.FakeRepoRepository
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReleaseViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val repos = FakeRepoRepository()
    private val repo = RepoId("octo", "repo")
    private val listed = Release("v2", "Tools 2.0", "Faster.", null, false, null, isLatest = true)

    private fun test(block: suspend TestScope.() -> Unit) = runTest(mainDispatcherRule.testDispatcher) { block() }

    private val loads get() = repos.calls.filter { it.startsWith("release:") }

    @Test
    fun a_release_opened_from_its_list_shows_at_once_and_asks_the_forge_nothing() = test {
        repos.seenReleases["v2"] = listed

        val viewModel = ReleaseViewModel(repo, "v2", repos)
        advanceUntilIdle()

        assertThat(viewModel.state.value).isEqualTo(ReleaseUiState(repo, "v2", listed))
        assertThat(loads).isEmpty()
    }

    @Test
    fun a_release_opened_from_a_link_is_loaded() = test {
        repos.releaseAnswers["v2"] = ForgeResult.Success(listed)
        repos.gate = CompletableDeferred()

        val viewModel = ReleaseViewModel(repo, "v2", repos)
        runCurrent()
        assertThat(viewModel.state.value.isRefreshing).isTrue()
        assertThat(viewModel.state.value.release).isNull()
        repos.gate?.complete(Unit)
        advanceUntilIdle()

        assertThat(viewModel.state.value).isEqualTo(ReleaseUiState(repo, "v2", listed))
        assertThat(loads).containsExactly("release:octo/repo@v2")
    }

    @Test
    fun a_tag_without_a_release_is_an_error_to_retry() = test {
        val viewModel = ReleaseViewModel(repo, "nope", repos)
        advanceUntilIdle()
        assertThat(viewModel.state.value.error).isEqualTo(ForgeError.Http(404, "Not Found"))
        assertThat(viewModel.state.value.release).isNull()
        assertThat(viewModel.state.value.isRefreshing).isFalse()

        repos.releaseAnswers["nope"] = ForgeResult.Success(listed.copy(tag = "nope"))
        viewModel.refresh()
        advanceUntilIdle()

        assertThat(viewModel.state.value.release?.tag).isEqualTo("nope")
        assertThat(viewModel.state.value.error).isNull()
    }

    @Test
    fun refreshing_loads_it_again_and_a_failure_keeps_what_was_shown() = test {
        repos.seenReleases["v2"] = listed
        val viewModel = ReleaseViewModel(repo, "v2", repos)
        repos.releaseAnswers["v2"] = ForgeResult.Success(listed.copy(body = "Faster, and fixed."))

        viewModel.refresh()
        advanceUntilIdle()
        assertThat(viewModel.state.value.release?.body).isEqualTo("Faster, and fixed.")

        repos.releaseAnswers["v2"] = ForgeResult.Failure(ForgeError.Network)
        viewModel.refresh()
        advanceUntilIdle()

        assertThat(viewModel.state.value.release?.body).isEqualTo("Faster, and fixed.")
        assertThat(viewModel.state.value.error).isEqualTo(ForgeError.Network)
        viewModel.errorShown()
        assertThat(viewModel.state.value.error).isNull()
    }
}
