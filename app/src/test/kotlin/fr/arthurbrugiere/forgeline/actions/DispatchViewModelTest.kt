package fr.arthurbrugiere.forgeline.actions

import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.actions.DefaultActionsRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.DispatchInput
import fr.arthurbrugiere.forgeline.core.model.DispatchInputType
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.Workflow
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeActionsApi
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import fr.arthurbrugiere.forgeline.repo.Loadable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DispatchViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val api = FakeActionsApi()
    private val accounts = FakeAccountRepository()
    private val repo = RepoId("octo", "repo")
    private val release = Workflow(1, "Release", ".github/workflows/release.yml")
    private val lint = Workflow(2, "Lint", ".github/workflows/lint.yml")
    private val channel = DispatchInput("channel", "Release channel", DispatchInputType.CHOICE, required = true, default = "stable", options = listOf("stable", "beta"))
    private val notes = DispatchInput("notes", null, DispatchInputType.STRING, required = false, default = null, options = emptyList())
    private val dryRun = DispatchInput("dry_run", null, DispatchInputType.BOOLEAN, required = false, default = null, options = emptyList())
    private val tag = DispatchInput("tag", null, DispatchInputType.STRING, required = true, default = null, options = emptyList())

    private fun test(block: suspend TestScope.() -> Unit) = runTest(mainDispatcherRule.testDispatcher) {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "ghp_token")
        api.workflows = listOf(release, lint)
        block()
    }

    private fun TestScope.opened(): DispatchViewModel = DispatchViewModel(repo, "main", DefaultActionsRepository(FakeForgeClients(actions = api), accounts)).also {
        it.open()
        advanceUntilIdle()
    }

    @Test
    fun opening_lists_the_workflows_once() = test {
        val viewModel = opened()
        viewModel.open()
        advanceUntilIdle()

        assertThat(viewModel.state.value.workflows).isEqualTo(Loadable.Loaded(listOf(release, lint)))
        assertThat(api.calls.count { it.startsWith("workflows:") }).isEqualTo(1)
    }

    @Test
    fun workflows_that_can_be_started_by_hand_come_first() = test {
        api.inputs[1] = null
        api.inputs[2] = emptyList()

        val viewModel = opened()

        assertThat(viewModel.state.value.sortedWorkflows).containsExactly(lint, release).inOrder()
        assertThat(viewModel.state.value.canStartByHand(lint)).isTrue()
        assertThat(viewModel.state.value.canStartByHand(release)).isFalse()
    }

    @Test
    fun a_workflow_read_ahead_opens_without_asking_again() = test {
        api.inputs[1] = listOf(channel)
        val viewModel = opened()

        viewModel.select(release)
        advanceUntilIdle()

        assertThat(viewModel.state.value.values).containsExactly("channel", "stable")
        assertThat(api.calls.count { it.startsWith("inputs:octo/repo:1@") }).isEqualTo(1)
    }

    @Test
    fun a_workflow_brings_its_inputs_with_their_defaults() = test {
        api.inputs[1] = listOf(channel, notes, dryRun)
        val viewModel = opened()

        viewModel.select(release)
        advanceUntilIdle()

        assertThat(viewModel.state.value.values).containsExactly("channel", "stable", "dry_run", "false")
        assertThat(viewModel.state.value.canStart).isTrue()
        assertThat(api.calls).contains("inputs:octo/repo:1@main")
    }

    @Test
    fun starting_sends_the_ref_and_filled_inputs_only() = test {
        api.inputs[1] = listOf(channel, notes, dryRun)
        val viewModel = opened()
        viewModel.select(release)
        advanceUntilIdle()

        viewModel.setValue("channel", "beta")
        viewModel.setRef("release/2.0")
        viewModel.start()
        advanceUntilIdle()

        assertThat(api.calls.last()).isEqualTo("dispatch:octo/repo:1@release/2.0:{channel=beta, dry_run=false}")
        assertThat(viewModel.state.value.started).isTrue()
    }

    @Test
    fun a_required_input_left_empty_blocks_the_start() = test {
        api.inputs[1] = listOf(tag)
        val viewModel = opened()
        viewModel.select(release)
        advanceUntilIdle()

        assertThat(viewModel.state.value.missing).containsExactly("tag")
        viewModel.start()
        advanceUntilIdle()
        assertThat(api.calls.none { it.startsWith("dispatch:") }).isTrue()

        viewModel.setValue("tag", "v2.0")
        assertThat(viewModel.state.value.canStart).isTrue()
    }

    @Test
    fun a_workflow_without_a_manual_trigger_cannot_start() = test {
        api.inputs[2] = null
        val viewModel = opened()

        viewModel.select(lint)
        advanceUntilIdle()

        assertThat(viewModel.state.value.inputs).isEqualTo(Loadable.Loaded(null))
        assertThat(viewModel.state.value.canStart).isFalse()
    }

    @Test
    fun a_refused_start_says_why_and_stays_open() = test {
        api.writeFailure = ForgeError.Http(403, "Resource not accessible by integration")
        val viewModel = opened()
        viewModel.select(lint)
        advanceUntilIdle()

        viewModel.start()
        advanceUntilIdle()

        assertThat(viewModel.state.value.sendError).isEqualTo(ForgeError.Http(403, "Resource not accessible by integration"))
        assertThat(viewModel.state.value.started).isFalse()
        assertThat(viewModel.state.value.sending).isFalse()
    }

    @Test
    fun reopening_starts_afresh() = test {
        val viewModel = opened()
        viewModel.select(lint)
        viewModel.setRef("dev")
        advanceUntilIdle()

        viewModel.open()

        assertThat(viewModel.state.value.selected).isNull()
        assertThat(viewModel.state.value.ref).isEqualTo("main")
    }
}
