package fr.arthurbrugiere.forgeline.configchecks

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The daily Trending job keeps a month of history per forge in what it publishes: these guard that history. */
class TrendingWorkflowTest {
    private val workflow = Repo.text(".github/workflows/trending.yml")

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
    fun one_forge_failing_keeps_its_history_in_the_deployment() {
        // One deployment replaces the whole site: a forge left out of it would lose its history.
        assertThat(workflow).contains("""cp "previous-${'$'}forge.json" "site/${'$'}forge/state.json"""")
        assertThat(workflow).contains("""[ "${'$'}measured" -gt 0 ]""")
    }

    @Test
    fun the_app_reads_the_file_the_job_publishes() {
        val url = Regex("""forgeline\.codebergTrendingUrl=(\S+)""").find(Repo.text("gradle.properties"))!!.groupValues[1]
        assertThat(url).startsWith("https://")
        assertThat(url).endsWith("/codeberg/trending.json")
        assertThat(workflow).contains("for forge in codeberg gitlab")
        assertThat(workflow).contains("site/${'$'}forge")
        assertThat(workflow).containsMatch("""path:\s*site""")
        assertThat(workflow).contains("${'$'}base/state.json")
    }

    @Test
    fun a_prepared_history_is_only_used_when_none_is_published() {
        // It sits in the 404 branch: a published history always wins, so the seed can't overwrite real measurements.
        val notFound = workflow.substringAfter("404)").substringBefore(";;")
        assertThat(notFound).contains("tools/trending/seed/${'$'}forge-state.json")
        assertThat(workflow.substringBefore("404)")).doesNotContain("tools/trending/seed")
    }
}
