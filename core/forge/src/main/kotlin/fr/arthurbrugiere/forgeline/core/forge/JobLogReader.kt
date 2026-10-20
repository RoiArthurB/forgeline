package fr.arthurbrugiere.forgeline.core.forge

import fr.arthurbrugiere.forgeline.core.model.LogPart

/**
 * A finished job's log, opened to be read a part at a time: a step of a 60,000-line log is read without the rest.
 * [fetch] reads bytes within the log; asking past its end answers nothing rather than an error.
 */
class JobLogReader(
    /** How many bytes the log has. */
    val size: Long,
    private val fetch: suspend (LongRange) -> ForgeResult<LogPart>,
) {
    /** Bytes [range] of the log, cut at its end. */
    suspend fun read(range: LongRange): ForgeResult<LogPart> {
        if (range.isEmpty() || range.first >= size) return ForgeResult.Success(LogPart(ByteArray(0), range.first.coerceAtMost(size), size))
        return fetch(range.first.coerceAtLeast(0)..minOf(range.last, size - 1))
    }
}
