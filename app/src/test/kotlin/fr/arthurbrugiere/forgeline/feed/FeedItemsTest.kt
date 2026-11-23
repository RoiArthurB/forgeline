package fr.arthurbrugiere.forgeline.feed

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.FeedDetailKey
import fr.arthurbrugiere.forgeline.core.model.FeedAction
import fr.arthurbrugiere.forgeline.core.model.FeedKind
import fr.arthurbrugiere.forgeline.core.model.IssueAction
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.PullRequestAction
import fr.arthurbrugiere.forgeline.core.model.ReleaseRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.testing.feedEvent
import org.junit.Test

class FeedItemsTest {
    private val closed = FeedAction.Issue(IssueAction.CLOSED, 42, "Launch fails")

    @Test
    fun keeps_the_timeline_chronological() {
        val items = feedItems(
            listOf(
                feedEvent("3", actor = "alice", repo = "acme/rocket", createdAt = "2026-09-27T09:00:00Z"),
                feedEvent("2", actor = "bob", repo = "octo/tools", action = closed, createdAt = "2026-09-27T08:00:00Z"),
            ),
            FeedKind.defaults,
        )

        assertThat(items.map { it.key }).containsExactly("3", "2").inOrder()
    }

    @Test
    fun identical_events_on_the_same_repo_merge_at_the_newest_one() {
        val items = feedItems(
            listOf(
                feedEvent("5", actor = "alice", repo = "acme/rocket", createdAt = "2026-09-27T09:00:00Z"),
                feedEvent("4", actor = "carol", repo = "octo/tools", action = closed, createdAt = "2026-09-27T08:30:00Z"),
                feedEvent("3", actor = "bob", repo = "acme/rocket", createdAt = "2026-09-27T08:00:00Z"),
                feedEvent("2", actor = "alice", repo = "acme/rocket", createdAt = "2026-09-27T07:00:00Z"),
                feedEvent("1", actor = "dave", repo = "octo/tools", createdAt = "2026-09-27T06:00:00Z"),
            ),
            FeedKind.defaults,
        )

        assertThat(items.map { it.key }).containsExactly("5", "4", "1").inOrder()
        assertThat(items[0].actors.map { it.login }).containsExactly("alice", "bob").inOrder()
        assertThat(items[0].createdAt.toString()).isEqualTo("2026-09-27T09:00:00Z")
        // Starring a different repo is a different event.
        assertThat(items[2].actors.map { it.login }).containsExactly("dave")
    }

    @Test
    fun different_actions_on_the_same_repo_never_merge() {
        val items = feedItems(
            listOf(
                feedEvent("2", actor = "alice", action = FeedAction.Issue(IssueAction.CLOSED, 42, "A")),
                feedEvent("1", actor = "bob", action = FeedAction.Issue(IssueAction.CLOSED, 43, "B")),
            ),
            FeedKind.defaults,
        )

        assertThat(items).hasSize(2)
    }

    @Test
    fun hidden_kinds_are_filtered_out() {
        val items = feedItems(
            listOf(
                feedEvent("2", action = FeedAction.Pushed("main")),
                feedEvent("1", action = closed),
            ),
            setOf(FeedKind.ISSUES_CLOSED),
        )

        assertThat(items.map { it.key }).containsExactly("1")
    }

    @Test
    fun a_comment_without_its_conversations_title_asks_for_it_issue_or_pull_request() {
        // GitHub's comment events name the issue; Codeberg's carry the comment instead, so the title is fetched.
        val onIssue = feedItems(listOf(feedEvent("1", action = FeedAction.Commented(12, null, isPullRequest = false))), FeedKind.entries.toSet()).single()
        val onPull = feedItems(listOf(feedEvent("2", action = FeedAction.Commented(13, null, isPullRequest = true))), FeedKind.entries.toSet()).single()
        val named = feedItems(listOf(feedEvent("3", action = FeedAction.Commented(14, "Launch fails", isPullRequest = false))), FeedKind.entries.toSet()).single()

        assertThat(onIssue.conversation).isEqualTo(IssueRef(RepoId("acme", "rocket"), 12, isPullRequest = false))
        assertThat(onPull.conversation).isEqualTo(IssueRef(RepoId("acme", "rocket"), 13, isPullRequest = true))
        assertThat(named.details).isEmpty()
    }

