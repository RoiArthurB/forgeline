package fr.arthurbrugiere.forgeline.feed

import androidx.compose.ui.test.onNodeWithTag
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.ReleaseRef
import fr.arthurbrugiere.forgeline.core.model.ReleasePreview
import androidx.compose.ui.test.assertTextContains
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.FeedAction
import fr.arthurbrugiere.forgeline.core.model.FeedKind
import fr.arthurbrugiere.forgeline.core.model.FeedPreviews
import fr.arthurbrugiere.forgeline.core.model.ConversationPreview
import fr.arthurbrugiere.forgeline.core.model.IssueAction
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.PullRequestAction
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoPreview
import fr.arthurbrugiere.forgeline.core.model.ReviewState
import fr.arthurbrugiere.forgeline.core.testing.feedEvent
import org.junit.Rule
import androidx.compose.ui.test.hasText
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import fr.arthurbrugiere.forgeline.ui.assertEveryTargetIsAtLeast48dp

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class FeedScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()
    private val opened = mutableListOf<fr.arthurbrugiere.forgeline.core.model.IssueRef>()

    /** Whether the feed sits in the app shell, which has a page for a release. */
    private var hasReleasePage = false

    /** What Settings holds for the test; every default unless it says otherwise. */
    private var userSettings = fr.arthurbrugiere.forgeline.core.model.UserSettings()

    private fun setContent(state: FeedUiState, previews: FeedPreviews = FeedPreviews()) {
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(fr.arthurbrugiere.forgeline.ui.LocalUserSettings provides userSettings) {
            val openRelease: ((fr.arthurbrugiere.forgeline.core.model.RepoId, String) -> Unit)? =
                if (hasReleasePage) ({ repo, tag -> events += "release:${repo.fullName}@$tag" }) else null
            val openDiscussion: ((fr.arthurbrugiere.forgeline.core.model.RepoId, Int) -> Unit)? =
                if (hasReleasePage) ({ repo, number -> events += "discussion:${repo.fullName}#$number" }) else null
            androidx.compose.runtime.CompositionLocalProvider(
                fr.arthurbrugiere.forgeline.ui.LocalOpenRelease provides openRelease,
                fr.arthurbrugiere.forgeline.ui.LocalOpenDiscussion provides openDiscussion,
            ) {
            FeedScreen(
                state = state.copy(previews = previews),
                onRefresh = { events += "refresh" },
                onLoadMore = { events += "more" },
                onOpenRepo = { events += "repo:${it.fullName}" },
                onOpenIssue = {
                    events += "issue:${it.repo.fullName}#${it.number}"
                    opened += it
                },
                onOpenUser = { _, login -> events += "user:$login" },
                onOpenUrl = { events += "url:$it" },
                onErrorShown = {},
                nowMillis = Instant.parse("2026-09-27T10:00:00Z").toEpochMilli(),
                zone = java.time.ZoneOffset.UTC,
            )
            }
        }
        }
    }

    @Test
    fun a_release_opens_its_page_and_its_repository_where_there_is_none() {
        hasReleasePage = true
        setContent(state(feedEvent("6", actor = "alice", repo = "octo/tools", action = FeedAction.Released("v2.0.0", "Tools 2.0", prerelease = false))))

        composeRule.onNodeWithText("Tools 2.0").performClick()

        assertThat(events).containsExactly("release:octo/tools@v2.0.0")
    }

    @Test
    fun outside_the_app_shell_a_release_opens_its_repository() {
        setContent(state(feedEvent("6", actor = "alice", repo = "octo/tools", action = FeedAction.Released("v2.0.0", "Tools 2.0", prerelease = false))))

        composeRule.onNodeWithText("Tools 2.0").performClick()

        assertThat(events).containsExactly("repo:octo/tools")
    }

    private fun state(vararg events: fr.arthurbrugiere.forgeline.core.model.FeedEvent, hasMore: Boolean = false) =
        FeedUiState(items = feedItems(events.toList(), FeedKind.entries.toSet()), syncedAtMillis = 1, hasMore = hasMore)

    @Test
    fun where_you_left_off_sits_above_the_last_read_activity() {
        setContent(
            state(feedEvent("2", repo = "octo/new", createdAt = "2026-09-27T09:30:00Z"), feedEvent("1", repo = "octo/read"))
                .copy(leftOffBefore = "1"),
        )

        val mark = composeRule.onNodeWithText("Where you left off").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val newer = composeRule.onNodeWithText("octo/\u2060new").fetchSemanticsNode().boundsInRoot
        val read = composeRule.onNodeWithText("octo/\u2060read").fetchSemanticsNode().boundsInRoot
        assertThat(mark.top).isAtLeast(newer.bottom)
        assertThat(read.top).isAtLeast(mark.bottom)
    }

    @Test
    fun merged_stars_name_everyone() {
        setContent(
            state(
                feedEvent("3", actor = "alice"),
                feedEvent("2", actor = "bob"),
                feedEvent("1", actor = "carol", repo = "octo/tools"),
                feedEvent("0", actor = "dave", repo = "octo/tools"),
            ),
        )

        composeRule.onNodeWithText("alice and bob starred\u00A0· 1\u00A0hr.\u00A0ago", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("carol and dave starred", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("acme/\u2060rocket").assertIsDisplayed()
        composeRule.onNodeWithText("octo/\u2060tools").assertIsDisplayed()
    }

    @Test
    fun many_stars_are_counted() {
        setContent(state(feedEvent("3", actor = "alice"), feedEvent("2", actor = "bob"), feedEvent("1", actor = "carol")))

        composeRule.onNodeWithText("alice and 2 others starred", substring = true).assertIsDisplayed()
    }

    @Test
    fun each_event_leads_with_its_kind_and_object() {
        setContent(
            state(
                feedEvent("9", actor = "carol", action = FeedAction.Issue(IssueAction.CLOSED, 42, "Launch fails")),
                feedEvent("8", actor = "bob", action = FeedAction.PullRequest(PullRequestAction.MERGED, 43)),
                feedEvent("7", actor = "bob", action = FeedAction.Reviewed(44, ReviewState.APPROVED)),
                feedEvent("6", actor = "alice", repo = "octo/tools", action = FeedAction.Released("v2.0.0", "Tools 2.0", prerelease = false)),
                feedEvent("5", actor = "carol", repo = "octo/tools", action = FeedAction.Forked(RepoId("carol", "tools"))),
                feedEvent("4", actor = "alice", repo = "alice/idea", action = FeedAction.CreatedRepo("A fresh idea")),
                feedEvent("3", actor = "alice", repo = "alice/dotfiles", action = FeedAction.Pushed("main")),
            ),
            FeedPreviews(conversations = mapOf(IssueRef(RepoId("acme", "rocket"), 43) to ConversationPreview("Retry the fuel pump"))),
        )

        composeRule.onNodeWithText("carol closed an issue in acme/\u2060rocket", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Closed #42").assertIsDisplayed()
        composeRule.onNodeWithText("Launch fails").assertIsDisplayed()
        composeRule.onNodeWithText("bob merged a pull request in acme/\u2060rocket", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Merged #43").assertIsDisplayed()
        composeRule.onNodeWithText("Retry the fuel pump").assertIsDisplayed()
        composeRule.onNodeWithText("Approved #44").assertIsDisplayed()
        composeRule.onNodeWithText("v2.0.0").assertIsDisplayed()
        composeRule.onNodeWithText("Tools 2.0").assertIsDisplayed()
        composeRule.onNodeWithText("carol forked to carol/\u2060tools", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("A fresh idea").assertIsDisplayed()
        composeRule.onNodeWithText("main").assertIsDisplayed()
    }

    @Test
    fun starred_repos_show_their_preview() {
        setContent(
            state(feedEvent("1")),
            FeedPreviews(repos = mapOf(RepoId("acme", "rocket") to RepoPreview("Tiny satellites, in Rust.", "Rust", 12_400))),
        )

        composeRule.onNodeWithText("Tiny satellites, in Rust.").assertIsDisplayed()
        composeRule.onNodeWithText("Rust").assertIsDisplayed()
        composeRule.onNodeWithText("12.4k", substring = true).assertIsDisplayed()
    }

    @Test
    fun events_are_grouped_by_day() {
        setContent(
            state(
                feedEvent("3", createdAt = "2026-09-27T09:00:00Z"),
                feedEvent("2", actor = "bob", repo = "octo/tools", createdAt = "2026-09-20T09:00:00Z"),
            ),
        )

        composeRule.onNodeWithText("Today").assertIsDisplayed()
        composeRule.onNodeWithText("Earlier").assertIsDisplayed()
    }

    @Test
    fun visible_rows_ask_for_their_previews() {
        val visible = mutableListOf<String>()
        composeRule.setContent {
            FeedScreen(
                state = state(feedEvent("2", action = FeedAction.PullRequest(PullRequestAction.OPENED, 7)), feedEvent("1", repo = "octo/tools")),
                onRefresh = {}, onLoadMore = {}, onOpenRepo = {}, onOpenIssue = {}, onOpenUser = { _, _ -> }, onErrorShown = {},
                onVisible = { items -> visible += items.map { it.key } },
                nowMillis = Instant.parse("2026-09-27T10:00:00Z").toEpochMilli(),
                zone = java.time.ZoneOffset.UTC,
            )
        }

        composeRule.waitForIdle()
        assertThat(visible).containsAtLeast("2", "1")
    }

    @Test
    fun rows_open_what_they_are_about() {
        setContent(
            state(
                feedEvent("3", actor = "carol", action = FeedAction.Issue(IssueAction.CLOSED, 42, "Launch fails")),
                feedEvent("2", actor = "carol", repo = "octo/tools", action = FeedAction.Forked(RepoId("carol", "tools"))),
                feedEvent("1", actor = "alice", repo = "octo/tools"),
            ),
        )

        composeRule.onNodeWithText("Launch fails").performClick()
        composeRule.onNodeWithText("carol forked to carol/\u2060tools", substring = true).performClick()
        composeRule.onNodeWithText("alice starred", substring = true).performClick()
        composeRule.onNode(hasContentDescription("alice")).performClick()

        assertThat(events).containsExactly("issue:acme/rocket#42", "repo:carol/tools", "repo:octo/tools", "user:alice").inOrder()
    }

    @Test
    fun reaching_the_end_loads_older_activity() {
        setContent(state(feedEvent("1"), hasMore = true))

        composeRule.waitForIdle()
        assertThat(events).contains("more")
    }

    @Test
    fun an_empty_feed_explains_where_activity_comes_from_and_where_to_find_people_to_follow() {
        var browsed = false
        composeRule.setContent {
            FeedScreen(
                state = FeedUiState(syncedAtMillis = 1),
                onRefresh = {}, onLoadMore = {}, onOpenRepo = {}, onOpenIssue = {}, onOpenUser = { _, _ -> }, onErrorShown = {},
                onBrowseTrending = { browsed = true },
            )
        }

        composeRule.onNodeWithText("Quiet for now").assertIsDisplayed()
        composeRule.onNodeWithText("Browse Trending").performClick()
        assertThat(browsed).isTrue()
    }

    @Test
    fun a_failed_first_load_offers_a_retry() {
        setContent(FeedUiState(error = ForgeError.Network))

        composeRule.onNodeWithText("Retry").performClick()
        assertThat(events).containsExactly("refresh")
    }

    @Test
    fun with_several_forges_each_row_names_its_forge() {
        val codeberg = feedEvent("2", actor = "alice", repo = "forgejo/forgejo").let { it.copy(repo = it.repo.copy(forge = ForgeInstance.Codeberg)) }
        setContent(state(codeberg, feedEvent("1", actor = "bob")).copy(showForge = true))

        // The forge's logo badges the actor's face; screen readers hear it with the rest of the row.
        composeRule.onNode(hasText("alice starred", substring = true) and hasContentDescription("Codeberg")).assertExists()
        composeRule.onNode(hasText("bob starred", substring = true) and hasContentDescription("GitHub")).assertExists()
    }

    @Test
    fun a_comment_on_an_issue_shows_the_title_fetched_for_it() {
        setContent(
            state(feedEvent("1", actor = "carol", action = FeedAction.Commented(12, null, isPullRequest = false))),
            FeedPreviews(conversations = mapOf(IssueRef(RepoId("acme", "rocket"), 12) to ConversationPreview("Launch fails"))),
        )

        composeRule.onNodeWithText("Launch fails").assertIsDisplayed()
    }

    @Test
    fun the_time_dot_never_starts_a_line() {
        // Regression: a wrap before "·" left it alone at the start of the second line. It now sticks to what precedes it.
        setContent(FeedUiState(items = feedItems(listOf(feedEvent("1", actor = "bob", repo = "acme/rocket")), FeedKind.defaults), syncedAtMillis = 1))

        val line = composeRule.onNode(hasText("bob", substring = true) and hasText("ago", substring = true)).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString()
        assertThat(line).doesNotContain(" ·")
        assertThat(line).contains("\u00A0·")
    }

    @Test
    fun an_announcement_from_a_starred_repository_reads_as_one_and_opens_its_discussion() {
        val announced = feedEvent("a", actor = "alextran", repo = "immich-app/immich", action = FeedAction.Announced(880, "Immich turns three"))
        setContent(FeedUiState(items = feedItems(listOf(announced), FeedKind.defaults), syncedAtMillis = 1))

        composeRule.onNodeWithText("alextran announced in immich-app/\u2060immich", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Announcement #880", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Immich turns three", useUnmergedTree = true).performClick()

        // Outside the app shell there is no discussion page: the announcement opens on the forge.
        assertThat(events).containsExactly("url:https://github.com/immich-app/immich/discussions/880")
    }

    @Test
    fun a_pre_release_can_be_switched_off_on_its_own() {
        val stable = feedEvent("1", repo = "octo/tools", action = FeedAction.Released("v2.0.0", null, prerelease = false))
        val candidate = feedEvent("2", repo = "octo/next", action = FeedAction.Released("v3.0.0-rc.1", null, prerelease = true))

        val items = feedItems(listOf(stable, candidate), FeedKind.defaults - FeedKind.PRERELEASES)

        assertThat(items.map { it.repo.name }).containsExactly("tools")
        assertThat(feedItems(listOf(stable, candidate), FeedKind.defaults).map { it.repo.name }).containsExactly("tools", "next")
    }

    @Test
    fun pull_request_titles_display_for_repositories_with_uppercase_names() {
        setContent(
            state(
                feedEvent("1", actor = "octocat", repo = "RoiArthurB/forgeline", action = FeedAction.PullRequest(PullRequestAction.OPENED, 4)),
            ),
            FeedPreviews(conversations = mapOf(IssueRef(RepoId("roiarthurb", "forgeline"), 4) to ConversationPreview("Fix PR title display bug"))),
        )

        composeRule.onNodeWithText("octocat opened a pull request in RoiArthurB/\u2060forgeline", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Open #4").assertIsDisplayed()
        composeRule.onNodeWithText("Fix PR title display bug").assertIsDisplayed()
    }

    @Test
    fun a_row_opens_its_conversation_as_the_kind_its_event_says() {
        // Regression: only the number was passed on. On GitLab a merge request and an issue can share theirs.
        setContent(
            state(
                feedEvent("3", actor = "alice", repo = "octo/tools", action = FeedAction.PullRequest(fr.arthurbrugiere.forgeline.core.model.PullRequestAction.OPENED, 7, "Keep install flags")),
                feedEvent("2", actor = "bob", repo = "octo/tools", action = FeedAction.Issue(fr.arthurbrugiere.forgeline.core.model.IssueAction.OPENED, 7, "Launch fails")),
                feedEvent("1", actor = "carol", repo = "octo/tools", action = FeedAction.Commented(9, "Flaky upload", isPullRequest = true)),
            ),
        )

        composeRule.onNodeWithText("Keep install flags", substring = true).performClick()
        composeRule.onNodeWithText("Launch fails", substring = true).performClick()
        composeRule.onNodeWithText("Flaky upload", substring = true).performClick()

        assertThat(opened.map { it.number to it.isPullRequest }).containsExactly(7 to true, 7 to false, 9 to true).inOrder()
    }

    @Test
    fun a_repositorys_name_gives_way_step_by_step_when_a_line_has_no_room_for_it() {
        // Regression: GitLab's nested paths are long and never broken across lines. One that took the line pushed the
        // forge's logo and the time out of sight; the name now shortens first.
        val nested = "gitlab-org/ci-cd/release-tooling/runner-tools"

        assertThat(feedRepoLabel(nested, 0)).isEqualTo(nested)
        assertThat(feedRepoLabel(nested, 1)).isEqualTo("gitlab-org/…/runner-tools")
        assertThat(feedRepoLabel(nested, 2)).isEqualTo("runner-tools")
        // An owner and a name have no middle to drop: whole, then the name alone.
        assertThat(feedRepoLabel("acme/rocket", 1)).isEqualTo("acme/rocket")
        assertThat(feedRepoLabel("acme/rocket", 2)).isEqualTo("rocket")
        // Past the last step the name stays at its shortest: the row takes another line instead.
        assertThat(feedRepoLabel(nested, 3)).isEqualTo("runner-tools")
    }

    @Test
    fun a_step_that_changes_nothing_is_skipped() {
        // Regression: an owner/name has no middle to drop, so the first step left the line as it was. Nothing was laid
        // out again, and the row stayed cut: seen on a phone with a long GitLab project and a long GitHub one.
        assertThat(nextFeedSqueeze("RoiArthurB/forgeline-scratch-deletion_scheduled-87209551", 0)).isEqualTo(2)
        assertThat(nextFeedSqueeze("gitlab-org/ci-cd/runner-tools", 0)).isEqualTo(1)
        assertThat(nextFeedSqueeze("gitlab-org/ci-cd/runner-tools", 1)).isEqualTo(2)
        // After the shortest name, one more line; after that, nothing more to give.
        assertThat(nextFeedSqueeze("acme/rocket", 2)).isEqualTo(3)
        assertThat(nextFeedSqueeze("acme/rocket", 3)).isEqualTo(3)
        // A name with no owner at all can only take the line.
        assertThat(nextFeedSqueeze("rocket", 0)).isEqualTo(3)
    }

    @Test
    fun in_the_app_shell_an_announcement_opens_its_discussion_s_page() {
        // Regression: an announcement always left the app for the forge's site.
        hasReleasePage = true
        val announced = feedEvent("a", actor = "alextran", repo = "immich-app/immich", action = FeedAction.Announced(880, "Immich turns three"))
        setContent(FeedUiState(items = feedItems(listOf(announced), FeedKind.defaults), syncedAtMillis = 1))

        composeRule.onNodeWithText("Immich turns three", useUnmergedTree = true).performClick()

        assertThat(events).containsExactly("discussion:immich-app/immich#880")
    }

    @Test
    fun with_the_marks_switched_off_the_feed_shows_no_mark() {
        userSettings = userSettings.copy(readingMarks = false)
        setContent(
            state(feedEvent("2", repo = "octo/new", createdAt = "2026-09-27T09:30:00Z"), feedEvent("1", repo = "octo/read"))
                .copy(leftOffBefore = "1"),
        )

        composeRule.onNodeWithText("Where you left off").assertDoesNotExist()
        composeRule.onNodeWithText("octo/\u2060read").assertIsDisplayed()
    }

    /** On a screen tall enough to hold it all: a target cut by the edge of a shorter one measures as less than it is. */
    @Test
    @Config(qualifiers = "w411dp-h2400dp-xhdpi")
    fun every_target_is_48dp_at_least() {
        hasReleasePage = true
        setContent(
            state(
                feedEvent("7", actor = "bob", repo = "octo/new", createdAt = "2026-09-27T09:30:00Z"),
                feedEvent("6", actor = "alice", repo = "octo/tools", action = FeedAction.Released("v2.0.0", "Tools 2.0", prerelease = false)),
                hasMore = true,
            ),
        )
        composeRule.assertEveryTargetIsAtLeast48dp()
    }

    private val tools = RepoId("octo", "tools")
    private val released = feedEvent("r", actor = "alice", repo = "octo/tools", action = FeedAction.Released("v2.0.0", null, prerelease = false))
    private val releasePreview = ReleasePreview(
        summary = "Every command starts twice as fast.",
        previousTag = "v1.9.2",
        imageUrl = "https://example.org/card.png",
        logoUrl = "https://example.org/logo.png",
        reactions = mapOf(Reaction.HOORAY to 31, Reaction.EYES to 2, Reaction.ROCKET to 12, Reaction.HEART to 5, Reaction.LAUGH to 1),
    )

    @Test
    fun a_release_names_its_project_says_its_step_and_sums_up_its_notes() {
        setContent(state(released), FeedPreviews(releases = mapOf(ReleaseRef(tools, "v2.0.0") to releasePreview)))

        composeRule.onNodeWithText("octo/\u2060tools released", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("v2.0.0").assertIsDisplayed()
        composeRule.onNodeWithText("Major").assertIsDisplayed()
        composeRule.onNodeWithText("Every command starts twice as fast.").assertIsDisplayed()
        composeRule.onNodeWithText("Notes").assertIsDisplayed()
        // The four reactions given most, most first; a fifth would only crowd the line.
        composeRule.onNode(hasContentDescription("Hooray, 31; Rocket, 12; Heart, 5; Eyes, 2")).assertExists()
    }

    @Test
    fun a_pre_release_says_so_beside_its_step() {
        val candidate = feedEvent("p", repo = "octo/tools", action = FeedAction.Released("v2.1.0-rc.1", null, prerelease = true))
        setContent(state(candidate), FeedPreviews(releases = mapOf(ReleaseRef(tools, "v2.1.0-rc.1") to releasePreview.copy(previousTag = "v2.0.0"))))

        composeRule.onNodeWithText("Minor · Pre-release").assertIsDisplayed()
    }

    @Test
    fun a_release_shows_its_project_s_picture_unless_pictures_are_switched_off() {
        setContent(state(released), FeedPreviews(releases = mapOf(ReleaseRef(tools, "v2.0.0") to releasePreview)))
        composeRule.onNodeWithTag(RELEASE_PICTURE_TAG, useUnmergedTree = true).assertExists()
    }

    @Test
    fun with_pictures_switched_off_a_release_is_text_only() {
        userSettings = userSettings.copy(feedPictures = false)
        setContent(state(released), FeedPreviews(releases = mapOf(ReleaseRef(tools, "v2.0.0") to releasePreview)))

        composeRule.onNodeWithTag(RELEASE_PICTURE_TAG, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("Every command starts twice as fast.").assertIsDisplayed()
    }

    @Test
    fun a_release_still_on_its_way_keeps_its_version_and_waits_for_the_rest() {
        setContent(state(released))

        composeRule.onNodeWithText("v2.0.0").assertIsDisplayed()
        composeRule.onNodeWithText("Notes").assertDoesNotExist()
        composeRule.onNodeWithTag(RELEASE_PICTURE_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun a_pull_request_shows_its_size_and_an_issue_its_labels_and_comments() {
        setContent(
            state(
                feedEvent("2", actor = "bob", action = FeedAction.PullRequest(PullRequestAction.MERGED, 43)),
                feedEvent("1", actor = "carol", action = FeedAction.Issue(IssueAction.OPENED, 42, "Launch fails")),
            ),
            FeedPreviews(
                conversations = mapOf(
                    IssueRef(RepoId("acme", "rocket"), 43, isPullRequest = true) to ConversationPreview("Retry", additions = 212, deletions = 40, changedFiles = 7),
                    IssueRef(RepoId("acme", "rocket"), 42, isPullRequest = false) to ConversationPreview(
                        "Launch fails", labels = listOf(fr.arthurbrugiere.forgeline.core.model.Label("bug", "d73a4a")), comments = 4,
                    ),
                ),
            ),
        )

        composeRule.onNode(hasContentDescription("212 lines added, 40 removed")).assertExists()
        composeRule.onNodeWithText("7 files").assertIsDisplayed()
        composeRule.onNodeWithText("bug").assertIsDisplayed()
        composeRule.onNode(hasContentDescription("4 comments")).assertExists()
    }

    @Test
    fun a_starred_repository_says_when_it_last_moved() {
        setContent(
            state(feedEvent("1")),
            FeedPreviews(repos = mapOf(RepoId("acme", "rocket") to RepoPreview("Rockets.", "Rust", 3, Instant.parse("2026-09-27T09:40:00Z")))),
        )

        composeRule.onNodeWithText("Updated 20 minutes ago").assertIsDisplayed()
    }
}
