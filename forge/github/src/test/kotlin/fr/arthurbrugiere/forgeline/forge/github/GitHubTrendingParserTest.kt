package fr.arthurbrugiere.forgeline.forge.github

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.TrendingRepo
import org.junit.Test

/** Fixtures are real github.com/trending pages captured on 2026-09-26. */
class GitHubTrendingParserTest {
    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/github/$name")).readText()

    private val daily = GitHubTrendingParser.parse(fixture("trending_daily.html"))

    @Test
    fun parses_every_repo_in_page_order() {
        assertThat(daily).hasSize(15)
        assertThat(daily.take(3).map { it.id.fullName })
            .containsExactly("paperclipai/paperclip", "vectorize-io/hindsight", "NVIDIA/Model-Optimizer").inOrder()
    }

    @Test
    fun parses_all_fields_of_a_repo() {
        assertThat(daily.first()).isEqualTo(
            TrendingRepo(
                id = RepoId("paperclipai", "paperclip"),
                description = "The open-source app everyone uses to manage agents at work",
                language = "TypeScript",
                languageColor = "#3178c6",
                stars = 85_955,
                forks = 15_313,
                periodStars = 2_109,
                builtBy = listOf(
                    ForgeUser("cryppadotta", null, "https://avatars.githubusercontent.com/u/34892728?s=40&v=4"),
                    ForgeUser("Paperclip-Paperclip", null, "https://avatars.githubusercontent.com/u/271391591?s=40&v=4"),
                    ForgeUser("devinfoley", null, "https://avatars.githubusercontent.com/u/139239?s=40&v=4"),
                    ForgeUser("claude", null, "https://avatars.githubusercontent.com/u/81847?s=40&v=4"),
                    ForgeUser("nickyleach", null, "https://avatars.githubusercontent.com/u/331803?s=40&v=4"),
                ),
            ),
        )
    }

    @Test
    fun a_repo_without_description_has_none() {
        val repo = daily.single { it.id == RepoId("anthropics", "claude-code-action") }

        assertThat(repo.description).isNull()
        assertThat(repo.periodStars).isEqualTo(15)
    }

    @Test
    fun built_by_lists_each_contributor_once() {
        val repo = daily.single { it.id == RepoId("anthropics", "claude-code-action") }

        assertThat(repo.builtBy.map { it.login }).containsNoDuplicates()
        assertThat(repo.builtBy.map { it.login }).contains("claude")
    }

    @Test
    fun parses_stars_gained_this_week() {
        val weekly = GitHubTrendingParser.parse(fixture("trending_weekly.html"))

        assertThat(weekly).hasSize(18)
        assertThat(weekly.all { it.periodStars > 0 }).isTrue()
    }

    @Test
    fun missing_optional_parts_are_tolerated() {
        val html = """
            <article class="Box-row">
              <h2 class="h3 lh-condensed"><a href="/someone/tiny" class="Link">someone / tiny</a></h2>
              <div class="f6 color-fg-muted mt-2">
                <a href="/someone/tiny/stargazers"><svg></svg> 12</a>
                <span class="d-inline-block float-sm-right"><svg></svg> 1 star today</span>
              </div>
            </article>
        """.trimIndent()

        assertThat(GitHubTrendingParser.parse(html)).containsExactly(
            TrendingRepo(RepoId("someone", "tiny"), null, null, null, stars = 12, forks = 0, periodStars = 1, builtBy = emptyList()),
        )
    }

    @Test
    fun an_unrecognized_page_parses_to_nothing() {
        assertThat(GitHubTrendingParser.parse("<html><body>Rate limited</body></html>")).isEmpty()
    }
}
