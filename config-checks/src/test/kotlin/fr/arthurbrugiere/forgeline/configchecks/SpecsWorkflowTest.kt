package fr.arthurbrugiere.forgeline.configchecks

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The job that refreshes the forges' API descriptions commits what changed back to the branch it ran on. */
class SpecsWorkflowTest {
    private val workflow = Repo.text(".github/workflows/specs.yml")

    @Test
    fun a_pushed_tag_never_runs_it() {
        // Regression: tagging v0.14.0 ran it on the tag, whose checkout is no branch, and its push failed
        // ("You are not currently on a branch"). A push trigger naming branches leaves tags out.
        val push = workflow.substringAfter("  push:").substringBefore("\npermissions:")
        assertThat(push).containsMatch("""branches:\s*\["\*\*"]""")
        assertThat(push).doesNotContain("tags:")
    }
}
