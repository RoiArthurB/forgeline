package fr.arthurbrugiere.forgeline.feed

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

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class FeedScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()

    /** Whether the feed sits in the app shell, which has a page for a release. */
    private var hasReleasePage = false

    private fun setContent(state: FeedUiState, previews: FeedPreviews = FeedPreviews()) {
        composeRule.setContent {
            val openRelease: ((fr.arthurbrugiere.forgeline.core.model.RepoId, String) -> Unit)? =
                if (hasReleasePage) ({ repo, tag -> events += "release:${repo.fullName}@$tag" }) else null
            androidx.compose.runtime.CompositionLocalProvider(fr.arthurbrugiere.forgeline.ui.LocalOpenRelease provides openRelease) {
            FeedScreen(
                state = state.copy(previews = previews),
                onRefresh = { events += "refresh" },
                onLoadMore = { events += "more" },
                onOpenRepo = { events += "repo:${it.fullName}" },
                onOpenIssue = { events += "issue:${it.repo.fullName}#${it.number}" },
                onOpenUser = { _, login -> events += "user:$login" },
                onOpenUrl = { events += "url:$it" },
                onErrorShown = {},
                nowMillis = Instant.parse("2026-09-27T10:00:00Z").toEpochMilli(),
                zone = java.time.ZoneOffset.UTC,
            )
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
            FeedPreviews(pullTitles = mapOf(IssueRef(RepoId("acme", "rocket"), 43) to "Retry the fuel pump")),
        )

        composeRule.onNodeWithText("carol closed an issue in acme/\u2060rocket", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Closed").assertIsDisplayed()
        composeRule.onNodeWithText("Launch fails").assertIsDisplayed()
        composeRule.onNodeWithText("bob merged a pull request in acme/\u2060rocket", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Merged").assertIsDisplayed()
        composeRule.onNodeWithText("#43").assertIsDisplayed()
        composeRule.onNodeWithText("Retry the fuel pump").assertIsDisplayed()
        composeRule.onNodeWithText("Approved").assertIsDisplayed()
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
    fun an_empty_feed_explains_where_activity_comes_from() {
        setContent(FeedUiState(syncedAtMillis = 1))

        composeRule.onNodeWithText("Quiet for now").assertIsDisplayed()
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

        composeRule.onNodeWithText("alice starred", substring = true).assertTextContains("Codeberg", substring = true)
        composeRule.onNodeWithText("bob starred", substring = true).assertTextContains("GitHub", substring = true)
    }

    @Test
    fun a_comment_on_an_issue_shows_the_title_fetched_for_it() {
        setContent(
            state(feedEvent("1", actor = "carol", action = FeedAction.Commented(12, null, isPullRequest = false))),
            FeedPreviews(pullTitles = mapOf(IssueRef(RepoId("acme", "rocket"), 12) to "Launch fails")),
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
        composeRule.onNodeWithText("Announcement", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Immich turns three", useUnmergedTree = true).performClick()

        // Forgeline has no discussion screen: the announcement opens on the forge.
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
}
