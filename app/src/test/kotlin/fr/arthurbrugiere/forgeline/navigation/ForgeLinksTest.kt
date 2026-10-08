package fr.arthurbrugiere.forgeline.navigation

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import org.junit.Test

class ForgeLinksTest {
    @Test
    fun repository_urls_open_the_repo_in_app() {
        assertThat(ForgeLinks.routeFor("https://github.com/paperclipai/paperclip"))
            .isEqualTo(RepoRoute("github.com", "paperclipai", "paperclip"))
        assertThat(ForgeLinks.routeFor("https://github.com/square/okhttp/"))
            .isEqualTo(RepoRoute("github.com", "square", "okhttp"))
        assertThat(ForgeLinks.routeFor("http://www.github.com/square/okhttp#readme"))
            .isEqualTo(RepoRoute("github.com", "square", "okhttp"))
    }

    @Test
    fun deeper_repository_pages_open_the_repo_for_now() {
        assertThat(ForgeLinks.routeFor("https://github.com/square/okhttp/blob/master/README.md"))
            .isEqualTo(RepoRoute("github.com", "square", "okhttp"))
        assertThat(ForgeLinks.routeFor("https://github.com/square/okhttp/issues"))
            .isEqualTo(RepoRoute("github.com", "square", "okhttp"))
    }

    @Test
    fun issue_and_pull_request_urls_open_the_conversation() {
        assertThat(ForgeLinks.routeFor("https://github.com/square/okhttp/issues/42?x=1")).isEqualTo(IssueRoute("github.com", "square", "okhttp", 42))
        assertThat(ForgeLinks.routeFor("https://github.com/square/okhttp/pull/7")).isEqualTo(IssueRoute("github.com", "square", "okhttp", 7))
        assertThat(ForgeLinks.routeFor("https://github.com/square/okhttp/pull/7/files")).isEqualTo(IssueRoute("github.com", "square", "okhttp", 7))
        assertThat(ForgeLinks.routeFor("https://github.com/square/okhttp/issues/42#issuecomment-1")).isEqualTo(IssueRoute("github.com", "square", "okhttp", 42))
    }

    @Test
    fun actions_run_and_job_urls_open_the_run() {
        assertThat(ForgeLinks.routeFor("https://github.com/square/okhttp/actions/runs/36539745670"))
            .isEqualTo(RunRoute("github.com", "square", "okhttp", 36539745670))
        assertThat(ForgeLinks.routeFor("https://github.com/square/okhttp/actions/runs/36539745670/job/109312096849#step:10:1"))
            .isEqualTo(RunRoute("github.com", "square", "okhttp", 36539745670))
        assertThat(ForgeLinks.routeFor("https://github.com/square/okhttp/actions")).isEqualTo(RepoRoute("github.com", "square", "okhttp"))
    }

    @Test
    fun profile_urls_open_the_user() {
        assertThat(ForgeLinks.routeFor("https://github.com/octocat")).isEqualTo(UserRoute("github.com", "octocat"))
        assertThat(ForgeLinks.routeFor("https://github.com/octocat/")).isEqualTo(UserRoute("github.com", "octocat"))
    }

    @Test
    fun a_trailing_git_suffix_is_ignored() {
        assertThat(ForgeLinks.routeFor("https://github.com/square/okhttp.git")).isEqualTo(RepoRoute("github.com", "square", "okhttp"))
    }

    @Test
    fun github_pages_that_are_not_repositories_stay_on_the_web() {
        for (url in listOf(
            "https://github.com/settings/tokens",
            "https://github.com/orgs/square/repositories",
            "https://github.com/marketplace/actions/checkout",
            "https://github.com/topics/android",
            "https://github.com/user-attachments/assets/abc",
            "https://github.com",
        )) {
            assertThat(ForgeLinks.routeFor(url)).isNull()
        }
    }

    @Test
    fun other_hosts_and_non_web_links_stay_outside() {
        assertThat(ForgeLinks.routeFor("https://bitbucket.org/a/b")).isNull()
        assertThat(ForgeLinks.routeFor("https://raw.githubusercontent.com/a/b/main/x.png")).isNull()
        assertThat(ForgeLinks.routeFor("mailto:me@example.com")).isNull()
        assertThat(ForgeLinks.routeFor("#usage")).isNull()
        assertThat(ForgeLinks.routeFor("not a url")).isNull()
    }

