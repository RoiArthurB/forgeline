package fr.arthurbrugiere.forgeline.configchecks

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The daily Codeberg Trending job keeps a month of history in what it publishes: these guard that history. */
class CodebergTrendingWorkflowTest {
    private val workflow = Repo.text(".github/workflows/codeberg-trending.yml")

    @Test
    fun only_a_missing_history_starts_afresh() {
        // Publishing after a failed download would replace a month of measurements with one day.
        assertThat(workflow).contains("404)")
        assertThat(workflow).containsMatch("""\*\)[^\n]*exit 1""")
    }

    @Test
    fun runs_never_overlap() {
        assertThat(workflow).containsMatch("""cancel-in-progress:\s*false""")
    }

    @Test
    fun the_app_reads_the_file_the_job_publishes() {
        val url = Regex("""forgeline\.codebergTrendingUrl=(\S+)""").find(Repo.text("gradle.properties"))!!.groupValues[1]
        assertThat(url).startsWith("https://")
        assertThat(url).endsWith("/codeberg/trending.json")
        assertThat(workflow).contains("site/codeberg")
        assertThat(workflow).containsMatch("""path:\s*site""")
        assertThat(workflow).contains("/codeberg/state.json")
    }
}
