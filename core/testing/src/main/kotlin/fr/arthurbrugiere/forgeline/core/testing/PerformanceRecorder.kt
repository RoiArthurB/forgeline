package fr.arthurbrugiere.forgeline.core.testing

import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.Locale

/**
 * Benchmark and performance measurement harness for unit and integration tests.
 *
 * Measures execution timings across warm-up and timed iterations, extracts SQLite query plans
 * to detect unindexed table scans or temporary B-tree disk sorts, and provides comparison
 * metrics before and after optimizations.
 */
class PerformanceRecorder(
    private val warmupIterations: Int = 5,
    private val iterations: Int = 25,
) {
    data class BenchmarkResult(
        val name: String,
        val iterations: Int,
        val minMillis: Double,
        val maxMillis: Double,
        val meanMillis: Double,
        val medianMillis: Double,
        val p90Millis: Double,
        val p99Millis: Double,
        val totalMillis: Double,
        val notes: Map<String, String> = emptyMap(),
    ) {
        /** Formats a compact single-line performance summary. */
        fun formatSummary(): String = String.format(
            Locale.US,
            "[%s] %d runs | min=%.2fms | median=%.2fms | p90=%.2fms | mean=%.2fms | max=%.2fms",
            name, iterations, minMillis, medianMillis, p90Millis, meanMillis, maxMillis,
        )

        /** Compares this result with a baseline result, returning speedup factor and savings. */
        fun compareWithBaseline(baseline: BenchmarkResult): Comparison {
            val speedupFactor = if (meanMillis > 0.0) baseline.meanMillis / meanMillis else 1.0
            val savingsPercent = if (baseline.meanMillis > 0.0) ((baseline.meanMillis - meanMillis) / baseline.meanMillis) * 100.0 else 0.0
            return Comparison(
                baselineName = baseline.name,
                optimizedName = name,
                baselineMeanMillis = baseline.meanMillis,
                optimizedMeanMillis = meanMillis,
                speedupFactor = speedupFactor,
                savingsPercent = savingsPercent,
            )
        }
    }

    data class Comparison(
        val baselineName: String,
        val optimizedName: String,
        val baselineMeanMillis: Double,
        val optimizedMeanMillis: Double,
        val speedupFactor: Double,
        val savingsPercent: Double,
    ) {
        fun formatSummary(): String = String.format(
            Locale.US,
            "%s vs %s: %.2fx speedup (%.1f%% reduction: %.2fms -> %.2fms)",
            optimizedName, baselineName, speedupFactor, savingsPercent, baselineMeanMillis, optimizedMeanMillis,
        )
    }

    data class QueryPlan(
        val sql: String,
        val rawLines: List<String>,
    ) {
        /** Whether the SQLite query plan uses an index. */
        val usesIndex: Boolean = rawLines.any { it.contains("USING INDEX", ignoreCase = true) || it.contains("USING COVERING INDEX", ignoreCase = true) }

        /** Whether SQLite requires building a temporary B-tree (e.g. for ORDER BY without an index). */
        val usesTempBTree: Boolean = rawLines.any { it.contains("USE TEMP B-TREE", ignoreCase = true) }

        /** Whether the query performs an unindexed full table scan. */
        val scansTableWithoutIndex: Boolean = rawLines.any {
            it.contains("SCAN", ignoreCase = true) && !it.contains("USING INDEX", ignoreCase = true) && !it.contains("USING COVERING INDEX", ignoreCase = true)
        }

        fun summary(): String = rawLines.joinToString("\n")
    }

    private val recordedResults = mutableListOf<BenchmarkResult>()

    fun allResults(): List<BenchmarkResult> = recordedResults.toList()

    /**
     * Executes [block] for [warmupIterations] to warm up JIT, caches, and memory,
     * then executes [iterations] times measuring execution duration in nanoseconds.
     */
    fun measure(name: String, notes: Map<String, String> = emptyMap(), block: () -> Unit): BenchmarkResult {
        repeat(warmupIterations) {
            block()
        }
        val durationsNanos = LongArray(iterations)
        for (i in 0 until iterations) {
            val start = System.nanoTime()
            block()
            val end = System.nanoTime()
            durationsNanos[i] = end - start
        }
        val result = calculateStats(name, durationsNanos, notes)
        recordedResults.add(result)
        return result
    }

    /**
     * Coroutine-friendly variant of [measure].
     */
    suspend fun measureSuspend(name: String, notes: Map<String, String> = emptyMap(), block: suspend () -> Unit): BenchmarkResult {
        repeat(warmupIterations) {
            block()
        }
        val durationsNanos = LongArray(iterations)
        for (i in 0 until iterations) {
            val start = System.nanoTime()
            block()
            val end = System.nanoTime()
            durationsNanos[i] = end - start
        }
        val result = calculateStats(name, durationsNanos, notes)
        recordedResults.add(result)
        return result
    }

    private fun calculateStats(name: String, durationsNanos: LongArray, notes: Map<String, String>): BenchmarkResult {
        durationsNanos.sort()
        val durationsMillis = durationsNanos.map { it / 1_000_000.0 }
        val size = durationsMillis.size
        val min = durationsMillis.first()
        val max = durationsMillis.last()
        val mean = durationsMillis.average()
        val median = durationsMillis[size / 2]
        val p90 = durationsMillis[(size * 0.90).toInt().coerceAtMost(size - 1)]
        val p99 = durationsMillis[(size * 0.99).toInt().coerceAtMost(size - 1)]
        val total = durationsMillis.sum()
        return BenchmarkResult(name, size, min, max, mean, median, p90, p99, total, notes)
    }

    /**
     * Queries SQLite for the execution plan of [sql] via `EXPLAIN QUERY PLAN <sql>`.
     */
    fun explainQueryPlan(db: SupportSQLiteDatabase, sql: String, args: Array<out Any?> = emptyArray()): QueryPlan {
        val lines = mutableListOf<String>()
        db.query("EXPLAIN QUERY PLAN $sql", args).use { cursor ->
            val detailIndex = cursor.getColumnIndex("detail").takeIf { it >= 0 } ?: (cursor.columnCount - 1)
            while (cursor.moveToNext()) {
                lines.add(cursor.getString(detailIndex))
            }
        }
        return QueryPlan(sql, lines)
    }

    /**
     * Clears recorded results.
     */
    fun clear() {
        recordedResults.clear()
    }
}
