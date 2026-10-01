package fr.arthurbrugiere.forgeline.core.data.trending

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.testing.trendingRepo
import org.junit.Test

class MergeByShareTest {
    private fun list(forge: ForgeInstance, vararg gains: Int) =
        gains.mapIndexed { i, gain -> trendingRepo("${forge.host.substringBefore('.')}/r${i + 1}", periodStars = gain, forge = forge) }

    private fun List<fr.arthurbrugiere.forgeline.core.model.TrendingRepo>.names() = map { it.id.fullName }

    // A day on GitHub as it looked on 2026-10-01, in GitHub's own order.
    private val gitHub = list(ForgeInstance.GitHub, 1179, 888, 2503, 112, 640, 420, 388, 301, 250, 199, 150, 120, 96, 80, 60)

    @Test
    fun a_forge_listing_a_handful_does_not_take_the_top_of_the_page() {
        // Regression: shares were taken over whatever a forge listed, so three projects with 3, 2 and 2 stars each
        // "held" 43%, 29% and 29% of their forge and stood above a repository that gained 1,179.
        val gitLab = list(ForgeInstance.GitLab, 3, 2, 2)

        val page = mergeByShare(listOf(gitHub, gitLab)).names()

        assertThat(page.first()).isEqualTo("github/r1")
        assertThat(page.take(4).none { it.startsWith("gitlab/") }).isTrue()
    }

    @Test
    fun a_short_list_that_gained_real_stars_keeps_its_leader_near_the_top() {
        // Three repositories, 110 stars between them: the forge's story of the day still shows early.
        val page = mergeByShare(listOf(gitHub, list(ForgeInstance.Codeberg, 40, 35, 35))).names()

        assertThat(page.first()).isEqualTo("github/r1")
        assertThat(page.indexOf("codeberg/r1")).isLessThan(5)
    }

    @Test
    fun a_forge_listing_one_repository_does_not_lead_the_page_with_it() {
        val page = mergeByShare(listOf(gitHub, list(ForgeInstance.GitLab, 2))).names()

        assertThat(page.first()).isEqualTo("github/r1")
    }

    @Test
    fun full_lists_are_ranked_as_before() {
        // Shares are untouched from the tenth entry on: 30 stars on Codeberg still stand with thousands on GitHub.
        val codeberg = list(ForgeInstance.Codeberg, 30, 12, 8, 7, 6, 6, 6, 4, 4, 3, 3, 2)

        val page = mergeByShare(listOf(gitHub, codeberg)).names()

        // 30 of 91 is a bigger share than 1,179 of 7,386.
        assertThat(page.first()).isEqualTo("codeberg/r1")
        assertThat(page).hasSize(gitHub.size + codeberg.size)
    }

    @Test
    fun each_forge_keeps_its_own_order() {
        val page = mergeByShare(listOf(gitHub, list(ForgeInstance.GitLab, 3, 2, 2), list(ForgeInstance.Codeberg, 12, 8, 7))).names()

        listOf("github", "gitlab", "codeberg").forEach { forge ->
            val own = page.filter { it.startsWith("$forge/") }
            assertThat(own).isEqualTo(own.sortedBy { it.substringAfter("/r").toInt() })
        }
    }

    @Test
    fun a_forge_that_gained_a_handful_of_stars_in_all_does_not_lead_the_page() {
        // Regression, with the three lists really published on 2026-10-01: gitlab.com's whole daily list gained 17
        // stars, so its first project (+3) held 18% of its forge and stood first, above GitHub's +1,179.
        val gitHubThatDay = list(ForgeInstance.GitHub, 1179, 888, 2503, 112, 640, 590, 480, 455, 420, 388, 301, 250, 240, 199, 155)
        val codeberg = list(ForgeInstance.Codeberg, 14, 12, 8, 7, 6, 6, 6, 5, 5, 4, 4, 4, 3, 3, 3, 3, 3, 3, 3, 3, 2, 2, 2, 2, 2)
        val gitLab = list(ForgeInstance.GitLab, 3, 2, 2, 2, 2, 2, 2, 2)

        val page = mergeByShare(listOf(gitHubThatDay, codeberg, gitLab)).names()

        assertThat(page.first()).isEqualTo("github/r1")
        assertThat(page.take(10).none { it.startsWith("gitlab/") }).isTrue()
        // Codeberg gained 115 that day: enough to be weighed as it is.
        assertThat(page.indexOf("codeberg/r1")).isLessThan(3)
    }
}