    @Test
    fun gitlab_links_open_in_app_on_gitlab() {
        assertThat(ForgeLinks.routeFor("https://gitlab.com/gitlab-org/gitlab")).isEqualTo(RepoRoute("gitlab.com", "gitlab-org", "gitlab"))
        assertThat(ForgeLinks.routeFor("https://gitlab.com/group/subgroup/project")).isEqualTo(RepoRoute("gitlab.com", "group/subgroup", "project"))
        assertThat(ForgeLinks.routeFor("https://gitlab.com/gitlab-org/gitlab/-/issues/42")).isEqualTo(IssueRoute("gitlab.com", "gitlab-org", "gitlab", 42, isPullRequest = false))
        assertThat(ForgeLinks.routeFor("https://gitlab.com/gitlab-org/gitlab/-/merge_requests/7")).isEqualTo(IssueRoute("gitlab.com", "gitlab-org", "gitlab", 7, isPullRequest = true))
        assertThat(ForgeLinks.routeFor("https://gitlab.com/gitlab-org/gitlab/-/pipelines/36539745")).isEqualTo(RunRoute("gitlab.com", "gitlab-org", "gitlab", 36539745))
        assertThat(ForgeLinks.routeFor("https://gitlab.com/gitlab-org/gitlab/-/releases/v1.0.0")).isEqualTo(ReleaseRoute("gitlab.com", "gitlab-org", "gitlab", "v1.0.0"))
        assertThat(ForgeLinks.routeFor("https://gitlab.com/alice")).isEqualTo(UserRoute("gitlab.com", "alice"))
    }

    @Test
    fun gitlab_pages_that_are_reserved_stay_on_the_web() {
        assertThat(ForgeLinks.routeFor("https://gitlab.com/explore")).isNull()
        assertThat(ForgeLinks.routeFor("https://gitlab.com/search")).isNull()
        assertThat(ForgeLinks.routeFor("https://gitlab.com/dashboard")).isNull()
    }

    @Test
    fun codeberg_links_open_in_app_on_codeberg() {
        assertThat(ForgeLinks.routeFor("https://codeberg.org/forgejo/forgejo")).isEqualTo(RepoRoute("codeberg.org", "forgejo", "forgejo"))
        assertThat(ForgeLinks.routeFor("https://codeberg.org/forgejo/forgejo/issues/42")).isEqualTo(IssueRoute("codeberg.org", "forgejo", "forgejo", 42))
        assertThat(ForgeLinks.routeFor("https://codeberg.org/forgejo/forgejo/pulls/7")).isEqualTo(IssueRoute("codeberg.org", "forgejo", "forgejo", 7))
        assertThat(ForgeLinks.routeFor("https://codeberg.org/alice")).isEqualTo(UserRoute("codeberg.org", "alice"))
    }

    @Test
    fun codeberg_pages_that_are_not_people_stay_on_the_web() {
        assertThat(ForgeLinks.routeFor("https://codeberg.org/explore/repos")).isNull()
        assertThat(ForgeLinks.routeFor("https://codeberg.org/user/login")).isNull()
    }

    @Test
    fun a_route_knows_its_forge() {
        assertThat(RepoRoute("codeberg.org", "forgejo", "forgejo").repo.forge).isEqualTo(ForgeInstance.Codeberg)
        assertThat(IssueRoute("github.com", "octo", "repo", 7).issue.repo.forge).isEqualTo(ForgeInstance.GitHub)
    }

    @Test
    fun codeberg_run_links_stay_in_the_browser() {
        // 4235 is the run's number in its repository; the API knows it as 7368141, so the app would open the wrong run.
        assertThat(ForgeLinks.routeFor("https://codeberg.org/forgejo/website/actions/runs/4235")).isNull()
        assertThat(ForgeLinks.routeFor("https://codeberg.org/forgejo/website/actions/runs/4235/jobs/0")).isNull()
    }

    @Test
    fun a_release_s_url_opens_its_page_on_either_forge() {
        assertThat(ForgeLinks.routeFor("https://github.com/RoiArthurB/forgeline/releases/tag/v0.4.0"))
            .isEqualTo(ReleaseRoute("github.com", "RoiArthurB", "forgeline", "v0.4.0"))
        assertThat(ForgeLinks.routeFor("https://codeberg.org/forgejo/forgejo/releases/tag/v16.0.5#notes"))
            .isEqualTo(ReleaseRoute("codeberg.org", "forgejo", "forgejo", "v16.0.5"))
        // A tag may hold slashes.
        assertThat(ForgeLinks.routeFor("https://github.com/octo/repo/releases/tag/desktop/v1.2"))
            .isEqualTo(ReleaseRoute("github.com", "octo", "repo", "desktop/v1.2"))
    }

    @Test
    fun the_releases_list_and_a_download_stay_with_the_repository() {
        assertThat(ForgeLinks.routeFor("https://github.com/octo/repo/releases")).isEqualTo(RepoRoute("github.com", "octo", "repo"))
        assertThat(ForgeLinks.routeFor("https://github.com/octo/repo/releases/tag/")).isEqualTo(RepoRoute("github.com", "octo", "repo"))
        assertThat(ForgeLinks.routeFor("https://github.com/octo/repo/releases/download/v1/app.apk")).isEqualTo(RepoRoute("github.com", "octo", "repo"))
    }

