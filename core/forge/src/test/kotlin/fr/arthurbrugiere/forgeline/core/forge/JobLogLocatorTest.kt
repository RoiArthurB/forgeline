package fr.arthurbrugiere.forgeline.core.forge

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.model.LogEntry
import fr.arthurbrugiere.forgeline.core.model.LogFormat
import fr.arthurbrugiere.forgeline.core.model.LogPart
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant

class JobLogLocatorTest {
    private fun bytes(name: String) = requireNotNull(javaClass.getResource("/logs/$name")) { name }.readBytes()

    /** Reads [log] as a forge would, counting what is asked of it. */
    private class Storage(val log: ByteArray) {
        var reads = 0
        var bytesRead = 0L
        val reader = JobLogReader(log.size.toLong()) { range ->
            reads++
            bytesRead += range.last - range.first + 1
            ForgeResult.Success(LogPart(log.copyOfRange(range.first.toInt(), range.last.toInt() + 1), range.first, log.size.toLong()))
        }
    }

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    @Test
    fun steps_are_found_from_the_seconds_the_forge_tells() = runTest {
        // This repository's CI job of 2026-10-17, and when GitHub says its steps started (Upload reports was skipped).
        val log = bytes("github_ci_job.txt")
        val ran = listOf("02:36:20", "02:36:23", "02:36:23", "02:36:38", "02:40:17", "02:40:29", "02:40:29", "02:40:29")
            .map { Instant.parse("2026-10-17T${it}Z") }

        val found = JobLogLocator(Storage(log).reader, LogFormat.GITHUB, partBytes = 2048).locate(ran).value()

        // Where the log's own opening lines are: three steps share 02:40:29, and the step before still wrote in it.
        val expected = JobLogSteps.index(log.inputStream(), LogFormat.GITHUB).drop(1).map { it.start }
        assertThat(found).containsExactlyElementsIn(expected).inOrder()
    }

    @Test
    fun a_long_log_is_searched_reading_a_little_of_it() = runTest {
        val log = longLog()
        val storage = Storage(log)
        val starts = listOf("10:00:01", "10:03:00", "10:03:00").map { Instant.parse("2026-10-17T${it}Z") }

        val found = JobLogLocator(storage.reader, LogFormat.GITHUB).locate(starts).value()

        assertThat(found).containsExactlyElementsIn(JobLogSteps.index(log.inputStream(), LogFormat.GITHUB).drop(1).map { it.start }).inOrder()
        assertThat(log.size).isGreaterThan(5_000_000)
        // A few dozen small parts of a 5 MB log.
        assertThat(storage.reads).isLessThan(80)
        assertThat(storage.bytesRead).isLessThan(log.size / 4L)
    }

    @Test
    fun a_log_without_times_cannot_be_searched() = runTest {
        val log = (1..5_000).joinToString("\n") { "line $it" }.toByteArray()

        val result = JobLogLocator(Storage(log).reader, LogFormat.GITHUB, partBytes = 1024).locate(listOf(Instant.parse("2026-10-17T10:00:00Z")))

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Unreadable))
    }

    @Test
    fun a_step_is_read_from_its_end_back_without_losing_or_repeating_a_line() = runTest {
        val log = bytes("github_ci_job.txt")
        val reader = Storage(log).reader
        val check = JobLogSteps.index(log.inputStream(), LogFormat.GITHUB)[4]
        assertThat(check.lines).isGreaterThan(JobLogWindow.LINES)

        val last = JobLogWindow.before(reader, check, check.end, LogFormat.GITHUB).value()
        val earlier = JobLogWindow.before(reader, check, last.start, LogFormat.GITHUB).value()

        fun texts(entries: List<LogEntry>) = entries.flatMap { if (it is LogEntry.Group) listOf(it.title) + it.lines.map { line -> line.text } else listOf((it as LogEntry.Line).text) }
        val whole = ActionsLogParser.parse(String(log.copyOfRange(check.start.toInt(), check.end.toInt()))).entries
        assertThat(earlier.start).isEqualTo(check.start)
        assertThat(texts(earlier.entries) + texts(last.entries)).containsExactlyElementsIn(texts(whole)).inOrder()
        assertThat(texts(last.entries).last()).isEqualTo("Configuration cache entry stored.")
    }

    @Test
    fun a_gitlab_step_reads_without_its_lines_stamps_and_with_its_split_lines_joined() = runTest {
        // Regression: each line showed its stream ("00O "), and a section's header stood apart from it.
        val log = bytes("gitlab_failed_job.txt")
        val prepare = JobLogSteps.index(log.inputStream(), LogFormat.GITLAB)[1]

        val read = JobLogWindow.before(Storage(log).reader, prepare, prepare.end, LogFormat.GITLAB).value()

        val texts = read.entries.map { JobLogSteps.stripAnsi((it as LogEntry.Line).text) }
        assertThat(texts.first()).isEqualTo("Preparing the \"docker\" executor")
        assertThat(texts[1]).isEqualTo("Using Docker executor with image briar/ci-image-android-emulator:trixie ...")
        assertThat(texts).hasSize(5)
        // As the log counts them: the header's line is still one of the step's.
        assertThat(read.lines).isEqualTo(prepare.lines)
    }

    /** 60,000 lines: a short setup, a three-minute build, and two post steps in the build's last second. */
    private fun longLog(): ByteArray {
        val out = StringBuilder()
        fun line(time: String, text: String) = out.append("2026-10-17T").append(time).append("Z ").append(text).append('\n')
        repeat(30) { line("10:00:00.${"%07d".format(it)}", "setting up $it") }
        line("10:00:01.1000000", "##[group]Run ./gradlew build")
        line("10:00:01.1000001", "##[endgroup]")
        for (i in 0 until 60_000) {
            val millis = 1_200 + i * 3L
            val time = "10:%02d:%02d.%07d".format(millis / 60_000, millis / 1_000 % 60, millis % 1_000 * 10_000)
            line(time, "Task :module$i:compileKotlin UP-TO-DATE, a line long enough to look like a build's")
        }
        line("10:03:00.5000000", "Post job cleanup.")
        line("10:03:00.6000000", "Post job cleanup.")
        return out.toString().toByteArray()
    }
}
