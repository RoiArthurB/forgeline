package fr.arthurbrugiere.forgeline.core.forge

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.model.LogEntry
import fr.arthurbrugiere.forgeline.core.model.LogLineKind
import org.junit.Test

/** Fixtures are real logs: a failed GitHub job of paperclipai/paperclip (2026-09-29) and a Codeberg one (2026-09-25). */
class ActionsLogParserTest {
    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/logs/$name")) { name }.readText()

    private fun lines(entries: List<LogEntry>) = entries.flatMap { if (it is LogEntry.Group) it.lines else listOf(it as LogEntry.Line) }

    @Test
    fun a_github_log_folds_groups_and_marks_errors() {
        val log = ActionsLogParser.parse(fixture("github_failed_job.txt"))

        val groups = log.entries.filterIsInstance<LogEntry.Group>()
        assertThat(groups.first().title).isEqualTo("Runner Image Provisioner")
        assertThat(groups.first().lines.first().text).isEqualTo("Hosted Compute Agent")
        val errors = log.entries.filterIsInstance<LogEntry.Line>().filter { it.kind == LogLineKind.ERROR }
        assertThat(errors.first().text).startsWith("NativeSessionCloseUnrecoverableError")
        // An error's following lines, which carry no timestamp, belong to it.
        assertThat(errors.any { it.text.contains("runnerd-codex-transport.ts:4314") }).isTrue()
        assertThat(errors.last().text).isEqualTo("Process completed with exit code 1.")
        assertThat(log.entries.filterIsInstance<LogEntry.Line>().any { it.kind == LogLineKind.COMMAND && it.text == "/usr/bin/git version" }).isTrue()
        // Timestamps are gone; ANSI colors stay for the viewer.
        assertThat(lines(log.entries).none { it.text.startsWith("2026-") }).isTrue()
    }

    @Test
    fun a_forgejo_log_folds_groups_and_keeps_the_runner_s_own_lines() {
        val log = ActionsLogParser.parse(fixture("forgejo_failed_job.txt"))

        assertThat(log.entries.filterIsInstance<LogEntry.Group>().map { it.title }).contains("Getting Git version info")
        val texts = lines(log.entries).map { it.text }
        assertThat(texts).contains("🏁  Job failed")
        assertThat(texts.none { it.startsWith("2026-") }).isTrue()
    }

    @Test
    fun a_part_that_starts_inside_a_group_reads_as_plain_lines_until_it_closes() {
        // A window of a step can begin past its group's opening line.
        val log = ActionsLogParser.parse("2026-10-17T02:36:20.1Z   token: ***\n2026-10-17T02:36:20.2Z ##[endgroup]\n2026-10-17T02:36:20.3Z Syncing repository\n")

        assertThat(log.entries).containsExactly(LogEntry.Line("  token: ***"), LogEntry.Line("Syncing repository")).inOrder()
    }
}