    @Test
    fun a_release_link_opens_the_release_where_there_is_a_page_for_it_and_its_repository_elsewhere() {
        val opened = mutableListOf<String>()
        val url = "https://github.com/octo/repo/releases/tag/v1"
        fun open(onOpenRelease: ((fr.arthurbrugiere.forgeline.core.model.RepoId, String) -> Unit)?) = openForgeLink(
            url, ForgeInstance.GitHub,
            onOpenRepo = { opened += "repo:${it.fullName}" }, onOpenIssue = {}, onOpenUser = {}, openUrl = { opened += "url:$it" },
            onOpenRelease = onOpenRelease,
        )

        open { repo, tag -> opened += "release:${repo.fullName}@$tag" }
        open(null)

        assertThat(opened).containsExactly("release:octo/repo@v1", "repo:octo/repo").inOrder()
    }

    @Test
    fun a_notification_link_opens_the_conversation_at_what_is_new() {
        val url = "https://github.com/acme/rocket/issues/42"

        assertThat(linkRoute(url, unread = true, lastReadAtMillis = 1_000L))
            .isEqualTo(IssueRoute("github.com", "acme", "rocket", 42, unread = true, lastReadAtMillis = 1_000L))
        // Any other link opens what it names from the top.
        assertThat(linkRoute(url)).isEqualTo(IssueRoute("github.com", "acme", "rocket", 42))
        assertThat(linkRoute("https://github.com/acme/rocket", unread = true)).isEqualTo(RepoRoute("github.com", "acme", "rocket"))
        assertThat(linkRoute("https://example.com/acme/rocket/issues/42", unread = true)).isNull()
    }

    @Test
    fun a_thread_opens_its_conversation_where_the_reader_left_it() {
        val lastRead = java.time.Instant.parse("2026-09-26T18:00:00Z")
        val thread = fr.arthurbrugiere.forgeline.core.testing.notificationThread("42", repo = "acme/rocket", title = "Launch fails")

        assertThat(thread.copy(unread = true, lastReadAt = lastRead).route())
            .isEqualTo(IssueRoute("github.com", "acme", "rocket", 42, isPullRequest = false, unread = true, lastReadAtMillis = lastRead.toEpochMilli()))
        // Read already: nothing new to go to.
        assertThat(thread.copy(unread = false).route()).isEqualTo(IssueRoute("github.com", "acme", "rocket", 42, isPullRequest = false))
        // A thread about a pull request says so: on GitLab that is another conversation than the issue of its number.
        assertThat(thread.copy(type = fr.arthurbrugiere.forgeline.core.model.SubjectType.PULL_REQUEST).route()?.isPullRequest).isTrue()
        // Not about a conversation: its repository opens instead.
        assertThat(thread.copy(type = fr.arthurbrugiere.forgeline.core.model.SubjectType.RELEASE).route()).isNull()
    }

    @Test
    fun a_gitlab_issue_addressed_as_a_work_item_opens_as_the_issue() {
        // What gitlab.com's API now gives as an issue's page (seen 2026-10-04).
        assertThat(ForgeLinks.routeFor("https://gitlab.com/RoiArthurB/forgeline-scratch/-/work_items/1"))
            .isEqualTo(IssueRoute("gitlab.com", "RoiArthurB", "forgeline-scratch", 1, isPullRequest = false))
    }

    @Test
    fun a_discussion_s_url_opens_it_on_github_only() {
        // Regression: a discussion's link opened its repository.
        assertThat(ForgeLinks.routeFor("https://github.com/vercel/next.js/discussions/99839#discussioncomment-1"))
            .isEqualTo(DiscussionRoute("github.com", "vercel", "next.js", 99839))
        assertThat(ForgeLinks.routeFor("https://github.com/vercel/next.js/discussions")).isEqualTo(RepoRoute("github.com", "vercel", "next.js"))
        assertThat(ForgeLinks.routeFor("https://github.com/vercel/next.js/discussions/categories/help")).isEqualTo(RepoRoute("github.com", "vercel", "next.js"))
        // Forgejo has none: whatever is at that address is its repository's.
        assertThat(ForgeLinks.routeFor("https://codeberg.org/forgejo/forgejo/discussions/3")).isEqualTo(RepoRoute("codeberg.org", "forgejo", "forgejo"))
    }

    @Test
    fun a_discussion_s_link_opens_in_the_app_where_it_can_and_on_its_forge_otherwise() {
        val events = mutableListOf<String>()
        val url = "https://github.com/vercel/next.js/discussions/7"
        fun open(inApp: Boolean) = openForgeLink(
            url, ForgeInstance.GitHub, onOpenRepo = { events += "repo" }, onOpenIssue = { events += "issue" }, onOpenUser = { events += "user" },
            openUrl = { events += "browser:$it" }, onOpenDiscussion = if (inApp) ({ repo, number -> events += "discussion:${repo.fullName}#$number" }) else null,
        )

        open(inApp = true)
        open(inApp = false)

        assertThat(events).containsExactly("discussion:vercel/next.js#7", "browser:$url").inOrder()
    }
}
