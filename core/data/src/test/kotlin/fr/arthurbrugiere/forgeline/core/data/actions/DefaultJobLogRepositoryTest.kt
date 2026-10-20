package fr.arthurbrugiere.forgeline.core.data.actions

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.LogEntry
import fr.arthurbrugiere.forgeline.core.model.LogFormat
import fr.arthurbrugiere.forgeline.core.model.LogStep
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RunConclusion
import fr.arthurbrugiere.forgeline.core.model.RunJob
import fr.arthurbrugiere.forgeline.core.model.RunStatus
import fr.arthurbrugiere.forgeline.core.model.RunStep
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeActionsApi
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class DefaultJobLogRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val accounts = FakeAccountRepository()
    private val repo = RepoId("octo", "repo")

    private fun repository(api: FakeActionsApi, forge: ForgeInstance = ForgeInstance.GitHub) =
        DefaultJobLogRepository(FakeForgeClients(actions = api).apply { if (forge != ForgeInstance.GitHub) put(forge, FakeForgeClients(actions = api)) }, accounts, folder.root)

    private suspend fun signIn() = accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "ghp_token")

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    private fun at(time: String) = Instant.parse("2026-10-17T${time}Z")

    /** A GitHub job: setup, a build of 60,000 lines (3 MB) that fails, an upload skipped, and the cleanup in the build's last second. */
    private val buildLog = buildString {
        fun line(time: String, text: String) = append("2026-10-17T").append(time).append("Z ").append(text).append('\n')
        line("10:00:00.1000000", "Current runner version: '2.337.0'")
        line("10:00:01.2000000", "##[group]Run ./gradlew build")
        line("10:00:01.2000001", "##[endgroup]")
        for (i in 0 until 60_000) {
            val second = 2 + i / 500
            line("10:%02d:%02d.%07d".format(second / 60, second % 60, i % 500 * 10_000), "compiling module $i of the build")
        }
        line("10:02:23.4000000", "##[error]Process completed with exit code 1.")
        line("10:02:23.5000000", "Post job cleanup.")
        line("10:02:23.6000000", "Cleaning up orphan processes")
    }

    private val job = RunJob(
        id = 9, name = "build", status = RunStatus.COMPLETED, conclusion = RunConclusion.FAILURE, startedAt = at("10:00:00"), completedAt = at("10:02:24"),
        steps = listOf(
            RunStep(1, "Set up job", RunStatus.COMPLETED, RunConclusion.SUCCESS, at("10:00:00"), at("10:00:01")),
            RunStep(2, "Build", RunStatus.COMPLETED, RunConclusion.FAILURE, at("10:00:01"), at("10:02:23")),
            RunStep(3, "Upload reports", RunStatus.COMPLETED, RunConclusion.SKIPPED, null, null),
            RunStep(4, "Post Run actions/checkout@v7", RunStatus.COMPLETED, RunConclusion.SUCCESS, at("10:02:23"), at("10:02:23")),
            RunStep(5, "Complete job", RunStatus.COMPLETED, RunConclusion.SUCCESS, at("10:02:23"), at("10:02:23")),
        ),
    )

    @Test
    fun github_steps_are_found_and_read_in_parts_never_whole() = runTest {
        signIn()
        val api = FakeActionsApi().apply { logs[9] = buildLog }
        val repository = repository(api)

        val steps = repository.steps(repo, job).value()

        assertThat(steps.map { it.title }).containsExactly("Set up job", "Build", "Upload reports", "Post Run actions/checkout@v7", "Complete job").inOrder()
        assertThat(steps.map { it.conclusion }).containsExactly(RunConclusion.SUCCESS, RunConclusion.FAILURE, RunConclusion.SKIPPED, RunConclusion.SUCCESS, RunConclusion.SUCCESS).inOrder()
        // The skipped step wrote nothing.
        assertThat(steps[2].isEmpty).isTrue()
        val build = repository.lines(repo, job, steps[1], steps[1].end).value()
        assertThat((build.entries.last() as LogEntry.Line).text).isEqualTo("Process completed with exit code 1.")
        assertThat(build.entries).hasSize(300)
        assertThat(build.start).isGreaterThan(steps[1].start)
        assertThat(api.calls.none { it.startsWith("downloadLog") }).isTrue()
        assertThat(buildLog.length).isGreaterThan(2_000_000)
        assertThat(api.bytesRead).isLessThan(buildLog.length / 4L)
    }

    @Test
    fun a_small_log_is_read_in_one_part() = runTest {
        signIn()
        val small = buildLog.lines().take(200).joinToString("\n") + "\n" + buildLog.lines().takeLast(4).joinToString("\n")
        val api = FakeActionsApi().apply { logs[9] = small }

        val steps = repository(api).steps(repo, job).value()

        assertThat(steps.map { it.title }).containsExactly("Set up job", "Build", "Upload reports", "Post Run actions/checkout@v7", "Complete job").inOrder()
        assertThat(api.partsRead).isEqualTo(1)
    }

    @Test
    fun a_log_that_cant_be_read_in_parts_is_downloaded_once_and_kept() = runTest {
        signIn()
        val api = FakeActionsApi().apply { logs[9] = buildLog; logsInParts = false }

        val steps = repository(api).steps(repo, job).value()
        // Another launch: the log is still on the device.
        val again = repository(api).steps(repo, job).value()

        // The log's own "Post job cleanup" reads under the name GitHub's API gives the step.
        assertThat(steps.map { it.title }).containsExactly("Set up job", "Build", "Upload reports", "Post Run actions/checkout@v7", "Complete job").inOrder()
        assertThat(steps[1].errors).isEqualTo(1)
        assertThat(again).isEqualTo(steps)
        assertThat(api.calls.count { it.startsWith("downloadLog") }).isEqualTo(1)
    }

    @Test
    fun forgejo_steps_come_from_its_log() = runTest {
        val log = listOf(
            "2026-09-25T22:07:12.5194486Z Runner received task 9841581",
            "2026-09-25T22:07:13.2054308Z ⭐ Run Main actions/checkout@v4",
            "2026-09-25T22:07:13.3098588Z Syncing repository: forgejo/website",
            "2026-09-25T22:07:13.4000000Z   ✅  Success - Main actions/checkout@v4",
            "2026-09-25T22:07:14.2054308Z ⭐ Run Main make",
            "2026-09-25T22:13:22.4436603Z error: unexpected EOF",
            "2026-09-25T22:13:22.5190948Z   ❌  Failure - Main make",
        ).joinToString("\n")
        val api = FakeActionsApi(logFormat = LogFormat.FORGEJO).apply { logs[9] = log; logsNeedToken = false; logsInParts = false }
        val forgejo = RepoId("forgejo", "website", ForgeInstance.Codeberg)
        val noSteps = job.copy(steps = emptyList())

        val steps = repository(api, ForgeInstance.Codeberg).steps(forgejo, noSteps).value()

        assertThat(steps.map { it.title }).containsExactly(null, "Main actions/checkout@v4", "Main make").inOrder()
        assertThat(steps.map { it.conclusion }).containsExactly(null, RunConclusion.SUCCESS, RunConclusion.FAILURE).inOrder()
    }

    @Test
    fun signed_out_a_github_log_is_unauthorized_and_nothing_is_kept() = runTest {
        val api = FakeActionsApi().apply { logs[9] = buildLog }

        assertThat(repository(api).steps(repo, job)).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
        assertThat(folder.root.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun the_kept_logs_stay_small_the_least_recently_read_going_first() = runTest {
        signIn()
        val api = FakeActionsApi().apply { logsInParts = false; logs[1] = "a".repeat(1_000); logs[2] = "b".repeat(1_000); logs[3] = "c".repeat(1_000) }
        val repository = repository(api).apply { cacheBytes = 2_500 }

        listOf(1L, 2L, 3L).forEach { repository.steps(repo, job.copy(id = it, steps = emptyList())) }

        assertThat(folder.root.listFiles().orEmpty().map { it.length() }).containsExactly(1_000L, 1_000L)
        assertThat(folder.root.listFiles().orEmpty().map { it.readText().first() }).containsExactly('b', 'c')
    }

    @Test
    fun steps_the_api_lists_are_placed_in_its_order_even_when_skipped() {
        val placed = DefaultJobLogRepository.placed(job.steps, mapOf(1 to 0L, 2 to 50L, 4 to 900L, 5 to 950L), 1_000)

        assertThat(placed.map { it.start to it.end }).containsExactly(0L to 50L, 50L to 900L, 900L to 900L, 900L to 950L, 950L to 1_000L).inOrder()
        assertThat(placed[2]).isEqualTo(LogStep("Upload reports", 900, 900, RunConclusion.SKIPPED))
    }
}
