package fr.arthurbrugiere.forgeline.actions

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.actions.DefaultActionsRepository
import fr.arthurbrugiere.forgeline.core.data.actions.DefaultJobLogRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.LogFormat
import fr.arthurbrugiere.forgeline.core.model.LogLineKind
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RunConclusion
import fr.arthurbrugiere.forgeline.core.model.RunJob
import fr.arthurbrugiere.forgeline.core.model.RunStatus
import fr.arthurbrugiere.forgeline.core.model.RunStep
import fr.arthurbrugiere.forgeline.core.model.UserSettings
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeActionsApi
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import fr.arthurbrugiere.forgeline.core.testing.FakeUserSettingsRepository
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import fr.arthurbrugiere.forgeline.repo.Loadable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class JobLogViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    @get:Rule
    val folder = TemporaryFolder()

    private val api = FakeActionsApi()
    private val accounts = FakeAccountRepository()
    private val repo = RepoId("octo", "repo")
    private val settings = FakeUserSettingsRepository()

    private fun at(time: String) = Instant.parse("2026-10-17T${time}Z")

    /** A failed GitHub job: setup, 400 lines of tests ending in errors, then the cleanup. */
    private val log = buildString {
        fun line(time: String, text: String) = append("2026-10-17T10:00:").append(time).append("Z ").append(text).append('\n')
        line("00.1000000", "Current runner version: '2.337.0'")
        line("01.1000000", "##[group]Run pnpm test")
        line("01.1000001", "pnpm test")
        line("01.1000002", "##[endgroup]")
        repeat(400) { line("02.%07d".format(it), "test $it passed") }
        line("04.0000000", "##[group]Error details")
        line("04.0000001", "##[error]Tests failed")
        line("04.0000002", "##[endgroup]")
        line("04.1000000", "##[error]Process completed with exit code 1.")
        line("05.1000000", "Post job cleanup.")
        line("05.2000000", "Cleaning up orphan processes")
    }

    private fun job(status: RunStatus, conclusion: RunConclusion? = null) = RunJob(
        3, "test", status, conclusion, at("10:00:00"), if (status == RunStatus.COMPLETED) at("10:00:06") else null,
        listOf(
            RunStep(1, "Set up job", RunStatus.COMPLETED, RunConclusion.SUCCESS, at("10:00:00"), at("10:00:01")),
            RunStep(2, "Run tests", status, conclusion, at("10:00:01"), if (status == RunStatus.COMPLETED) at("10:00:05") else null),
            RunStep(3, "Post Run actions/checkout@v7", status, conclusion?.let { RunConclusion.SUCCESS }, at("10:00:05"), at("10:00:05")),
            RunStep(4, "Complete job", status, conclusion?.let { RunConclusion.SUCCESS }, at("10:00:05"), at("10:00:06")),
        ),
    )

    private fun test(block: suspend TestScope.() -> Unit) = runTest(mainDispatcherRule.testDispatcher) { block() }

    private fun viewModel(repo: RepoId = this.repo) = FakeForgeClients(actions = api).also { it.put(ForgeInstance.Codeberg, FakeForgeClients(actions = api)) }.let { clients ->
        JobLogViewModel(repo, 1, 3, "test", DefaultActionsRepository(clients, accounts), DefaultJobLogRepository(clients, accounts, folder.root, mainDispatcherRule.testDispatcher), settings)
    }

    private suspend fun signedInWithFailedJob() {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "ghp_token")
        api.jobs[1] = listOf(job(RunStatus.COMPLETED, RunConclusion.FAILURE))
        api.logs[3] = log
    }

    private fun JobLogViewModel.lines() = state.value.rows.filterIsInstance<LogRow.Line>()

    @Test
    fun steps_come_folded_with_none_of_their_lines() = test {
        signedInWithFailedJob()

        val viewModel = viewModel()
        advanceUntilIdle()

        val rows = viewModel.state.value.rows
        assertThat(rows.map { (it as LogRow.Step).logStep.title }).containsExactly("Set up job", "Run tests", "Post Run actions/checkout@v7", "Complete job").inOrder()
        assertThat(rows.none { (it as LogRow.Step).open }).isTrue()
        // How long each took, as the forge says.
        assertThat((rows[1] as LogRow.Step).took).isEqualTo(java.time.Duration.ofSeconds(4))
        assertThat(viewModel.state.value.failedStep).isEqualTo(1)
    }

    @Test
    fun an_opened_step_shows_its_last_lines_with_its_errors_unfolded() = test {
        signedInWithFailedJob()
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.toggleStep(1)
        advanceUntilIdle()

        val lines = viewModel.lines()
        assertThat(lines.all { it.step == 1 }).isTrue()
        assertThat(lines.last().line.text).isEqualTo("Process completed with exit code 1.")
        // The group holding an error is open; the step's command is folded.
        assertThat(lines.map { it.line.text }).contains("Tests failed")
        assertThat(viewModel.state.value.rows.filterIsInstance<LogRow.Group>().map { it.title to it.open }).contains("Error details" to true)
        assertThat(viewModel.state.value.errorRows).hasSize(2)
        // Its last 300 lines: the step has more above them.
        assertThat(viewModel.state.value.rows.filterIsInstance<LogRow.Earlier>()).hasSize(1)
        assertThat(lines.first().line.text).isNotEqualTo("test 0 passed")
    }

    @Test
    fun earlier_lines_come_when_asked_and_a_folded_step_keeps_what_was_read() = test {
        signedInWithFailedJob()
        // Read whole, the log tells how many lines each step has.
        api.logsInParts = false
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.toggleStep(1)
        advanceUntilIdle()
        val earlier = viewModel.state.value.rows.filterIsInstance<LogRow.Earlier>().single()
        // The command, its 400 lines and the error: 407, of which 300 are read.
        assertThat(earlier.lines).isEqualTo(407 - 300)

        viewModel.loadEarlier(1)
        advanceUntilIdle()

        assertThat(viewModel.state.value.rows.filterIsInstance<LogRow.Earlier>()).isEmpty()
        assertThat(viewModel.lines().map { it.line.text }).containsAtLeast("test 0 passed", "test 399 passed", "Process completed with exit code 1.").inOrder()
        // The error group read first is still open below what came after.
        assertThat(viewModel.state.value.rows.filterIsInstance<LogRow.Group>().single { it.title == "Error details" }.open).isTrue()

        viewModel.toggleStep(1)
        viewModel.toggleStep(1)
        val read = api.bytesRead
        advanceUntilIdle()
        assertThat(api.bytesRead).isEqualTo(read)
        assertThat(viewModel.lines().map { it.line.text }).contains("test 0 passed")
    }

    @Test
    fun the_step_the_job_failed_at_opens_from_the_header() = test {
        signedInWithFailedJob()
        val viewModel = viewModel()
        advanceUntilIdle()

        assertThat(viewModel.openFailed()).isEqualTo(1)
        advanceUntilIdle()

        assertThat(viewModel.state.value.opened).containsExactly(1)
        assertThat(viewModel.lines()).isNotEmpty()
    }

    @Test
    fun with_the_setting_on_a_failed_job_opens_on_its_failure_once() = test {
        signedInWithFailedJob()
        settings.update { it.copy(openFailedStep = true) }
        val viewModel = viewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.opened).containsExactly(1)
        assertThat(viewModel.lines().map { it.line.text }).contains("Process completed with exit code 1.")
        assertThat(viewModel.state.value.scrollToStep).isEqualTo(1)

        viewModel.scrolledToStep()
        assertThat(viewModel.state.value.scrollToStep).isNull()
    }

    @Test
    fun by_default_a_failed_job_opens_on_its_folded_steps() = test {
        signedInWithFailedJob()
        assertThat(UserSettings().openFailedStep).isFalse()
        val viewModel = viewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.opened).isEmpty()
        assertThat(viewModel.state.value.scrollToStep).isNull()
    }

    @Test
    fun with_the_setting_on_a_job_that_passed_opens_folded() = test {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "ghp_token")
        api.jobs[1] = listOf(job(RunStatus.COMPLETED, RunConclusion.SUCCESS))
        api.logs[3] = log
        settings.update { it.copy(openFailedStep = true) }
        val viewModel = viewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.opened).isEmpty()
    }

    @Test
    fun a_running_job_shows_its_steps_without_asking_for_a_log() = test {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "ghp_token")
        api.jobs[1] = listOf(job(RunStatus.IN_PROGRESS))

        val viewModel = viewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.isRunning).isTrue()
        assertThat(viewModel.state.value.isLive).isTrue()
        assertThat(viewModel.state.value.job?.steps?.get(1)?.name).isEqualTo("Run tests")
        assertThat(api.calls.none { it.startsWith("openLog") || it.startsWith("downloadLog") }).isTrue()
    }

    @Test
    fun once_the_job_finishes_its_steps_come() = test {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "ghp_token")
        api.jobs[1] = listOf(job(RunStatus.IN_PROGRESS))
        val viewModel = viewModel()
        advanceUntilIdle()

        api.jobs[1] = listOf(job(RunStatus.COMPLETED, RunConclusion.FAILURE))
        api.logs[3] = log
        viewModel.poll()
        advanceUntilIdle()

        assertThat(viewModel.state.value.isRunning).isFalse()
        assertThat((viewModel.state.value.steps as Loadable.Loaded).value).hasSize(4)
        assertThat(viewModel.state.value.isLive).isFalse()
    }

    @Test
    fun a_log_not_published_yet_is_asked_for_again_a_few_times() = test {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "ghp_token")
        api.jobs[1] = listOf(job(RunStatus.COMPLETED, RunConclusion.SUCCESS))
        val viewModel = viewModel()
        advanceUntilIdle()
        assertThat(viewModel.state.value.isLive).isTrue()

        repeat(MAX_LOG_ATTEMPTS) {
            viewModel.poll()
            advanceUntilIdle()
        }

        assertThat(viewModel.state.value.isLive).isFalse()
        assertThat(viewModel.state.value.steps).isEqualTo(Loadable.Failed(ForgeError.Http(404, "Not Found")))
    }

    @Test
    fun signed_out_the_log_is_unauthorized() = test {
        api.jobs[1] = listOf(job(RunStatus.COMPLETED, RunConclusion.FAILURE))
        api.logs[3] = log

        val viewModel = viewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.steps).isEqualTo(Loadable.Failed(ForgeError.Unauthorized))
    }

    @Test
    fun a_codeberg_job_s_public_log_is_read_signed_out_into_its_steps() = test {
        // Codeberg serves public logs to anyone, and its API lists no steps: they come from the log.
        val forgejo = FakeActionsApi(logFormat = LogFormat.FORGEJO).apply {
            logsNeedToken = false
            logsInParts = false
            jobs[1] = listOf(job(RunStatus.COMPLETED, RunConclusion.SUCCESS).copy(steps = emptyList()))
            logs[3] = "2026-09-25T22:07:12.5Z Runner received task\n2026-09-25T22:07:13.2Z ⭐ Run Main make\n2026-09-25T22:07:14.0Z   ✅  Success - Main make\n"
        }
        val codeberg = RepoId("forgejo", "website", ForgeInstance.Codeberg)
        val clients = FakeForgeClients().also { it.put(ForgeInstance.Codeberg, FakeForgeClients(actions = forgejo)) }
        val viewModel = JobLogViewModel(codeberg, 1, 3, "publish", DefaultActionsRepository(clients, accounts), DefaultJobLogRepository(clients, accounts, folder.root, mainDispatcherRule.testDispatcher), settings)
        advanceUntilIdle()

        val steps = (viewModel.state.value.steps as Loadable.Loaded).value
        assertThat(steps.map { it.title }).containsExactly(null, "Main make").inOrder()
        assertThat(steps.last().conclusion).isEqualTo(RunConclusion.SUCCESS)
        assertThat(forgejo.calls).contains("job:forgejo/website:1/3")
    }

    @Test
    fun a_job_its_forge_cant_list_still_has_its_log_read() = test {
        // Older Forgejo servers list runs but not their jobs.
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "ghp_token")
        api.logs[3] = log
        api.logsInParts = false

        val viewModel = viewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.job).isNull()
        assertThat((viewModel.state.value.steps as Loadable.Loaded).value.map { it.title }).contains("Run pnpm test")
        viewModel.toggleStep(1)
        advanceUntilIdle()
        assertThat(viewModel.lines().last().line.kind).isEqualTo(LogLineKind.ERROR)
    }
}
