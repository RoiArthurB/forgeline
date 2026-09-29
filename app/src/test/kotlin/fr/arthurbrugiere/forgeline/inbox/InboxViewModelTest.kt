package fr.arthurbrugiere.forgeline.inbox

import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.inbox.SyncResult
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.NotificationReason
import fr.arthurbrugiere.forgeline.core.model.InboxCheckInterval
import fr.arthurbrugiere.forgeline.core.testing.FakeInboxRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeUserSettingsRepository
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import fr.arthurbrugiere.forgeline.core.testing.notificationThread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val inbox = FakeInboxRepository()
    private val accounts = FakeAccountRepository()
    private val settings = FakeUserSettingsRepository()

    private fun test(block: suspend TestScope.() -> Unit) = runTest(mainDispatcherRule.testDispatcher) { block() }

    private fun TestScope.viewModel(savedState: SavedStateHandle = SavedStateHandle()) =
        InboxViewModel(savedState, inbox, settings, accounts, backgroundScope).also { it.state.launchIn(backgroundScope) }

    private val mention = notificationThread("1", repo = "acme/rocket", reason = NotificationReason.MENTION, updatedAt = "2026-09-27T09:00:00Z")
    private val watching = notificationThread("2", repo = "octo/tools", reason = NotificationReason.SUBSCRIBED, updatedAt = "2026-09-27T09:30:00Z")
    private val read = notificationThread("3", repo = "acme/rocket", reason = NotificationReason.COMMENT, unread = false, updatedAt = "2026-09-27T08:00:00Z")

    @Test
    fun opens_on_unread_threads_needing_you_first_and_syncs() = test {
        inbox.set(watching, mention, read)

        val viewModel = viewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.filter).isEqualTo(InboxFilter.UNREAD)
        assertThat(state.groups.map { it.section }).containsExactly(InboxSection.NEEDS_YOU, InboxSection.OTHERS).inOrder()
        assertThat(state.groups.flatMap { g -> g.threads.map { it.id } }).containsExactly("1", "2").inOrder()
        assertThat(inbox.syncs).containsExactly(false)
    }

    @Test
    fun everything_else_is_ordered_by_owner_then_repository() = test {
        val rocketNew = notificationThread("4", repo = "acme/rocket", reason = NotificationReason.SUBSCRIBED, updatedAt = "2026-09-27T09:50:00Z")
        val tools = notificationThread("5", repo = "octo/tools", reason = NotificationReason.SUBSCRIBED, updatedAt = "2026-09-27T09:40:00Z")
        val sat = notificationThread("6", repo = "Acme/satellite", reason = NotificationReason.SUBSCRIBED, updatedAt = "2026-09-27T09:35:00Z")
        val rocketOld = notificationThread("7", repo = "acme/rocket", reason = NotificationReason.COMMENT, updatedAt = "2026-09-27T09:30:00Z")
        inbox.set(rocketNew, tools, sat, rocketOld)

        val viewModel = viewModel()
        advanceUntilIdle()

        val others = viewModel.state.value.groups.single { it.section == InboxSection.OTHERS }
        assertThat(others.threads.map { it.id }).containsExactly("4", "7", "6", "5").inOrder()
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
        assertThat(viewModel.state.value.groups.flatMap { g -> g.threads.map { it.id } }).containsExactly("1", "2", "3").inOrder()
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
    fun actions_show_at_once_but_reach_the_forge_after_the_undo_window() = test {
        inbox.set(mention, watching)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.markDone(mention)
        viewModel.unsubscribe(watching)
        runCurrent()
        assertThat(viewModel.state.value.groups).isEmpty()
        assertThat(viewModel.state.value.undo).isEqualTo(PendingUndo(notificationThread("2").key, InboxAction.UNSUBSCRIBE, serial = 2))
        assertThat(inbox.actions).isEmpty()

        advanceTimeBy(InboxViewModel.UNDO_MILLIS + 1)
        runCurrent()
        assertThat(inbox.actions).containsExactly("done:1", "unsubscribe:2").inOrder()
        assertThat(viewModel.state.value.undo).isNull()
    }

    @Test
    fun the_same_thread_id_on_two_accounts_is_two_threads() = test {
        // Regression guard: pending actions were keyed by thread id alone, which two forges can share.
        val onGitHub = notificationThread("1", reason = NotificationReason.SUBSCRIBED).copy(accountId = "github:github.com:me")
        val onCodeberg = notificationThread("1", repo = "forgejo/forgejo", reason = NotificationReason.SUBSCRIBED)
            .copy(accountId = "forgejo:codeberg.org:me")
        inbox.set(onGitHub, onCodeberg)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.markDone(onCodeberg)
        runCurrent()

        assertThat(viewModel.state.value.groups.flatMap { it.threads }).containsExactly(onGitHub)
    }

    @Test
    fun undo_brings_the_thread_back_and_never_reaches_the_forge() = test {
        inbox.set(mention)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.markDone(mention)
        runCurrent()
        viewModel.undo(viewModel.state.value.undo!!)
        advanceUntilIdle()

        assertThat(viewModel.state.value.groups.flatMap { g -> g.threads.map { it.id } }).containsExactly("1")
        assertThat(viewModel.state.value.undo).isNull()
        assertThat(inbox.actions).isEmpty()
    }

    @Test
    fun a_read_thread_shows_as_read_while_undo_is_offered() = test {
        inbox.set(mention)
        val viewModel = viewModel()
        viewModel.selectFilter(InboxFilter.ALL)
        advanceUntilIdle()

        viewModel.markRead(mention)
        runCurrent()

        assertThat(viewModel.state.value.groups.single().threads.single().unread).isFalse()
        assertThat(inbox.actions).isEmpty()
    }

    @Test
    fun failures_are_reported_once_sent() = test {
        inbox.set(mention)
        inbox.actionFailure = ForgeError.Network
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.markRead(mention)
        advanceUntilIdle()

        assertThat(viewModel.state.value.actionFailed).isTrue()
    }

    @Test
    fun knows_whether_background_checks_are_on() = test {
        val viewModel = viewModel()
        advanceUntilIdle()
        assertThat(viewModel.state.value.backgroundChecks).isTrue()

        settings.setInboxCheckInterval(InboxCheckInterval.OFF)
        advanceUntilIdle()
        assertThat(viewModel.state.value.backgroundChecks).isFalse()
    }

    @Test
    fun with_the_setting_on_each_account_gets_its_own_tab() = test {
        val github = accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "g")
        val codeberg = accounts.signIn(ForgeInstance.Codeberg, ForgeUser("me", null, null), "c")
        val onGitHub = notificationThread("1", reason = NotificationReason.SUBSCRIBED).copy(accountId = github.id)
        val onCodeberg = notificationThread("2", repo = "forgejo/forgejo", reason = NotificationReason.SUBSCRIBED).copy(accountId = codeberg.id)
        inbox.set(onGitHub, onCodeberg)
        val viewModel = viewModel()
        advanceUntilIdle()

        // One list by default, and rows name their forge since two are signed in.
        assertThat(viewModel.state.value.accountTabs).isEmpty()
        assertThat(viewModel.state.value.showForge).isTrue()
        assertThat(viewModel.state.value.groups.flatMap { it.threads }).hasSize(2)

        settings.setSeparateInboxPerForge(true)
        advanceUntilIdle()
        assertThat(viewModel.state.value.accountTabs).containsExactly(github, codeberg).inOrder()
        assertThat(viewModel.state.value.groups.flatMap { it.threads }).containsExactly(onGitHub)

        viewModel.selectAccount(codeberg.id)
        advanceUntilIdle()
        assertThat(viewModel.state.value.groups.flatMap { it.threads }).containsExactly(onCodeberg)
    }
}
