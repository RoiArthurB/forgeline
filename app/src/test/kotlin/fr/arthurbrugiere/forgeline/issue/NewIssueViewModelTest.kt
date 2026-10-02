package fr.arthurbrugiere.forgeline.issue

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.issue.DefaultIssueRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import fr.arthurbrugiere.forgeline.core.testing.FakeIssueApi
import fr.arthurbrugiere.forgeline.core.testing.InMemoryConversationDao
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class NewIssueViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val api = FakeIssueApi()
    private val accounts = FakeAccountRepository()
    private val repository = DefaultIssueRepository(FakeForgeClients(issues = api), accounts, InMemoryConversationDao(), Clock.systemUTC())
    private val drafts = IssueDrafts()
    private val repo = RepoId("octo", "repo")

    private fun test(block: suspend TestScope.() -> Unit) = runTest(mainDispatcherRule.testDispatcher) {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "tok")
        block()
    }

    private fun viewModel() = NewIssueViewModel(repo, repository, drafts)

    @Test
    fun an_issue_needs_a_title_but_not_a_description() = test {
        val viewModel = viewModel()
        assertThat(viewModel.state.value.canSend).isFalse()

        viewModel.bodyChanged("Steps")
        assertThat(viewModel.state.value.canSend).isFalse()
        viewModel.titleChanged("   ")
        assertThat(viewModel.state.value.canSend).isFalse()

        viewModel.titleChanged("Crash")
        viewModel.bodyChanged("")
        assertThat(viewModel.state.value.canSend).isTrue()
    }

    @Test
    fun sending_without_a_title_asks_the_forge_nothing() = test {
        val viewModel = viewModel()
        viewModel.bodyChanged("Steps")

        viewModel.send()
        advanceUntilIdle()

        assertThat(api.calls).isEmpty()
    }

    @Test
    fun sending_opens_the_issue_trimmed_and_hands_over_its_conversation() = test {
        val viewModel = viewModel()
        viewModel.titleChanged("  Crash on start ")
        viewModel.bodyChanged("Steps:\n1. Open it\n")

        viewModel.send()
        advanceUntilIdle()

        assertThat(api.opened).containsExactly("octo/repo: Crash on start / Steps:\n1. Open it")
        assertThat(viewModel.state.value.created).isEqualTo(IssueRef(repo, 100))
        assertThat(viewModel.state.value.isSending).isFalse()
        // The conversation it becomes is already kept: it opens without waiting for the forge.
        assertThat(repository.cached(IssueRef(repo, 100))?.issue?.title).isEqualTo("Crash on start")
    }

    @Test
    fun while_it_is_on_its_way_it_can_t_be_sent_twice() = test {
        api.gate = CompletableDeferred()
        val viewModel = viewModel()
        viewModel.titleChanged("Crash")

        viewModel.send()
        advanceUntilIdle()
        assertThat(viewModel.state.value.isSending).isTrue()
        assertThat(viewModel.state.value.canSend).isFalse()
        viewModel.send()
        api.gate?.complete(Unit)
        advanceUntilIdle()

        assertThat(api.opened).hasSize(1)
    }

    @Test
    fun a_failure_keeps_what_was_written_and_says_why() = test {
        api.createFailure = ForgeError.Network
        val viewModel = viewModel()
        viewModel.titleChanged("Crash")
        viewModel.bodyChanged("Steps")

        viewModel.send()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.error).isEqualTo(ForgeError.Network)
        assertThat(state.title).isEqualTo("Crash")
        assertThat(state.body).isEqualTo("Steps")
        assertThat(state.created).isNull()
        assertThat(state.canSend).isTrue()

        // Writing again takes the error away.
        viewModel.bodyChanged("Steps, again")
        assertThat(viewModel.state.value.error).isNull()
    }

    @Test
    fun without_an_account_on_the_forge_nothing_is_sent() = test {
        val codeberg = RepoId("octo", "repo", ForgeInstance.Codeberg)
        val viewModel = NewIssueViewModel(codeberg, repository, drafts)
        viewModel.titleChanged("Crash")

        viewModel.send()
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isEqualTo(ForgeError.Unauthorized)
        assertThat(api.opened).isEmpty()
    }

    @Test
    fun an_issue_left_half_written_is_there_when_the_form_reopens() = test {
        viewModel().run {
            titleChanged("Crash")
            bodyChanged("Steps")
        }

        // The form was left (Back by mistake, or to look something up) and opened again.
        val reopened = viewModel()

        assertThat(reopened.state.value.title).isEqualTo("Crash")
        assertThat(reopened.state.value.body).isEqualTo("Steps")
        // Each repository has its own.
        assertThat(NewIssueViewModel(RepoId("octo", "other"), repository, drafts).state.value.title).isEmpty()
    }

    @Test
    fun an_issue_that_was_opened_is_not_offered_again() = test {
        val viewModel = viewModel()
        viewModel.titleChanged("Crash")
        viewModel.send()
        advanceUntilIdle()

        assertThat(viewModel().state.value.title).isEmpty()
    }
}
