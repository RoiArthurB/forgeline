package fr.arthurbrugiere.forgeline.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RepoIdTest {
    @Test
    fun the_same_name_on_two_forges_is_two_repositories() {
        assertThat(RepoId("alice", "tool", ForgeInstance.GitHub)).isNotEqualTo(RepoId("alice", "tool", ForgeInstance.Codeberg))
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
        assertThat(ForgeInstance.GitLab.isBrowsable).isFalse()
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
