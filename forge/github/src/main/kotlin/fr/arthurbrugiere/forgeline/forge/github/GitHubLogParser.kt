package fr.arthurbrugiere.forgeline.forge.github

import fr.arthurbrugiere.forgeline.core.model.JobLog
import fr.arthurbrugiere.forgeline.core.model.LogEntry
import fr.arthurbrugiere.forgeline.core.model.LogLineKind

/**
 * Reads a GitHub Actions job log: every line starts with a timestamp, `##[group]`/`##[endgroup]` fold lines, and
 * `##[error]`-style markers set a line's kind. A line without a timestamp continues the one before (a multi-line
 * error), so it keeps its kind. ANSI color codes are left in the text.
 */
internal object GitHubLogParser {
    private val timestamp = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z ?""")
    private val markers = mapOf(
        "##[error]" to LogLineKind.ERROR,
        "##[warning]" to LogLineKind.WARNING,
        "##[notice]" to LogLineKind.NOTICE,
        "##[debug]" to LogLineKind.DEBUG,
        "##[command]" to LogLineKind.COMMAND,
        "[command]" to LogLineKind.COMMAND,
    )

    fun parse(text: String): JobLog {
        val entries = mutableListOf<LogEntry>()
        var groupTitle: String? = null
        val groupLines = mutableListOf<LogEntry.Line>()
        var lastKind = LogLineKind.PLAIN

        fun closeGroup() {
            groupTitle?.let { entries += LogEntry.Group(it, groupLines.toList()) }
            groupTitle = null
            groupLines.clear()
        }

        for (raw in text.removePrefix("﻿").lines()) {
            val stamped = timestamp.find(raw)
            val content = if (stamped != null) raw.substring(stamped.range.last + 1) else raw
            when {
                content.startsWith("##[group]") -> {
                    closeGroup()
                    groupTitle = content.removePrefix("##[group]")
                    lastKind = LogLineKind.PLAIN
                }
                content.startsWith("##[endgroup]") -> {
                    closeGroup()
                    lastKind = LogLineKind.PLAIN
                }
                else -> {
                    val marker = markers.entries.firstOrNull { content.startsWith(it.key) }
                    val kind = marker?.value ?: if (stamped == null) lastKind else LogLineKind.PLAIN
                    val line = LogEntry.Line(marker?.let { content.removePrefix(it.key) } ?: content, kind)
                    lastKind = kind
                    if (groupTitle != null) groupLines += line else entries += line
                }
            }
        }
        closeGroup()
        // The file ends with a newline: drop the empty line it leaves.
        if ((entries.lastOrNull() as? LogEntry.Line)?.text?.isEmpty() == true) entries.removeAt(entries.lastIndex)
        return JobLog(entries)
    }
}