    @Test
    fun each_row_asks_for_what_it_shows() {
        fun keysOf(action: FeedAction) = feedItems(listOf(feedEvent("1", action = action)), FeedKind.entries.toSet()).single().details
        val rocket = RepoId("acme", "rocket")

        assertThat(keysOf(FeedAction.Starred)).containsExactly(FeedDetailKey.Repo(rocket))
        assertThat(keysOf(FeedAction.CreatedRepo("A fresh idea"))).containsExactly(FeedDetailKey.Repo(rocket))
        // A release's summary, picture and reactions; an issue's labels and comments, even with its title known.
        assertThat(keysOf(FeedAction.Released("v2.0.0", null, prerelease = false))).containsExactly(FeedDetailKey.Release(ReleaseRef(rocket, "v2.0.0")))
        assertThat(keysOf(FeedAction.Issue(IssueAction.OPENED, 8, "Has a title"))).containsExactly(FeedDetailKey.Conversation(IssueRef(rocket, 8, isPullRequest = false)))
        // A pull request's size, even where its event carries the title (Forgejo).
        assertThat(keysOf(FeedAction.PullRequest(PullRequestAction.MERGED, 9, "Titled"))).containsExactly(FeedDetailKey.Conversation(IssueRef(rocket, 9, isPullRequest = true)))
        assertThat(keysOf(FeedAction.Pushed("main"))).isEmpty()
    }

    private fun pull(id: String, action: PullRequestAction, number: Int = 30, at: String, repo: String = "acme/rocket") =
        feedEvent(id, actor = "alice", repo = repo, action = FeedAction.PullRequest(action, number), createdAt = at)

    @Test
    fun a_pull_request_since_merged_shows_only_as_merged() {
        val items = feedItems(
            listOf(
                pull("2", PullRequestAction.MERGED, at = "2026-09-27T09:00:00Z"),
                pull("1", PullRequestAction.OPENED, at = "2026-09-27T08:00:00Z"),
            ),
            FeedKind.defaults,
            hideSettled = true,
        )

        assertThat(items.map { it.key }).containsExactly("2")
    }

    @Test
    fun an_issue_since_closed_shows_only_as_closed() {
        val items = feedItems(
            listOf(
                feedEvent("2", action = closed, createdAt = "2026-09-27T09:00:00Z"),
                feedEvent("1", action = FeedAction.Issue(IssueAction.OPENED, 42, "Launch fails"), createdAt = "2026-09-27T08:00:00Z"),
            ),
            FeedKind.defaults,
            hideSettled = true,
        )

        assertThat(items.map { it.key }).containsExactly("2")
    }

    @Test
    fun an_opening_stays_when_it_came_after_the_close_or_for_another_pull_request() {
        val items = feedItems(
            listOf(
                pull("4", PullRequestAction.REOPENED, at = "2026-09-27T10:00:00Z"),
                pull("3", PullRequestAction.CLOSED, at = "2026-09-27T09:00:00Z"),
                pull("2", PullRequestAction.OPENED, number = 31, at = "2026-09-27T08:30:00Z"),
                pull("1", PullRequestAction.OPENED, at = "2026-09-27T08:00:00Z", repo = "octo/tools"),
            ),
            FeedKind.defaults,
            hideSettled = true,
        )

        assertThat(items.map { it.key }).containsExactly("4", "3", "2", "1").inOrder()
    }

    @Test
    fun an_opening_stays_when_its_merge_is_hidden_or_the_setting_is_off() {
        val events = listOf(
            pull("2", PullRequestAction.MERGED, at = "2026-09-27T09:00:00Z"),
            pull("1", PullRequestAction.OPENED, at = "2026-09-27T08:00:00Z"),
        )

        assertThat(feedItems(events, FeedKind.defaults - FeedKind.PRS_CLOSED, hideSettled = true).map { it.key }).containsExactly("1")
        assertThat(feedItems(events, FeedKind.defaults, hideSettled = false).map { it.key }).containsExactly("2", "1").inOrder()
    }
}
