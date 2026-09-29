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
}
