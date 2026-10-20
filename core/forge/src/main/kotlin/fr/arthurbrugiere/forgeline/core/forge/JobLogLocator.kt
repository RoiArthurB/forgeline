package fr.arthurbrugiere.forgeline.core.forge

import fr.arthurbrugiere.forgeline.core.model.LogFormat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import java.util.TreeMap

/**
 * Finds where each step of a stamped log begins (GitHub's: every line carries its time) from the times the forge gives
 * the steps, reading a few small parts of the log rather than all of it. Lines are written in time order, so each
 * round of parts read side by side narrows where a time falls by [ways]; a 10 MB log takes three or four rounds.
 *
 * The forge tells a step's start to the second, and the step before may still write within that second: a step starts
 * at its own opening line ([JobLogSteps.opening]) when there is one in that second.
 */
class JobLogLocator(
    private val reader: JobLogReader,
    private val format: LogFormat,
    private val partBytes: Int = 16 * 1024,
    private val ways: Int = 8,
) {
    /** Times of the line starts read so far: lines are in time order, so they bound every search. */
    private val known = TreeMap<Long, Instant>()

    /**
     * Where each step begins, for steps that started at [starts] (in their order, to the second). Unreadable when the
     * log doesn't let itself be searched (no time on its lines, a line longer than the parts read).
     */
    suspend fun locate(starts: List<Instant>): ForgeResult<List<Long>> {
        repeat(MAX_ROUNDS) {
            val wide = starts.distinct().filter { bounds(it).let { (lo, hi) -> hi - lo > partBytes } }
            if (wide.isEmpty()) return finish(starts)
            val offsets = wide.flatMap { time ->
                val (lo, hi) = bounds(time)
                (1 until ways).map { lo + (hi - lo) * it / ways }
            }.distinct()
            val before = known.size
            when (val read = readTimes(offsets)) {
                is ForgeResult.Failure -> return read
                is ForgeResult.Success -> Unit
            }
            // Nothing learned: the parts hold no stamped line start, so searching on would go round in circles.
            if (known.size == before) return ForgeResult.Failure(ForgeError.Unreadable)
        }
        return ForgeResult.Failure(ForgeError.Unreadable)
    }

    /** Bytes where the first line stamped at or after [time] can be: after every line known to be earlier, up to the first known later. */
    private fun bounds(time: Instant): Pair<Long, Long> {
        val lo = known.entries.lastOrNull { it.value < time }?.key?.plus(1) ?: 0L
        val hi = known.entries.firstOrNull { it.value >= time }?.key ?: reader.size
        return lo to hi
    }

    private suspend fun readTimes(offsets: List<Long>): ForgeResult<Unit> = coroutineScope {
        val gate = Semaphore(PARALLEL_READS)
        val reads = offsets.map { offset -> async { gate.withPermit { reader.read(offset until offset + partBytes) } } }.awaitAll()
        reads.forEach { read ->
            when (read) {
                is ForgeResult.Failure -> return@coroutineScope read
                is ForgeResult.Success -> stampedLines(read.value.bytes, read.value.start).firstOrNull()?.let { (start, time, _) -> known[start] = time }
            }
        }
        ForgeResult.Success(Unit)
    }

    private suspend fun finish(starts: List<Instant>): ForgeResult<List<Long>> {
        val found = mutableListOf<Long>()
        var previous = -1L
        for (time in starts) {
            val (lo, hi) = bounds(time)
            // Past the bounds as far as the next second may run, where the step's opening line can be.
            val read = reader.read(lo until minOf(hi + LOOK_AHEAD_BYTES, reader.size))
            val bytes = when (read) {
                is ForgeResult.Failure -> return read
                is ForgeResult.Success -> read.value
            }
            val lines = stampedLines(bytes.bytes, bytes.start, keepFirst = lo == 0L).filter { it.start > previous }
            val first = lines.firstOrNull { it.time >= time }
            val opening = lines.firstOrNull { it.time >= time && it.time < time.plusSeconds(1) && JobLogSteps.opening(it.content, format) != null }
            val start = opening?.start ?: first?.start ?: hi
            found += start
            previous = start
        }
        return ForgeResult.Success(found)
    }

    private data class StampedLine(val start: Long, val time: Instant, val content: String)

    /**
     * The stamped lines of a part read from [start]. The part's first line is cut unless the part begins the log: it
     * is dropped. A line without a time continues the one before and is left out.
     */
    private fun stampedLines(bytes: ByteArray, start: Long, keepFirst: Boolean = start == 0L): List<StampedLine> {
        val lines = mutableListOf<StampedLine>()
        var first = true
        val splitter = LineSplitter(start) { lineStart, raw ->
            if (first && !keepFirst) {
                first = false
                return@LineSplitter
            }
            first = false
            ActionsLogParser.timeOf(raw)?.let { lines += StampedLine(lineStart, it, ActionsLogParser.contentOf(raw)) }
        }
        splitter.feed(bytes)
        // A part ends mid-line unless it reaches the log's end: that line is left out too.
        if (start + bytes.size >= reader.size) splitter.finish()
        return lines
    }

    private companion object {
        const val MAX_ROUNDS = 12
        const val PARALLEL_READS = 8
        const val LOOK_AHEAD_BYTES = 64 * 1024L
    }
}
