package fr.arthurbrugiere.forgeline.core.forge

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.model.LogFormat
import fr.arthurbrugiere.forgeline.core.model.RunConclusion
import org.junit.Test

/**
 * Fixtures are real logs: this repository's own CI job of 2026-10-17 (github_ci_job), a failed paperclipai/paperclip
 * job (github_failed_job), a failed Codeberg one (forgejo_failed_job) and a timed-out fdroid/fdroidclient job on
 * gitlab.com of 2026-10-23, its first 60 and last 15 lines (gitlab_failed_job).
 */
class JobLogStepsTest {
    private fun bytes(name: String) = requireNotNull(javaClass.getResource("/logs/$name")) { name }.readBytes()

    private fun index(text: String, format: LogFormat) = JobLogSteps.index(text.byteInputStream(), format)

    @Test
    fun lines_are_told_where_they_start_in_bytes_whatever_their_characters() {
        val lines = mutableListOf<Pair<Long, String>>()
        LineSplitter { start, line -> lines += start to line }.apply {
            feed("⭐ a\r\nb\nlast".toByteArray())
            finish()
        }

        // The star is three bytes; a line's \r goes with its \n, and the last line needs no newline.
        assertThat(lines).containsExactly(0L to "⭐ a", 7L to "b", 9L to "last").inOrder()
    }

    @Test
    fun a_github_log_is_read_into_the_steps_that_ran() {
        val log = bytes("github_ci_job.txt")
        val steps = JobLogSteps.index(log.inputStream(), LogFormat.GITHUB)

        // Set up job, checkout, setup-java, setup-gradle, the check, three post steps and Complete job: the skipped
        // upload wrote nothing.
        assertThat(steps.map { it.title }).containsExactly(
            null,
            "Run actions/checkout@v7",
            "Run actions/setup-java@v6",
            "Run gradle/actions/setup-gradle@v6",
            "Run ./gradlew testDebugUnitTest verifyRoborazziDebug lintDebug assembleDebug --continue",
            "Post job cleanup",
            "Post job cleanup",
            "Post job cleanup",
            "Complete job",
        ).inOrder()
        // One after the other, over the whole log.
        assertThat(steps.first().start).isEqualTo(0)
        assertThat(steps.zipWithNext().all { (a, b) -> a.end == b.start }).isTrue()
        assertThat(steps.last().end).isEqualTo(log.size.toLong())
        assertThat(steps.sumOf { it.lines!! }).isEqualTo(String(log).trimEnd('\n').lines().size)
        val check = String(log.copyOfRange(steps[4].start.toInt(), steps[4].end.toInt()))
        assertThat(check).contains("BUILD SUCCESSFUL in 3m 37s")
        assertThat(check).doesNotContain("Post job cleanup.")
    }

    @Test
    fun a_failed_github_step_counts_its_errors() {
        val steps = JobLogSteps.index(bytes("github_failed_job.txt").inputStream(), LogFormat.GITHUB)

        val failing = steps.single { it.errors!! > 0 }
        assertThat(failing.title).startsWith("Run pnpm --filter @paperclipai/paperclip-runner")
        assertThat(failing.errors).isEqualTo(2)
    }

    @Test
    fun a_forgejo_log_names_its_steps_and_says_how_each_ended() {
        val steps = JobLogSteps.index(bytes("forgejo_failed_job.txt").inputStream(), LogFormat.FORGEJO)

        assertThat(steps.first().title).isNull()
        assertThat(steps.drop(1).map { it.title }).containsExactly(
            "Pre run typos",
            "Main https://data.forgejo.org/actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1",
            "Post run typos",
        ).inOrder()
        assertThat(steps.drop(1).map { it.conclusion }).containsExactly(RunConclusion.SUCCESS, null, RunConclusion.SUCCESS).inOrder()
    }

    @Test
    fun a_gitlab_trace_is_read_into_its_outer_sections() {
        val esc = "\u001B"
        val trace = listOf(
            "$esc[0KRunning with gitlab-runner 16.0",
            "section_start:1700000000:prepare_executor\r$esc[0K$esc[0K$esc[36;1mPreparing the \"docker\" executor$esc[0;m",
            "Using Docker executor",
            "section_end:1700000005:prepare_executor\r$esc[0K",
            "section_start:1700000006:step_script[collapsed=true]\r$esc[0K",
            "section_start:1700000007:inner\r$esc[0K$esc[0KNested, not a step",
            "$ make test",
            "section_end:1700000008:inner\r$esc[0K",
            "$esc[31;1mERROR: Job failed: exit code 1$esc[0;m",
            "section_end:1700000009:step_script\r$esc[0Ksection_start:1700000009:cleanup_file_variables\r$esc[0KCleaning up project directory",
        ).joinToString("\n")

        val steps = index(trace, LogFormat.GITLAB)

        // The header GitLab shows, or the section's name when it shows none; a section within another isn't a step.
        assertThat(steps.map { it.title }).containsExactly(null, "Preparing the \"docker\" executor", "step script", "Cleaning up project directory").inOrder()
        assertThat(steps.map { it.errors }).containsExactly(0, 0, 1, 0).inOrder()
    }

    @Test
    fun a_gitlab_trace_stamped_line_by_line_is_read_into_its_sections_by_their_headers() {
        // Regression: gitlab.com's runners stamp each line "<time> 00O " and write a section's header on the next line,
        // marked "+"; the steps were named by their sections' names and the job's failure went uncounted.
        val steps = JobLogSteps.index(bytes("gitlab_failed_job.txt").inputStream(), LogFormat.GITLAB)

        assertThat(steps.map { it.title }).containsExactly(
            null, "Preparing the \"docker\" executor", "Preparing environment", "Getting source from Git repository",
            "Downloading artifacts", "Executing \"step_script\" stage of the job script",
        ).inOrder()
        assertThat(steps.last().errors).isEqualTo(1)
        assertThat(steps.sumOf { it.lines!! }).isEqualTo(75)
    }
}
