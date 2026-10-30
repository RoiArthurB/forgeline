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
    fun landing_page_lives_in_site_and_deploys_without_losing_trending_history() {
        val index = Repo.text("site/index.html")
        assertThat(index).contains("Forgeline")
        assertThat(workflow).contains("site/**")
        assertThat(workflow).contains("""REPUBLISH: ${'$'}{{ github.event_name == 'push' || inputs.republish == true }}""")
        assertThat(workflow).contains("""if [ "${'$'}REPUBLISH" = "true" ]; then""")
    }

    @Test
    fun landing_page_names_the_latest_release() {
        // The page said v0.9.0 for days after v0.10.0 came out: the version is filled in at each deployment.
        assertThat(Regex("""data-version>v[^<]+<""").findAll(Repo.text("site/index.html")).count()).isAtLeast(1)
        val stamp = workflow.indexOf("gh release view")
        assertThat(stamp).isAtLeast(0)
        assertThat(stamp).isLessThan(workflow.indexOf("upload-pages-artifact"))
        assertThat(workflow).contains("data-version")
    }
}
