package fr.arthurbrugiere.forgeline.inbox

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.inbox.SyncResult
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.NotificationReason
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.testing.FakeInboxRepository
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import fr.arthurbrugiere.forgeline.core.testing.notificationThread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val inbox = FakeInboxRepository()

    private fun test(block: suspend TestScope.() -> Unit) = runTest(mainDispatcherRule.testDispatcher) { block() }

    private fun TestScope.viewModel(savedState: SavedStateHandle = SavedStateHandle()) =
        InboxViewModel(savedState, inbox).also { it.state.launchIn(backgroundScope) }

    private val mention = notificationThread("1", repo = "acme/rocket", reason = NotificationReason.MENTION, updatedAt = "2026-09-27T09:00:00Z")
    private val watching = notificationThread("2", repo = "octo/tools", reason = NotificationReason.SUBSCRIBED, updatedAt = "2026-09-27T09:30:00Z")
    private val read = notificationThread("3", repo = "acme/rocket", reason = NotificationReason.COMMENT, unread = false, updatedAt = "2026-09-27T08:00:00Z")

    @Test
    fun opens_on_unread_threads_grouped_by_repo_newest_first_and_syncs() = test {
        inbox.set(watching, mention, read)

        val viewModel = viewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.filter).isEqualTo(InboxFilter.UNREAD)
        assertThat(state.groups.map { it.repo }).containsExactly(RepoId("octo", "tools"), RepoId("acme", "rocket")).inOrder()
        assertThat(state.groups.flatMap { g -> g.threads.map { it.id } }).containsExactly("2", "1").inOrder()
        assertThat(inbox.syncs).containsExactly(false)
    }

    @Test
    fun participating_and_all_filters() = test {
        inbox.set(watching, mention, read)
        val saved = SavedStateHandle()
        val viewModel = viewModel(saved)
        advanceUntilIdle()

        viewModel.selectFilter(InboxFilter.PARTICIPATING)
        advanceUntilIdle()
        assertThat(viewModel.state.value.groups.flatMap { g -> g.threads.map { it.id } }).containsExactly("1", "3").inOrder()

        viewModel.selectFilter(InboxFilter.ALL)
        advanceUntilIdle()
        assertThat(viewModel.state.value.groups.flatMap { g -> g.threads.map { it.id } }).containsExactly("2", "1", "3").inOrder()
        assertThat(viewModel(saved).state.value.filter).isEqualTo(InboxFilter.ALL)
    }

    @Test
    fun pull_to_refresh_forces_a_sync_and_reports_failures() = test {
        val viewModel = viewModel()
        advanceUntilIdle()
        inbox.nextSync = SyncResult.Failed(ForgeError.Network)

        viewModel.refresh()
        advanceUntilIdle()

        assertThat(inbox.syncs).containsExactly(false, true).inOrder()
        assertThat(viewModel.state.value.error).isEqualTo(ForgeError.Network)
    }

    @Test
    fun opening_a_thread_marks_it_read() = test {
        inbox.set(mention)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.opened(mention)
        advanceUntilIdle()

        assertThat(inbox.actions).containsExactly("read:1")
    }

    @Test
    fun opening_an_already_read_thread_does_nothing() = test {
        inbox.set(read)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.opened(read)
        advanceUntilIdle()

        assertThat(inbox.actions).isEmpty()
    }

    @Test
    fun swipe_actions_reach_the_inbox_and_failures_are_reported() = test {
        inbox.set(mention, watching)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.markDone(mention)
        viewModel.unsubscribe(watching)
        advanceUntilIdle()
        assertThat(inbox.actions).containsExactly("done:1", "unsubscribe:2").inOrder()
        assertThat(viewModel.state.value.groups).isEmpty()

        inbox.set(mention)
        inbox.actionFailure = ForgeError.Network
        viewModel.markRead(mention)
        advanceUntilIdle()
        assertThat(viewModel.state.value.actionFailed).isTrue()
    }
}
