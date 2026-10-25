package fr.arthurbrugiere.forgeline.core.forge

import fr.arthurbrugiere.forgeline.core.model.LogEntry
import fr.arthurbrugiere.forgeline.core.model.LogFormat
import fr.arthurbrugiere.forgeline.core.model.LogStep

/** Some of a step's lines, read back from a point: the earliest of them begins at [start]; [lines] as the log counts them. */
data class StepLines(val entries: List<LogEntry>, val start: Long, val lines: Int)

/** A step's lines read a window at a time, from its end back: where a failure and its summary are. */
object JobLogWindow {
    /** How many lines a window holds at most, and how many bytes are read for it. */
    const val LINES = 300
    const val BYTES = 96 * 1024

    /** The last [LINES] lines of [step] before byte [until] (its end, for the first window). */
    suspend fun before(reader: JobLogReader, step: LogStep, until: Long, format: LogFormat): ForgeResult<StepLines> {
        if (until <= step.start) return ForgeResult.Success(StepLines(emptyList(), step.start, 0))
        val from = maxOf(step.start, until - BYTES)
        val part = when (val read = reader.read(from until until)) {
            is ForgeResult.Failure -> return read
            is ForgeResult.Success -> read.value
        }
        val lines = mutableListOf<Pair<Long, String>>()
        LineSplitter(part.start) { start, raw -> lines += start to raw }.apply {
            feed(part.bytes)
            finish()
        }
        // A window that starts inside the step starts mid-line: that line is read with the next window.
        val whole = if (from > step.start && lines.size > 1) lines.drop(1) else lines
        val kept = whole.takeLast(LINES)
        val text = if (format == LogFormat.GITLAB) gitLabText(kept.map { it.second }) else kept.joinToString("\n") { it.second }
        return ForgeResult.Success(StepLines(ActionsLogParser.parse(text).entries, kept.firstOrNull()?.first ?: until, kept.size))
    }

    private val sectionMarker = Regex("""section_(?:start|end):\d+:[A-Za-z0-9_.\-]+(?:\[[^\]]*])?\r?(?:\u001B\[0K)?""")

    /** GitLab's section markers are for folding, not reading: what's left of the line is. */
    private fun withoutSectionMarkers(raw: String) = sectionMarker.replace(raw, "").replace("\r", "").replace("\u001B[0K", "")

    /** GitLab's lines to read: without their stamps, each line the runner cut put back together. */
    private fun gitLabText(raws: List<String>): String {
        val lines = mutableListOf<String>()
        for (raw in raws) {
            val line = GitLabLines.read(raw)
            val text = withoutSectionMarkers(line.text)
            if (line.continues && lines.isNotEmpty()) lines[lines.lastIndex] += text else lines += text
        }
        return lines.joinToString("\n")
    }
}
