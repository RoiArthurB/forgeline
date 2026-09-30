package fr.arthurbrugiere.forgeline.feed

import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.model.FeedAction
import fr.arthurbrugiere.forgeline.core.model.FeedKind
import fr.arthurbrugiere.forgeline.core.model.IssueAction
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

        assertThat(onIssue.previewPull).isEqualTo(IssueRef(RepoId("acme", "rocket"), 12))
        assertThat(onPull.previewPull).isEqualTo(IssueRef(RepoId("acme", "rocket"), 13))
        assertThat(named.previewPull).isNull()
    }
}
