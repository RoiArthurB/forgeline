package fr.arthurbrugiere.forgeline.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant

class YourReposTest {
    private fun repo(full: String, pushed: String?, forge: ForgeInstance = ForgeInstance.GitHub, description: String? = null): RepoSummary {
        val (owner, name) = full.split('/')
        return RepoSummary(RepoId(owner, name, forge), description, null, 0, 0, false, null, pushedAt = pushed?.let(Instant::parse))
    }

    private val me = ForgeUser("RoiArthurB", null, null) to ForgeInstance.GitHub

    @Test
    fun repositories_come_most_recently_pushed_first_and_owners_by_their_latest_push() {
        val repos = listOf(
            repo("gama-platform/gama", "2026-10-16T10:59:55Z"),
            repo("RoiArthurB/forgeline", "2026-10-16T13:58:28Z"),
            repo("ACROSS-Lab/EVEIL.Plus", "2026-10-16T10:52:47Z"),
            repo("ACROSS-Lab/GamaWebPlayer", "2026-10-16T10:04:07Z"),
        )
        val orgs = listOf("ACROSS-Lab", "gama-platform", "EpicGames").map { UserSummary(it, null, true) }

        val yours = YourRepos.of(listOf(me), repos, orgs, 1L)

        assertThat(yours.repos.map { it.id.name }).containsExactly("forgeline", "gama", "EVEIL.Plus", "GamaWebPlayer").inOrder()
        // Oneself first; then gama-platform, pushed to last among the organisations; one with nothing reachable at the end.
        assertThat(yours.owners.map { it.login to it.repoCount }).containsExactly(
            "RoiArthurB" to 1, "gama-platform" to 1, "ACROSS-Lab" to 2, "EpicGames" to 0,
        ).inOrder()
        assertThat(yours.owners.first().isYou).isTrue()
    }

    @Test
    fun the_same_login_on_two_forges_is_two_owners() {
        val codeberg = ForgeUser("RoiArthurB", null, null) to ForgeInstance.Codeberg
        val repos = listOf(repo("RoiArthurB/forgeline", "2026-10-16T13:58:28Z"), repo("RoiArthurB/forgeline", "2026-10-01T00:00:00Z", ForgeInstance.Codeberg))

        val yours = YourRepos.of(listOf(me, codeberg), repos, emptyList(), 1L)

        assertThat(yours.repos).hasSize(2)
        assertThat(yours.owners.map { it.forge }).containsExactly(ForgeInstance.GitHub, ForgeInstance.Codeberg).inOrder()
        assertThat(yours.owners.all { it.repoCount == 1 }).isTrue()
    }

    @Test
    fun what_is_typed_matches_owner_name_and_description_word_by_word_ignoring_case() {
        val gama = repo("ACROSS-Lab/GamaWebPlayer", null, description = "Plays GAMA models in the browser")

        assertThat(gama.matches("gama")).isTrue()
        assertThat(gama.matches("across player")).isTrue()
        assertThat(gama.matches("browser models")).isTrue()
        assertThat(gama.matches("unity")).isFalse()
        assertThat(gama.matches("  ")).isTrue()
    }
}
