package fr.arthurbrugiere.forgeline.core.data.trending

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.TrendingRepo
import fr.arthurbrugiere.forgeline.core.testing.trendingRepo
import org.junit.Test

class BalanceForgesTest {
    private fun list(forge: ForgeInstance, size: Int) =
        (1..size).map { i -> trendingRepo("${forge.host.substringBefore('.')}/r$i", periodStars = 1000 - i, forge = forge) }

    private fun List<TrendingRepo>.countOn(forge: ForgeInstance) = count { it.id.forge == forge }

    @Test
    fun each_forge_gets_about_the_same_room_on_a_mixed_page() {
        // GitHub's 25 and Codeberg's 10: GitHub, far deeper, gets its two extra, no more.
        val page = balanceForges(mergeByShare(listOf(list(ForgeInstance.GitHub, 25), list(ForgeInstance.Codeberg, 10))))

        assertThat(page.countOn(ForgeInstance.GitHub)).isEqualTo(PER_FORGE + MAX_ACTIVE_EXTRA)
        assertThat(page.countOn(ForgeInstance.Codeberg)).isEqualTo(PER_FORGE)
    }

    @Test
    fun a_forge_a_little_deeper_than_the_others_gets_one_more() {
        val page = balanceForges(mergeByShare(listOf(list(ForgeInstance.GitHub, 25), list(ForgeInstance.Codeberg, PER_FORGE + ACTIVE_STEP))))

        assertThat(page.countOn(ForgeInstance.Codeberg)).isEqualTo(PER_FORGE + 1)
    }

    @Test
    fun a_quiet_forge_shows_what_it_has_and_the_others_are_not_stretched_to_fill() {
        val page = balanceForges(mergeByShare(listOf(list(ForgeInstance.GitHub, 25), list(ForgeInstance.Codeberg, 3))))

        assertThat(page.countOn(ForgeInstance.Codeberg)).isEqualTo(3)
        assertThat(page.countOn(ForgeInstance.GitHub)).isEqualTo(PER_FORGE + MAX_ACTIVE_EXTRA)
    }

    @Test
    fun the_order_of_the_merge_is_kept_and_each_forge_keeps_its_head() {
        val merged = mergeByShare(listOf(list(ForgeInstance.GitHub, 25), list(ForgeInstance.Codeberg, 20), list(ForgeInstance.GitLab, 12)))

        val page = balanceForges(merged)

        assertThat(merged).containsAtLeastElementsIn(page).inOrder()
        listOf(ForgeInstance.GitHub, ForgeInstance.Codeberg, ForgeInstance.GitLab).forEach { forge ->
            assertThat(page.filter { it.id.forge == forge }).isEqualTo(merged.filter { it.id.forge == forge }.take(page.countOn(forge)))
        }
    }

    @Test
    fun a_page_with_a_single_forge_is_left_whole() {
        val gitHub = list(ForgeInstance.GitHub, 25)

        assertThat(balanceForges(gitHub)).isEqualTo(gitHub)
    }
}
