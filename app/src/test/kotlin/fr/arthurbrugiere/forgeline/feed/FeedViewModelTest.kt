package fr.arthurbrugiere.forgeline.feed

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.FeedAction
import fr.arthurbrugiere.forgeline.core.model.FeedKind
import fr.arthurbrugiere.forgeline.core.model.FeedPreviews
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.PullRequestAction
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoPreview
import fr.arthurbrugiere.forgeline.core.testing.FakeFeedPreviewRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeFeedRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeUserSettingsRepository
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import fr.arthurbrugiere.forgeline.core.testing.feedEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FeedViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val feed = FakeFeedRepository()
    private val previews = FakeFeedPreviewRepository()
    private val settings = FakeUserSettingsRepository()

    private fun test(block: suspend TestScope.() -> Unit) = runTest(mainDispatcherRule.testDispatcher) { block() }

    private fun TestScope.viewModel() = FeedViewModel(feed, previews, settings).also { it.state.launchIn(backgroundScope) }

    @Test
    fun shows_the_cached_feed_and_refreshes_it() = test {
        feed.set(feedEvent("2", actor = "alice"), feedEvent("1", actor = "bob", repo = "octo/tools"), hasMore = true)

        val viewModel = viewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.items.map { it.key }).containsExactly("2", "1").inOrder()
        assertThat(state.hasMore).isTrue()
        assertThat(state.isRefreshing).isFalse()
        assertThat(feed.refreshes).containsExactly(false)
    }

    @Test
    fun follows_the_kinds_chosen_in_settings() = test {
        feed.set(feedEvent("2", action = FeedAction.Pushed("main")), feedEvent("1"))
        val viewModel = viewModel()
        advanceUntilIdle()
        assertThat(viewModel.state.value.items.map { it.key }).containsExactly("1")

        settings.setFeedKindShown(FeedKind.PUSHES, true)
        advanceUntilIdle()
        assertThat(viewModel.state.value.items.map { it.key }).containsExactly("2", "1").inOrder()
    }

    @Test
    fun pull_to_refresh_forces_a_reload() = test {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.refresh()
        advanceUntilIdle()

        assertThat(feed.refreshes).containsExactly(false, true).inOrder()
    }

    @Test
    fun failures_are_reported_until_shown() = test {
        feed.failure = ForgeError.Network
        val viewModel = viewModel()
        advanceUntilIdle()
        assertThat(viewModel.state.value.error).isEqualTo(ForgeError.Network)

        viewModel.errorShown()
        advanceUntilIdle()
        assertThat(viewModel.state.value.error).isNull()
    }

    @Test
    fun loads_older_activity_once_at_a_time() = test {
        feed.set(feedEvent("2"), hasMore = true)
        feed.olderPage = listOf(feedEvent("1", repo = "octo/tools", createdAt = "2026-09-27T08:00:00Z"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.loadMore()
        viewModel.loadMore()
        advanceUntilIdle()

        assertThat(feed.loadMoreCalls).isEqualTo(1)
        assertThat(viewModel.state.value.items.map { it.key }).containsExactly("2", "1").inOrder()
        assertThat(viewModel.state.value.hasMore).isFalse()
    }

    @Test
    fun visible_rows_ask_only_for_the_previews_they_show() = test {
        feed.set(
            feedEvent("3", action = FeedAction.PullRequest(PullRequestAction.OPENED, 7)),
            feedEvent("2", repo = "octo/tools"),
            feedEvent("1", action = FeedAction.Issue(fr.arthurbrugiere.forgeline.core.model.IssueAction.OPENED, 8, "Has a title")),
        )
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onVisible(viewModel.state.value.items)
        advanceUntilIdle()

        assertThat(previews.requestedRepos).containsExactly(RepoId("octo", "tools"))
        assertThat(previews.requestedPulls).containsExactly(IssueRef(RepoId("acme", "rocket"), 7))
    }

    @Test
    fun previews_reach_the_state_as_they_arrive() = test {
        val viewModel = viewModel()
        advanceUntilIdle()

        val arrived = FeedPreviews(repos = mapOf(RepoId("acme", "rocket") to RepoPreview("Rockets", "Rust", 3)))
        previews.previews.value = arrived
        advanceUntilIdle()

        assertThat(viewModel.state.value.previews).isEqualTo(arrived)
    }
}
