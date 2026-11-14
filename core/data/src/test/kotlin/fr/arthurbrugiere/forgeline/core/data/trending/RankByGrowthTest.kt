package fr.arthurbrugiere.forgeline.core.data.trending

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import fr.arthurbrugiere.forgeline.core.model.TrendingRepo
import fr.arthurbrugiere.forgeline.core.testing.trendingRepo
import org.junit.Test

class RankByGrowthTest {
    /** Repositories as name to (total stars, stars gained), in the forge's own order. */
    private fun list(forge: ForgeInstance, vararg repos: Pair<String, Pair<Int, Int>>) =
        repos.map { (name, counts) -> trendingRepo(name, stars = counts.first, periodStars = counts.second, forge = forge) }

    private fun List<TrendingRepo>.names() = map { "${it.id.forge.host.substringBefore('.')}:${it.id.fullName}" }

    // GitHub's daily Trending on 2026-11-14 as the page showed it, in GitHub's order.
    private val gitHub = list(
        ForgeInstance.GitHub,
        "alibaba/open-code-review" to (50785 to 215),
        "GoogleChrome/modern-web-guidance-src" to (5025 to 1925),
        "openai/codex-security" to (13232 to 751),
        "ZhuLinsen/daily_stock_analysis" to (71794 to 256),
        "we-promise/sure" to (14857 to 925),
        "backnotprop/plannotator" to (10534 to 108),
        "louislam/uptime-kuma" to (93936 to 71),
        "block/buzz" to (37339 to 97),
        "tradermonty/claude-trading-skills" to (4270 to 69),
        "tailscale/tailcat" to (9543 to 256),
        "Likely7/Veyra-NRVideo" to (1697 to 256),
        "ChromeDevTools/chrome-devtools-mcp" to (55612 to 67),
    )

    // Codeberg's published daily list of 2026-11-09, the one on screen when zig stood second.
    private val codeberg = list(
        ForgeInstance.Codeberg,
        "ethical-foss/ai-free-foss-alternatives" to (1379 to 12),
        "ziglang/zig" to (7033 to 9),
        "forgejo/forgejo" to (5800 to 8),
        "GramEditor/gram" to (1649 to 7),
        "ziglings/exercises" to (1748 to 6),
        "dnkl/foot" to (2323 to 6),
        "comaps/comaps" to (2235 to 5),
        "brib/slopfree-software-index" to (373 to 5),
        "shitter/shitter" to (163 to 5),
        "Freeyourgadget/Gadgetbridge" to (2027 to 4),
        "dwl/dwl" to (771 to 4),
        "librewolf/source" to (1369 to 4),
        "polyphony/slopscore" to (231 to 4),
        "fairyglade/ly" to (742 to 3),
        "superseriousbusiness/gotosocial" to (702 to 3),
        "ChiPass/ChiPass" to (598 to 3),
        "evi-editor/evi" to (334 to 3),
        "haydn/typesetter" to (375 to 3),
        "furylemons/RedXGameLibrary" to (100 to 3),
        "polyphony/volute" to (55 to 3),
        "whzmsa/unpk" to (31 to 3),
        "StillDeadcode/radiance" to (96 to 2),
        "guix/guix" to (1031 to 2),
        "river/wiki" to (78 to 2),
        "RevK/ESP32-Faikout" to (64 to 2),
    )

    // gitlab.com's published daily list of 2026-11-13.
    private val gitLab = list(
        ForgeInstance.GitLab,
        "Evokerr/AlwaysStrong" to (75 to 6),
        "rennan-group2/nxnative_Launcher" to (27 to 3),
        "kitbyte/wand-enhancer" to (167 to 2),
        "bighead.0/ladxhd_updated" to (89 to 2),
        "librespacefoundation/satnogs/satnogs-network" to (60 to 2),
        "koha-community/koha-testing-docker" to (27 to 2),
        "ismaellozanoperezserrano/hacbrewpacklz" to (17 to 2),
    )

    private val page = rankByGrowth(listOf(gitHub, codeberg, gitLab), TrendingPeriod.DAILY).names()

    @Test
    fun a_big_project_s_usual_daily_stars_are_off_the_page() {
        // Regression: zig (+9 on 7,000) and forgejo (+8 on 5,800) stood second and third.
        assertThat(page).containsNoneOf(
            "codeberg:ziglang/zig",
            "codeberg:forgejo/forgejo",
            "codeberg:comaps/comaps",
            "github:louislam/uptime-kuma",
            "github:ChromeDevTools/chrome-devtools-mcp",
        )
    }

    @Test
    fun the_forges_mix_by_growth_against_their_own_forge() {
        assertThat(page.take(10)).containsExactly(
            "github:GoogleChrome/modern-web-guidance-src",
            "github:Likely7/Veyra-NRVideo",
            "github:we-promise/sure",
            "codeberg:shitter/shitter",
            "github:openai/codex-security",
            "codeberg:brib/slopfree-software-index",
            "github:tailscale/tailcat",
            "gitlab:Evokerr/AlwaysStrong",
            "codeberg:ethical-foss/ai-free-foss-alternatives",
            "github:tradermonty/claude-trading-skills",
        ).inOrder()
        assertThat(page).hasSize(17)
    }

    @Test
    fun a_forge_s_median_counts_the_repositories_under_the_floors() {
        // Without zig, forgejo and the 2- to 4-star rows, Codeberg's median would be its few movers' and push them down.
        val onlyMovers = rankByGrowth(listOf(gitHub, codeberg.filter { it.isTrending(TrendingPeriod.DAILY) }, gitLab), TrendingPeriod.DAILY).names()

        assertThat(onlyMovers.indexOf("codeberg:shitter/shitter")).isGreaterThan(page.indexOf("codeberg:shitter/shitter"))
    }

    @Test
    fun a_forge_whose_list_gained_nothing_does_not_divide_by_zero() {
        val tangled = list(ForgeInstance.Tangled, "a/picked" to (40 to 0), "b/picked" to (10 to 0), "c/picked" to (30 to 2))

        val mixed = rankByGrowth(listOf(gitHub, tangled), TrendingPeriod.DAILY).names()

        // Tangled's own pick is shown whole, whatever it gained.
        assertThat(mixed).containsAtLeast("tangled:a/picked", "tangled:b/picked", "tangled:c/picked")
        // c/picked: +2 on 28 is 2.6%, against the 0.1% floor of Tangled's median: second only to GitHub's +1,925.
        assertThat(mixed.indexOf("tangled:c/picked")).isEqualTo(1)
    }

    @Test
    fun equal_scores_keep_the_forges_order_then_each_forge_s_own() {
        val a = list(ForgeInstance.GitHub, "a/one" to (150 to 10), "a/two" to (150 to 10))
        val b = list(ForgeInstance.Codeberg, "b/one" to (150 to 10))

        assertThat(rankByGrowth(listOf(a, b), TrendingPeriod.DAILY).names()).containsExactly("github:a/one", "github:a/two", "codeberg:b/one").inOrder()
    }

    @Test
    fun nothing_to_rank_is_an_empty_page() {
        assertThat(rankByGrowth(emptyList(), TrendingPeriod.DAILY)).isEmpty()
        assertThat(rankByGrowth(listOf(emptyList(), emptyList()), TrendingPeriod.DAILY)).isEmpty()
    }
}
