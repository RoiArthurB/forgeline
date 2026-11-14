package fr.arthurbrugiere.forgeline.core.data.trending

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import fr.arthurbrugiere.forgeline.core.testing.trendingRepo
import org.junit.Test

class TrendingFilterTest {
    private fun repo(stars: Int, gain: Int, forge: ForgeInstance = ForgeInstance.Codeberg) =
        trendingRepo("a/r", stars = stars, periodStars = gain, forge = forge)

    @Test
    fun a_big_project_s_usual_daily_stars_are_not_a_trend() {
        // Regression: ziglang/zig stood second on the mixed page with +7 on 7,000 stars, forgejo third with +8 on 5,800.
        assertThat(repo(7_000, 7).isTrending(TrendingPeriod.DAILY)).isFalse()
        assertThat(repo(5_800, 8).isTrending(TrendingPeriod.DAILY)).isFalse()
    }

    @Test
    fun the_same_gain_on_a_smaller_project_is() {
        assertThat(repo(1_300, 12).isTrending(TrendingPeriod.DAILY)).isTrue()
        assertThat(repo(1_600, 7).isTrending(TrendingPeriod.DAILY)).isTrue()
    }

    @Test
    fun the_star_floor_still_holds_however_small_the_project() {
        assertThat(repo(10, TrendingPeriod.DAILY.minStars - 1).isTrending(TrendingPeriod.DAILY)).isFalse()
        assertThat(repo(10, TrendingPeriod.DAILY.minStars).isTrending(TrendingPeriod.DAILY)).isTrue()
    }

    @Test
    fun a_big_github_project_gaining_hundreds_stays() {
        assertThat(repo(60_000, 300, ForgeInstance.GitHub).isTrending(TrendingPeriod.DAILY)).isTrue()
        assertThat(repo(200_000, 80, ForgeInstance.GitHub).isTrending(TrendingPeriod.DAILY)).isFalse()
    }

    @Test
    fun longer_periods_ask_for_more_growth() {
        // +40 in a week on 7,000 stars is still zig's usual pace.
        assertThat(repo(7_000, 40).isTrending(TrendingPeriod.WEEKLY)).isFalse()
        assertThat(repo(7_000, 150).isTrending(TrendingPeriod.WEEKLY)).isTrue()
        assertThat(repo(7_000, 150).isTrending(TrendingPeriod.MONTHLY)).isFalse()
    }

    @Test
    fun a_brand_new_repository_s_first_stars_do_not_read_as_infinite_growth() {
        assertThat(repo(5, 5).growth).isWithin(1e-9).of(5.0 / GROWTH_PRIOR)
    }

    @Test
    fun tangled_s_own_pick_is_kept_whatever_it_gained() {
        assertThat(repo(7_000, 0, ForgeInstance.Tangled).isTrending(TrendingPeriod.DAILY)).isTrue()
    }
}
