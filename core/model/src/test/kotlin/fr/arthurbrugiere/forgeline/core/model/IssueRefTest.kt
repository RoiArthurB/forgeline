package fr.arthurbrugiere.forgeline.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IssueRefTest {
    private val onGitHub = RepoId("acme", "rocket", ForgeInstance.GitHub)
    private val onCodeberg = RepoId("acme", "rocket", ForgeInstance.Codeberg)
    private val onGitLab = RepoId("acme", "rocket", ForgeInstance.GitLab)

    @Test
    fun where_numbers_are_shared_a_conversation_is_its_number_whatever_is_known_of_its_kind() {
        for (repo in listOf(onGitHub, onCodeberg)) {
            assertThat(IssueRef(repo, 7, isPullRequest = true)).isEqualTo(IssueRef(repo, 7))
            assertThat(IssueRef(repo, 7, isPullRequest = true)).isEqualTo(IssueRef(repo, 7, isPullRequest = false))
            assertThat(IssueRef(repo, 7, isPullRequest = true).hashCode()).isEqualTo(IssueRef(repo, 7).hashCode())
        }
    }

    @Test
    fun on_gitlab_an_issue_and_a_merge_request_with_one_number_are_two_conversations() {
        assertThat(IssueRef(onGitLab, 7, isPullRequest = true)).isNotEqualTo(IssueRef(onGitLab, 7, isPullRequest = false))
        assertThat(setOf(IssueRef(onGitLab, 7, isPullRequest = true), IssueRef(onGitLab, 7, isPullRequest = false))).hasSize(2)
    }

    @Test
    fun on_gitlab_a_reference_that_does_not_say_is_an_issue() {
        // Regression: unknown was its own third thing, equal to neither: what was kept under one was never found by the other.
        assertThat(IssueRef(onGitLab, 7)).isEqualTo(IssueRef(onGitLab, 7, isPullRequest = false))
        assertThat(IssueRef(onGitLab, 7).hashCode()).isEqualTo(IssueRef(onGitLab, 7, isPullRequest = false).hashCode())
        assertThat(IssueRef(onGitLab, 7)).isNotEqualTo(IssueRef(onGitLab, 7, isPullRequest = true))
    }

    @Test
    fun another_number_or_repository_is_another_conversation() {
        assertThat(IssueRef(onGitHub, 7)).isNotEqualTo(IssueRef(onGitHub, 8))
        assertThat(IssueRef(onGitHub, 7)).isNotEqualTo(IssueRef(onCodeberg, 7))
    }
}
