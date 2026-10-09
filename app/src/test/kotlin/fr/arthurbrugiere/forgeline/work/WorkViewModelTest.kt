package fr.arthurbrugiere.forgeline.work

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.work.WorkRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueSearchResult
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.WorkKind
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import fr.arthurbrugiere.forgeline.core.testing.FakeSearchApi
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import fr.arthurbrugiere.forgeline.core.testing.issueSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val api = FakeSearchApi()
    private val accounts = FakeAccountRepository()
    private val repository = WorkRepository(FakeForgeClients(search = api), accounts)
    private val review = IssueSearchResult(RepoId("octo", "tools"), issueSummary(4, "Fix the upload", isPullRequest = true))

    private fun test(block: suspend TestScope.() -> Unit) = runTest(mainDispatcherRule.testDispatcher) {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "tok")
        block()
    }

    @Test
    fun the_work_is_read_when_the_page_opens() = test {
        api.work[WorkKind.REVIEW_REQUESTED] = listOf(review)

        val viewModel = WorkViewModel(repository)
        assertThat(viewModel.state.value.isLoading).isTrue()
        assertThat(viewModel.state.value.work).isNull()
        advanceUntilIdle()

        assertThat(viewModel.state.value.isLoading).isFalse()
        assertThat(viewModel.state.value.work!!.sections[WorkKind.REVIEW_REQUESTED]).containsExactly(review)
    }

    @Test
    fun work_that_cannot_be_read_says_why() = test {
        api.failure = ForgeError.Network

        val viewModel = WorkViewModel(repository)
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isEqualTo(ForgeError.Network)
        assertThat(viewModel.state.value.work).isNull()
        assertThat(viewModel.state.value.isLoading).isFalse()
    }

    @Test
    fun a_refresh_that_fails_keeps_what_was_read_until_the_error_is_said() = test {
        api.work[WorkKind.REVIEW_REQUESTED] = listOf(review)
        val viewModel = WorkViewModel(repository)
        advanceUntilIdle()
        api.failure = ForgeError.Network

        viewModel.refresh()
        assertThat(viewModel.state.value.isLoading).isTrue()
        advanceUntilIdle()

        assertThat(viewModel.state.value.work!!.sections[WorkKind.REVIEW_REQUESTED]).containsExactly(review)
        assertThat(viewModel.state.value.error).isEqualTo(ForgeError.Network)
        viewModel.errorShown()
        assertThat(viewModel.state.value.error).isNull()
    }

    @Test
    fun a_refresh_reads_what_changed_and_forgets_the_last_error() = test {
        api.failure = ForgeError.Network
        val viewModel = WorkViewModel(repository)
        advanceUntilIdle()
        api.failure = null
        api.work[WorkKind.ASSIGNED] = listOf(review)

        viewModel.refresh()
        assertThat(viewModel.state.value.error).isNull()
        advanceUntilIdle()

        assertThat(viewModel.state.value.work!!.sections[WorkKind.ASSIGNED]).containsExactly(review)
    }

    @Test
    fun each_forge_s_work_shows_as_it_comes_in_and_loading_ends_with_the_last() = test {
        val codeberg = FakeSearchApi().apply { workGate = kotlinx.coroutines.CompletableDeferred() }
        val clients = FakeForgeClients(search = api).also { it.put(ForgeInstance.Codeberg, FakeForgeClients(search = codeberg)) }
        accounts.signIn(ForgeInstance.Codeberg, ForgeUser("alice", null, null), "tok-cb")
        api.work[WorkKind.REVIEW_REQUESTED] = listOf(review)

        val viewModel = WorkViewModel(WorkRepository(clients, accounts))
        testScheduler.runCurrent()

        assertThat(viewModel.state.value.work!!.sections[WorkKind.REVIEW_REQUESTED]).containsExactly(review)
        assertThat(viewModel.state.value.work!!.pending).containsExactly(ForgeInstance.Codeberg)
        assertThat(viewModel.state.value.isLoading).isTrue()

        codeberg.workGate!!.complete(Unit)
        advanceUntilIdle()

        assertThat(viewModel.state.value.work!!.pending).isEmpty()
        assertThat(viewModel.state.value.isLoading).isFalse()
    }

    @Test
    fun a_new_refresh_drops_the_one_still_under_way() = test {
        api.workGate = kotlinx.coroutines.CompletableDeferred()
        val viewModel = WorkViewModel(repository)
        testScheduler.runCurrent()
        val first = api.calls.size

        api.workGate = null
        api.work[WorkKind.ASSIGNED] = listOf(review)
        viewModel.refresh()
        advanceUntilIdle()

        assertThat(api.calls.size).isEqualTo(first * 2)
        assertThat(viewModel.state.value.work!!.sections[WorkKind.ASSIGNED]).containsExactly(review)
        assertThat(viewModel.state.value.isLoading).isFalse()
    }
}
