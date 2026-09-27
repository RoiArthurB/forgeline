package fr.arthurbrugiere.forgeline.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ForgeLinksTest {
    @Test
    fun repository_urls_open_the_repo_in_app() {
        assertThat(ForgeLinks.routeFor("https://github.com/paperclipai/paperclip"))
            .isEqualTo(RepoRoute("paperclipai", "paperclip"))
        assertThat(ForgeLinks.routeFor("https://github.com/square/okhttp/"))
            .isEqualTo(RepoRoute("square", "okhttp"))
        assertThat(ForgeLinks.routeFor("http://www.github.com/square/okhttp#readme"))
            .isEqualTo(RepoRoute("square", "okhttp"))
    }

    @Test
    fun deeper_repository_pages_open_the_repo_for_now() {
        assertThat(ForgeLinks.routeFor("https://github.com/square/okhttp/blob/master/README.md"))
            .isEqualTo(RepoRoute("square", "okhttp"))
        assertThat(ForgeLinks.routeFor("https://github.com/square/okhttp/issues"))
            .isEqualTo(RepoRoute("square", "okhttp"))
    }

    @Test
    fun issue_and_pull_request_urls_open_the_conversation() {
        assertThat(ForgeLinks.routeFor("https://github.com/square/okhttp/issues/42?x=1")).isEqualTo(IssueRoute("square", "okhttp", 42))
        assertThat(ForgeLinks.routeFor("https://github.com/square/okhttp/pull/7")).isEqualTo(IssueRoute("square", "okhttp", 7))
        assertThat(ForgeLinks.routeFor("https://github.com/square/okhttp/pull/7/files")).isEqualTo(IssueRoute("square", "okhttp", 7))
        assertThat(ForgeLinks.routeFor("https://github.com/square/okhttp/issues/42#issuecomment-1")).isEqualTo(IssueRoute("square", "okhttp", 42))
    }

    @Test
    fun profile_urls_open_the_user() {
        assertThat(ForgeLinks.routeFor("https://github.com/octocat")).isEqualTo(UserRoute("octocat"))
        assertThat(ForgeLinks.routeFor("https://github.com/octocat/")).isEqualTo(UserRoute("octocat"))
    }

    @Test
    fun a_trailing_git_suffix_is_ignored() {
        assertThat(ForgeLinks.routeFor("https://github.com/square/okhttp.git")).isEqualTo(RepoRoute("square", "okhttp"))
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
        assertThat(ForgeLinks.routeFor("https://gitlab.com/a/b")).isNull()
        assertThat(ForgeLinks.routeFor("https://raw.githubusercontent.com/a/b/main/x.png")).isNull()
        assertThat(ForgeLinks.routeFor("mailto:me@example.com")).isNull()
        assertThat(ForgeLinks.routeFor("#usage")).isNull()
        assertThat(ForgeLinks.routeFor("not a url")).isNull()
    }
}
