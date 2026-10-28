package fr.arthurbrugiere.forgeline.user

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ContributionLevelsTest {
    @Test
    fun nothing_is_level_none_and_the_busy_days_split_in_four() {
        val levels = Levels.of(listOf(0, 1, 2, 3, 4, 5, 6, 7, 8))

        assertThat(levels.of(0)).isEqualTo(0)
        assertThat(levels.of(1)).isEqualTo(1)
        assertThat(levels.of(8)).isEqualTo(4)
        assertThat((1..8).map(levels::of)).isInOrder()
    }

    @Test
    fun one_huge_day_does_not_wash_out_the_others() {
        val levels = Levels.of(List(30) { 3 } + 500)

        assertThat(levels.of(500)).isEqualTo(4)
        // A day like most is a step above nothing, not the palest of five.
        assertThat(levels.of(3)).isAtLeast(1)
    }

    @Test
    fun a_year_with_nothing_still_answers() {
        assertThat(Levels.of(emptyList()).of(0)).isEqualTo(0)
        assertThat(Levels.of(emptyList()).of(2)).isEqualTo(1)
    }
}
