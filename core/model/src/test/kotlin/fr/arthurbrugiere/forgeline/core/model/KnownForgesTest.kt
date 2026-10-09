package fr.arthurbrugiere.forgeline.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

class KnownForgesTest {
    @After
    fun forget() = KnownForges.clear()

    @Test
    fun a_server_nobody_told_about_is_a_forgejo() {
        assertThat(ForgeInstance.of("git.example.org")).isEqualTo(ForgeInstance(ForgeType.FORGEJO, "git.example.org"))
    }

    @Test
    fun a_server_remembered_as_a_gitlab_is_one_wherever_only_its_host_is_carried() {
        KnownForges.remember(ForgeInstance(ForgeType.GITLAB, "gitlab.example.org"))

        assertThat(ForgeInstance.of("gitlab.example.org").type).isEqualTo(ForgeType.GITLAB)
        assertThat(ForgeInstance.of("GitLab.Example.org").type).isEqualTo(ForgeType.GITLAB)
        // Its merge requests are numbered apart, as on gitlab.com.
        val repo = RepoId("group", "project", ForgeInstance.of("gitlab.example.org"))
        assertThat(IssueRef(repo, 7, isPullRequest = true)).isNotEqualTo(IssueRef(repo, 7, isPullRequest = false))
        // Another server stays what it was.
        assertThat(ForgeInstance.of("git.example.org").type).isEqualTo(ForgeType.FORGEJO)
    }

    @Test
    fun the_forges_known_by_name_cannot_be_told_otherwise() {
        KnownForges.remember(ForgeInstance(ForgeType.FORGEJO, "gitlab.com"))
        KnownForges.remember(ForgeInstance(ForgeType.GITLAB, "github.com"))

        assertThat(ForgeInstance.of("gitlab.com")).isEqualTo(ForgeInstance.GitLab)
        assertThat(ForgeInstance.of("github.com")).isEqualTo(ForgeInstance.GitHub)
    }

    @Test
    fun every_server_remembered_is_listed_as_what_it_runs() {
        KnownForges.remember(ForgeInstance(ForgeType.GITLAB, "Lab.Example.org"))
        KnownForges.remember(ForgeInstance(ForgeType.FORGEJO, "git.example.org"))
        // Known without being told: not a self-hosted server.
        KnownForges.remember(ForgeInstance.GitHub)

        assertThat(KnownForges.all()).containsExactly(ForgeInstance(ForgeType.GITLAB, "lab.example.org"), ForgeInstance(ForgeType.FORGEJO, "git.example.org"))
    }

    @Test
    fun nothing_remembered_lists_nothing() {
        assertThat(KnownForges.all()).isEmpty()
    }
}
