package fr.arthurbrugiere.forgeline.core.forge

import fr.arthurbrugiere.forgeline.core.model.LogFormat
import fr.arthurbrugiere.forgeline.core.model.LogStep
import fr.arthurbrugiere.forgeline.core.model.RunConclusion
import java.io.InputStream

/**
 * Splits bytes into lines as they come, telling where each line starts. Only `\n` ends a line: GitLab writes `\r` inside
 * its section lines. Holds one line at a time, never the log.
 */
class LineSplitter(start: Long = 0, private val emit: (start: Long, line: String) -> Unit) {
    private var offset = start
    private var lineStart = start
    private var line = ByteArray(256)
    private var length = 0

    fun feed(bytes: ByteArray, from: Int = 0, to: Int = bytes.size) {
        for (i in from until to) {
            val byte = bytes[i]
            offset++
            if (byte == '\n'.code.toByte()) {
                flush()
                lineStart = offset
            } else {
                if (length == line.size) line = line.copyOf(line.size * 2)
                line[length++] = byte
            }
        }
    }

    /** Ends the last line, which a log may leave without its newline. */
    fun finish() {
        if (length > 0) flush()
        lineStart = offset
    }

    private fun flush() {
        val end = if (length > 0 && line[length - 1] == '\r'.code.toByte()) length - 1 else length
        emit(lineStart, String(line, 0, end, Charsets.UTF_8))
        length = 0
    }
}

/** Where the steps of a job's log begin, read from the log itself: Forgejo's and GitLab's APIs don't list steps. */
object JobLogSteps {
    /** Reads [input] once, a line at a time, into its steps: where each is, how many lines and errors it has. */
    fun index(input: InputStream, format: LogFormat): List<LogStep> {
        val steps = mutableListOf<LogStep>()
        var current = LogStep(title = null, start = 0, end = 0, lines = 0, errors = 0)
        var depth = 0
        val splitter = LineSplitter { start, raw ->
            val content = ActionsLogParser.contentOf(raw)
            val opens = when (format) {
                LogFormat.GITLAB -> GitLabSections.read(raw, depth).also { depth = it.depth }.opens
                else -> opening(content, format)
            }
            if (opens != null) {
                // What came before the first step stays only when there was any.
                if (current.title != null || current.lines!! > 0) steps += current.copy(end = start)
                current = LogStep(title = opens, start = start, end = start, lines = 0, errors = 0)
            }
            current = current.copy(
                lines = current.lines!! + 1,
                errors = current.errors!! + if (isError(content, format)) 1 else 0,
                conclusion = outcome(content, format) ?: current.conclusion,
            )
        }
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            splitter.feed(buffer, 0, read)
            total += read
        }
        splitter.finish()
        if (current.title != null || current.lines!! > 0) steps += current.copy(end = total)
        return steps
    }

    /** The title of the step [content] (a line without its time) opens, or null when it opens none. */
    fun opening(content: String, format: LogFormat): String? = when (format) {
        LogFormat.GITHUB -> when {
            content.startsWith("##[group]Run ") -> content.removePrefix("##[group]")
            content == "Post job cleanup." -> "Post job cleanup"
            content == "Cleaning up orphan processes" -> "Complete job"
            else -> null
        }
        LogFormat.FORGEJO -> content.takeIf { it.startsWith("⭐ Run ") }?.removePrefix("⭐ Run ")
        LogFormat.GITLAB -> GitLabSections.read(content, 0).opens
    }

    private fun isError(content: String, format: LogFormat) = when (format) {
        LogFormat.GITLAB -> stripAnsi(content).startsWith("ERROR: ")
        else -> ActionsLogParser.isError(content)
    }

    /** How a step ended, as Forgejo's runner says it after the step. */
    private fun outcome(content: String, format: LogFormat): RunConclusion? {
        if (format != LogFormat.FORGEJO) return null
        val said = content.trim()
        return when {
            said.startsWith("✅") && "Success - " in said -> RunConclusion.SUCCESS
            said.startsWith("❌") && "Failure - " in said -> RunConclusion.FAILURE
            else -> null
        }
    }

    private val ansi = Regex("""\u001B\[[0-9;]*[A-Za-z]""")

    internal fun stripAnsi(text: String) = ansi.replace(text, "")
}

/**
 * GitLab's sections: `section_start:<time>:<name>[options]\r\e[0K<header>` opens one, `section_end:<time>:<name>\r\e[0K`
 * closes it, several to a line at times. Only a section opened outside any other is a step.
 */
internal object GitLabSections {
    private val marker = Regex("""section_(start|end):\d+:([A-Za-z0-9_.\-]+)(?:\[[^\]]*])?\r?(?:\u001B\[0K)?""")

    data class Read(val depth: Int, val opens: String?)

    fun read(raw: String, depthBefore: Int): Read {
        var depth = depthBefore
        var opens: String? = null
        val markers = marker.findAll(raw).toList()
        markers.forEachIndexed { index, match ->
            if (match.groupValues[1] == "end") {
                depth = (depth - 1).coerceAtLeast(0)
            } else {
                if (depth == 0 && opens == null) {
                    val headerEnd = markers.getOrNull(index + 1)?.range?.first ?: raw.length
                    val header = JobLogSteps.stripAnsi(raw.substring(match.range.last + 1, headerEnd)).trim()
                    opens = header.ifEmpty { match.groupValues[2].replace('_', ' ') }
                }
                depth++
            }
        }
        return Read(depth, opens)
    }
}
