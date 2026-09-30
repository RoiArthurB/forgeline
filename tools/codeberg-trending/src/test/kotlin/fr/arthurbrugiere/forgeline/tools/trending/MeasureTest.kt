package fr.arthurbrugiere.forgeline.tools.trending

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class MeasureTest {
    private val day0 = Instant.parse("2026-09-01T02:17:00Z")
    private val old = Instant.parse("2024-01-01T00:00:00Z")

    private fun at(day: Int): Instant = day0.plus(day.toLong(), ChronoUnit.DAYS)

    private fun repo(id: Long, stars: Int, createdAt: Instant = old, forks: Int = 0) =
        CrawledRepo(id, "owner$id", "repo$id", null, "Kotlin", stars, forks, createdAt, null)

    private fun crawl(day: Int, vararg top: CrawledRepo, forks: List<CrawledFork> = emptyList()) = Crawl(at(day), top.toList(), forks)

    /** Records one crawl per day, [counts] giving each day's star counts by id. */
    private fun history(vararg counts: Map<Long, Int>): Pair<TrendingState, Crawl> {
        var state = TrendingState()
        lateinit var last: Crawl
        counts.forEachIndexed { day, stars ->
            last = crawl(day, *stars.map { (id, count) -> repo(id, count) }.toTypedArray())
            state = state.record(last)
        }
        return state to last
    }

    @Test
    fun daily_stars_are_todays_count_minus_yesterdays() {
        val (state, today) = history(mapOf(1L to 100, 2L to 50), mapOf(1L to 130, 2L to 55))

        val daily = state.lists(today).daily

        assertThat(daily.map { it.name to it.periodStars }).containsExactly("repo1" to 30, "repo2" to 5).inOrder()
    }

    @Test
    fun an_old_repository_without_a_starting_point_is_left_out_not_guessed() {
        // Repo 2 only just entered the tracked set, and it's years old: its gain today is unknown.
        val (state, today) = history(mapOf(1L to 100), mapOf(1L to 110, 2L to 400))

        assertThat(state.lists(today).daily.map { it.name }).containsExactly("repo1")
    }

    @Test
    fun a_repository_created_within_the_period_gained_all_its_stars_in_it() {
        val state = TrendingState().record(crawl(0, repo(1, 100)))
        val fresh = repo(2, 40, createdAt = at(1).minus(3, ChronoUnit.HOURS))
        val today = crawl(1, repo(1, 101), fresh)

        val lists = state.record(today).lists(today)

        assertThat(lists.daily.single().name).isEqualTo("repo2")
        assertThat(lists.daily.single().periodStars).isEqualTo(40)
        assertThat(lists.weekly.single().name).isEqualTo("repo2")
    }

    @Test
    fun losing_stars_counts_as_none_and_a_lone_star_isnt_trending() {
        val (state, today) = history(mapOf(1L to 100, 2L to 100, 3L to 100), mapOf(1L to 90, 2L to 101, 3L to 102))

        assertThat(state.lists(today).daily.map { it.name }).containsExactly("repo3")
    }

    @Test
    fun weekly_compares_with_a_week_ago_and_monthly_waits_for_a_month_of_history() {
        val days = (0..7).map { day -> mapOf(1L to 100 + day * 10, 2L to 100 + day) }
        val (state, today) = history(*days.toTypedArray())

        val lists = state.lists(today)

        assertThat(lists.weekly.map { it.name to it.periodStars }).containsExactly("repo1" to 70, "repo2" to 7).inOrder()
        assertThat(lists.monthly).isEmpty()
    }

    @Test
    fun a_missed_run_falls_back_to_the_latest_day_before_the_period() {
        var state = TrendingState().record(crawl(0, repo(1, 100)))
        // No run on day 1.
        val today = crawl(2, repo(1, 120))
        state = state.record(today)

        assertThat(state.days).containsExactly("2026-09-01", "2026-09-03").inOrder()
        assertThat(state.lists(today).daily.single().periodStars).isEqualTo(20)
    }

    @Test
    fun new_forks_break_ties_and_add_up_over_the_period() {
        var state = TrendingState().record(crawl(0, repo(1, 100), repo(2, 100)))
        val today = crawl(
            1, repo(1, 110), repo(2, 110),
            forks = listOf(
                CrawledFork(at(1).minus(1, ChronoUnit.HOURS), parentId = 2),
                CrawledFork(at(1).minus(2, ChronoUnit.HOURS), parentId = 2),
                // Before the last run: already counted then.
                CrawledFork(at(0).minus(1, ChronoUnit.HOURS), parentId = 1),
            ),
        )
        state = state.record(today)

        val daily = state.lists(today).daily

        assertThat(daily.map { it.name to it.periodForks }).containsExactly("repo2" to 2, "repo1" to 0).inOrder()
    }

    @Test
    fun a_second_run_the_same_day_replaces_the_stars_and_adds_only_newer_forks() {
        val first = crawl(0, repo(1, 100), forks = listOf(CrawledFork(at(0).minus(1, ChronoUnit.HOURS), 1)))
        var state = TrendingState().record(first)
        val again = Crawl(
            at(0).plus(2, ChronoUnit.HOURS), listOf(repo(1, 104)),
            listOf(CrawledFork(at(0).minus(1, ChronoUnit.HOURS), 1), CrawledFork(at(0).plus(1, ChronoUnit.HOURS), 1)),
        )
        state = state.record(again)

        assertThat(state.days).hasSize(1)
        assertThat(state.repos.getValue("1").stars).containsExactly(104)
        assertThat(state.newForks.getValue("1")).containsExactly(2)
    }

    @Test
    fun history_keeps_the_last_31_days_and_forgets_repositories_gone_that_long() {
        var state = TrendingState().record(crawl(0, repo(1, 100), repo(2, 50)))
        for (day in 1..HISTORY_DAYS) state = state.record(crawl(day, repo(1, 100 + day)))

        assertThat(state.days).hasSize(HISTORY_DAYS)
        assertThat(state.days.first()).isEqualTo("2026-09-02")
        assertThat(state.repos.getValue("1").stars).hasSize(HISTORY_DAYS)
        assertThat(state.repos).doesNotContainKey("2")
    }

    @Test
    fun a_list_holds_at_most_25_repositories_most_gained_first() {
        val yesterday = (1L..40L).associateWith { 100 }
        val today = (1L..40L).associateWith { 100 + it.toInt() * 2 }
        val (state, crawl) = history(yesterday, today)

        val daily = state.lists(crawl).daily

        assertThat(daily).hasSize(LIST_SIZE)
        assertThat(daily.first().name).isEqualTo("repo40")
        assertThat(daily.map { it.periodStars }).isInOrder(Comparator.reverseOrder<Int>())
    }

    @Test
    fun a_renamed_repository_keeps_its_history_by_id() {
        val state = TrendingState().record(crawl(0, repo(1, 100)))
        val renamed = repo(1, 130).copy(owner = "neworg", name = "newname")
        val today = crawl(1, renamed)

        val daily = state.record(today).lists(today).daily

        assertThat(daily.single().owner).isEqualTo("neworg")
        assertThat(daily.single().periodStars).isEqualTo(30)
    }
}
