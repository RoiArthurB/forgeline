package fr.arthurbrugiere.forgeline.core.testing

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PerformanceRecorderTest {
    private val recorder = PerformanceRecorder(warmupIterations = 2, iterations = 10)

    @Test
    fun measures_synchronous_block_and_computes_statistics() {
        var counter = 0
        val result = recorder.measure("counter_increment") {
            counter++
            Thread.sleep(1)
        }

        // 2 warmup + 10 iterations = 12 total runs
        assertThat(counter).isEqualTo(12)
        assertThat(result.name).isEqualTo("counter_increment")
        assertThat(result.iterations).isEqualTo(10)
        assertThat(result.minMillis).isAtLeast(0.5)
        assertThat(result.maxMillis).isAtLeast(result.minMillis)
        assertThat(result.medianMillis).isAtLeast(result.minMillis)
        assertThat(result.medianMillis).isAtMost(result.maxMillis)
        assertThat(result.formatSummary()).contains("[counter_increment]")
    }

    @Test
    fun measures_coroutine_block() = runTest {
        var counter = 0
        val result = recorder.measureSuspend("suspend_increment") {
            counter++
        }

        assertThat(counter).isEqualTo(12)
        assertThat(result.name).isEqualTo("suspend_increment")
        assertThat(result.iterations).isEqualTo(10)
    }

    @Test
    fun compares_two_results_and_reports_speedup() {
        val baseline = PerformanceRecorder.BenchmarkResult(
            name = "baseline",
            iterations = 10,
            minMillis = 10.0,
            maxMillis = 15.0,
            meanMillis = 12.0,
            medianMillis = 12.0,
            p90Millis = 14.0,
            p99Millis = 15.0,
            totalMillis = 120.0,
        )
        val optimized = PerformanceRecorder.BenchmarkResult(
            name = "optimized",
            iterations = 10,
            minMillis = 3.0,
            maxMillis = 5.0,
            meanMillis = 4.0,
            medianMillis = 4.0,
            p90Millis = 4.5,
            p99Millis = 5.0,
            totalMillis = 40.0,
        )

        val comparison = optimized.compareWithBaseline(baseline)
        assertThat(comparison.speedupFactor).isWithin(0.01).of(3.0)
        assertThat(comparison.savingsPercent).isWithin(0.1).of(66.66)
        assertThat(comparison.formatSummary()).contains("3.00x speedup")
    }

    @Test
    fun parses_query_plan_flags_correctly() {
        val unindexedPlan = PerformanceRecorder.QueryPlan(
            sql = "SELECT * FROM notifications ORDER BY updatedAtMillis DESC",
            rawLines = listOf(
                "SCAN notifications",
                "USE TEMP B-TREE FOR ORDER BY",
            ),
        )
        assertThat(unindexedPlan.usesIndex).isFalse()
        assertThat(unindexedPlan.usesTempBTree).isTrue()
        assertThat(unindexedPlan.scansTableWithoutIndex).isTrue()

        val indexedPlan = PerformanceRecorder.QueryPlan(
            sql = "SELECT * FROM notifications ORDER BY updatedAtMillis DESC",
            rawLines = listOf(
                "SCAN notifications USING INDEX index_notifications_updatedAtMillis",
            ),
        )
        assertThat(indexedPlan.usesIndex).isTrue()
        assertThat(indexedPlan.usesTempBTree).isFalse()
        assertThat(indexedPlan.scansTableWithoutIndex).isFalse()
    }
}
