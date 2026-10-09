package fr.arthurbrugiere.forgeline.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebUrlsTest {
    private val github = RepoId("alice", "tool", ForgeInstance.GitHub)
    private val codeberg = RepoId("alice", "tool", ForgeInstance.Codeberg)
    private val gitlab = RepoId("alice", "tool", ForgeInstance.GitLab)

    @Test
    fun pull_requests_are_pull_on_github_pulls_on_forgejo_and_merge_requests_on_gitlab() {
        assertThat(IssueRef(github, 7).webUrl(isPullRequest = true)).isEqualTo("https://github.com/alice/tool/pull/7")
        assertThat(IssueRef(codeberg, 7).webUrl(isPullRequest = true)).isEqualTo("https://codeberg.org/alice/tool/pulls/7")
        assertThat(IssueRef(codeberg, 7).webUrl(isPullRequest = false)).isEqualTo("https://codeberg.org/alice/tool/issues/7")
        assertThat(IssueRef(gitlab, 7).webUrl(isPullRequest = true)).isEqualTo("https://gitlab.com/alice/tool/-/merge_requests/7")
        assertThat(IssueRef(gitlab, 7).webUrl(isPullRequest = false)).isEqualTo("https://gitlab.com/alice/tool/-/issues/7")
    }

    @Test
    fun runs_and_jobs_open_on_their_forge() {
        assertThat(github.jobUrl(1, 2)).isEqualTo("https://github.com/alice/tool/actions/runs/1/job/2")
        assertThat(github.runUrl(1)).isEqualTo("https://github.com/alice/tool/actions/runs/1")
        // Forgejo's run pages count runs per repository, not by API id: a wrong run would be worse than the list.
        assertThat(codeberg.runUrl(7368141)).isEqualTo("https://codeberg.org/alice/tool/actions")
        assertThat(codeberg.jobUrl(1, 2)).isEqualTo("https://codeberg.org/alice/tool/actions")
        // GitLab pipelines and jobs
        assertThat(gitlab.runUrl(12345)).isEqualTo("https://gitlab.com/alice/tool/-/pipelines/12345")
        assertThat(gitlab.jobUrl(12345, 67890)).isEqualTo("https://gitlab.com/alice/tool/-/jobs/67890")
    }

    @Test
    fun raw_files_and_file_pages_follow_each_forge_layout() {
        assertThat(github.rawBaseUrl("main")).isEqualTo("https://raw.githubusercontent.com/alice/tool/main/")
        assertThat(github.blobBaseUrl("main")).isEqualTo("https://github.com/alice/tool/blob/main/")
        assertThat(codeberg.rawBaseUrl("main")).isEqualTo("https://codeberg.org/alice/tool/raw/branch/main/")
        assertThat(codeberg.blobBaseUrl("main")).isEqualTo("https://codeberg.org/alice/tool/src/branch/main/")
        assertThat(gitlab.rawBaseUrl("main")).isEqualTo("https://gitlab.com/alice/tool/-/raw/main/")
        assertThat(gitlab.blobBaseUrl("main")).isEqualTo("https://gitlab.com/alice/tool/-/blob/main/")
    }

    @Test
    fun a_release_s_page_is_under_releases_tag_except_on_gitlab() {
        assertThat(github.releaseUrl("v1.0")).isEqualTo("https://github.com/alice/tool/releases/tag/v1.0")
        assertThat(codeberg.releaseUrl("v1.0")).isEqualTo("https://codeberg.org/alice/tool/releases/tag/v1.0")
        assertThat(gitlab.releaseUrl("v1.0")).isEqualTo("https://gitlab.com/alice/tool/-/releases/v1.0")
    }
}
