package fr.arthurbrugiere.forgeline.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RepoIdTest {
    @Test
    fun the_same_name_on_two_forges_is_two_repositories() {
        assertThat(RepoId("alice", "tool", ForgeInstance.GitHub)).isNotEqualTo(RepoId("alice", "tool", ForgeInstance.Codeberg))
    }

    @Test
    fun names_differing_only_in_case_are_one_repository_in_a_set() {
        val ids = listOf(
            RepoId("Alice", "Tool"),
            RepoId("alice", "tool"),
            RepoId("ALICE", "TOOL"),
            // Letters whose case folds unevenly still hash as they compare.
            RepoId("straße", "ǅ"),
            RepoId("STRAßE", "ǆ"),
        )

        assertThat(ids.toSet()).hasSize(2)
        ids.forEach { a -> ids.filter { it == a }.forEach { b -> assertThat(b.hashCode()).isEqualTo(a.hashCode()) } }
    }

    @Test
    fun web_url_points_at_the_repository_forge() {
        assertThat(RepoId("alice", "tool", ForgeInstance.GitHub).webUrl).isEqualTo("https://github.com/alice/tool")
        assertThat(RepoId("alice", "tool", ForgeInstance.Codeberg).webUrl).isEqualTo("https://codeberg.org/alice/tool")
    }

    @Test
    fun a_key_names_the_forge_and_reads_back() {
        val codeberg = RepoId("alice", "tool", ForgeInstance.Codeberg)

        assertThat(codeberg.key).isEqualTo("codeberg.org/alice/tool")
        assertThat(RepoId.fromKey(codeberg.key)).isEqualTo(codeberg)
        assertThat(RepoId.fromKey("github.com/alice/tool")).isEqualTo(RepoId("alice", "tool", ForgeInstance.GitHub))
        assertThat(RepoId.fromKey("alice/tool")).isNull()
    }

    @Test
    fun a_gitlab_project_under_nested_groups_reads_back_from_its_key() {
        val nested = RepoId("group/subgroup", "tool", ForgeInstance.GitLab)

        assertThat(RepoId.fromKey(nested.key)).isEqualTo(nested)
        assertThat(nested.webUrl).isEqualTo("https://gitlab.com/group/subgroup/tool")
        assertThat(ForgeInstance.of("GitLab.com")).isEqualTo(ForgeInstance.GitLab)
        assertThat(ForgeInstance.GitLab.isBrowsable).isTrue()
        assertThat(ForgeInstance.Codeberg.isBrowsable).isTrue()
    }

    @Test
    fun a_host_is_github_or_a_forgejo_instance() {
        assertThat(ForgeInstance.of("github.com")).isEqualTo(ForgeInstance.GitHub)
        assertThat(ForgeInstance.of("www.GitHub.com")).isEqualTo(ForgeInstance.GitHub)
        assertThat(ForgeInstance.of("codeberg.org")).isEqualTo(ForgeInstance.Codeberg)
        assertThat(ForgeInstance.of("git.example.org")).isEqualTo(ForgeInstance(ForgeType.FORGEJO, "git.example.org"))
    }

    @Test
    fun gitea_com_is_known_by_name_and_speaks_forgejos_api() {
        assertThat(ForgeInstance.of("gitea.com")).isEqualTo(ForgeInstance.Gitea)
        assertThat(ForgeInstance.Gitea.displayName).isEqualTo("Gitea")
        assertThat(ForgeType.GITEA.speaksForgejo).isTrue()
        assertThat(ForgeType.GITLAB.speaksForgejo).isFalse()
        val tea = RepoId("gitea", "tea", ForgeInstance.Gitea)
        assertThat(IssueRef(tea, 7).webUrl(isPullRequest = true)).isEqualTo("https://gitea.com/gitea/tea/pulls/7")
        assertThat(tea.blobBaseUrl("main")).isEqualTo("https://gitea.com/gitea/tea/src/branch/main/")
    }

    @Test
    fun tangled_is_known_by_name_under_both_its_addresses() {
        assertThat(ForgeInstance.of("tangled.org")).isEqualTo(ForgeInstance.Tangled)
        assertThat(ForgeInstance.of("tangled.sh")).isEqualTo(ForgeInstance.Tangled)
        assertThat(ForgeInstance.Tangled.displayName).isEqualTo("Tangled")
        assertThat(ForgeType.TANGLED.speaksForgejo).isFalse()
        assertThat(ForgeType.TANGLED.signsIn).isFalse()
        val core = RepoId("tangled.org", "core", ForgeInstance.Tangled)
        assertThat(core.webUrl).isEqualTo("https://tangled.org/tangled.org/core")
        assertThat(core.blobBaseUrl("master")).isEqualTo("https://tangled.org/tangled.org/core/blob/master/")
        assertThat(core.rawBaseUrl("master")).isEqualTo("https://tangled.org/tangled.org/core/raw/master/")
    }

    @Test
    fun sourcehut_is_known_by_name_and_its_owners_keep_their_tilde() {
        assertThat(ForgeInstance.of("git.sr.ht")).isEqualTo(ForgeInstance.SourceHut)
        assertThat(ForgeInstance.SourceHut.displayName).isEqualTo("SourceHut")
        assertThat(ForgeType.SOURCEHUT.isSocial).isFalse()
        assertThat(ForgeType.SOURCEHUT.signsIn).isTrue()
        val scdoc = RepoId("~sircmpwn", "scdoc", ForgeInstance.SourceHut)
        assertThat(scdoc.webUrl).isEqualTo("https://git.sr.ht/~sircmpwn/scdoc")
        assertThat(scdoc.rawBaseUrl("master")).isEqualTo("https://git.sr.ht/~sircmpwn/scdoc/blob/master/")
        assertThat(scdoc.blobBaseUrl("master")).isEqualTo("https://git.sr.ht/~sircmpwn/scdoc/tree/master/item/")
        assertThat(scdoc.releaseUrl("1.11.3")).isEqualTo("https://git.sr.ht/~sircmpwn/scdoc/refs/1.11.3")
        assertThat(IssueRef(scdoc, 4).webUrl(isPullRequest = false)).isEqualTo("https://todo.sr.ht/~sircmpwn/scdoc/4")
    }

    @Test
    fun repositories_are_equal_and_hash_identically_regardless_of_case() {
        val uppercase = RepoId("RoiArthurB", "Forgeline", ForgeInstance.GitHub)
        val lowercase = RepoId("roiarthurb", "forgeline", ForgeInstance.GitHub)

        assertThat(uppercase).isEqualTo(lowercase)
        assertThat(uppercase.hashCode()).isEqualTo(lowercase.hashCode())
        assertThat(mapOf(lowercase to "ok")[uppercase]).isEqualTo("ok")
    }

    @Test
    fun case_insensitivity_handles_ascii_letters_deterministically() {
        val igniaUpper = RepoId("Ignia-org", "Ignia-app", ForgeInstance.GitHub)
        val igniaLower = RepoId("ignia-org", "ignia-app", ForgeInstance.GitHub)

        assertThat(igniaUpper).isEqualTo(igniaLower)
        assertThat(igniaUpper.hashCode()).isEqualTo(igniaLower.hashCode())
    }
}
