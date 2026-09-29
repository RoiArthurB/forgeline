package fr.arthurbrugiere.forgeline.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebUrlsTest {
    private val github = RepoId("alice", "tool", ForgeInstance.GitHub)
    private val codeberg = RepoId("alice", "tool", ForgeInstance.Codeberg)

    @Test
    fun pull_requests_are_pull_on_github_and_pulls_on_forgejo() {
        assertThat(IssueRef(github, 7).webUrl(isPullRequest = true)).isEqualTo("https://github.com/alice/tool/pull/7")
        assertThat(IssueRef(codeberg, 7).webUrl(isPullRequest = true)).isEqualTo("https://codeberg.org/alice/tool/pulls/7")
        assertThat(IssueRef(codeberg, 7).webUrl(isPullRequest = false)).isEqualTo("https://codeberg.org/alice/tool/issues/7")
    }

    @Test
    fun runs_and_jobs_open_on_their_forge() {
        assertThat(github.jobUrl(1, 2)).isEqualTo("https://github.com/alice/tool/actions/runs/1/job/2")
        assertThat(codeberg.jobUrl(1, 2)).isEqualTo("https://codeberg.org/alice/tool/actions/runs/1")
    }

    @Test
    fun raw_files_and_file_pages_follow_each_forge_layout() {
        assertThat(github.rawBaseUrl("main")).isEqualTo("https://raw.githubusercontent.com/alice/tool/main/")
        assertThat(github.blobBaseUrl("main")).isEqualTo("https://github.com/alice/tool/blob/main/")
        assertThat(codeberg.rawBaseUrl("main")).isEqualTo("https://codeberg.org/alice/tool/raw/branch/main/")
        assertThat(codeberg.blobBaseUrl("main")).isEqualTo("https://codeberg.org/alice/tool/src/branch/main/")
    }
}
