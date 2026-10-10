package fr.arthurbrugiere.forgeline.trending

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.trending.RefreshResult
import fr.arthurbrugiere.forgeline.core.data.trending.TrendingSnapshot
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeStarRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeTrendingRepository
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import fr.arthurbrugiere.forgeline.core.testing.trendingRepo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrendingViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val trending = FakeTrendingRepository()
    private val stars = FakeStarRepository()
    private val accounts = FakeAccountRepository()
    private val settings = fr.arthurbrugiere.forgeline.core.testing.FakeUserSettingsRepository()
    private val paperclip = trendingRepo("paperclipai/paperclip")
    private val hindsight = trendingRepo("vectorize-io/hindsight")

    private fun test(block: suspend TestScope.() -> Unit) = runTest(mainDispatcherRule.testDispatcher) { block() }

    private fun TestScope.viewModel(savedState: SavedStateHandle = SavedStateHandle()): TrendingViewModel =
        TrendingViewModel(savedState, trending, stars, accounts, settings).also { it.state.launchIn(backgroundScope) }

    private suspend fun signIn() = accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "t")

    @Test
    fun shows_the_cached_ranking_and_refreshes_it_if_stale() = test {
        trending.snapshots.getValue(TrendingPeriod.DAILY).value = TrendingSnapshot(listOf(paperclip, hindsight), 1_000)

        val viewModel = viewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.period).isEqualTo(TrendingPeriod.DAILY)
        assertThat(state.items.map { it.repo }).containsExactly(paperclip, hindsight).inOrder()
        assertThat(state.updatedAtMillis).isEqualTo(1_000)
        assertThat(trending.refreshes).containsExactly(TrendingPeriod.DAILY to false)
        assertThat(state.isRefreshing).isFalse()
    }

    @Test
    fun switching_period_shows_that_ranking_refreshes_it_and_survives_process_death() = test {
        trending.snapshots.getValue(TrendingPeriod.WEEKLY).value = TrendingSnapshot(listOf(hindsight), 2_000)
        val savedState = SavedStateHandle()
        val viewModel = viewModel(savedState)
        advanceUntilIdle()

        viewModel.selectPeriod(TrendingPeriod.WEEKLY)
        advanceUntilIdle()

        assertThat(viewModel.state.value.period).isEqualTo(TrendingPeriod.WEEKLY)
        assertThat(viewModel.state.value.items.map { it.repo }).containsExactly(hindsight)
        assertThat(trending.refreshes.last()).isEqualTo(TrendingPeriod.WEEKLY to false)
        assertThat(viewModel(savedState).state.value.period).isEqualTo(TrendingPeriod.WEEKLY)
    }

    @Test
    fun pull_to_refresh_forces_a_fetch() = test {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.refresh()
        advanceUntilIdle()

        assertThat(trending.refreshes.last()).isEqualTo(TrendingPeriod.DAILY to true)
    }

    @Test
    fun a_failed_refresh_is_reported_and_keeps_the_list() = test {
        trending.snapshots.getValue(TrendingPeriod.DAILY).value = TrendingSnapshot(listOf(paperclip), 1_000)
        trending.nextResult = RefreshResult.Failed(ForgeError.Network)
        val viewModel = viewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isEqualTo(ForgeError.Network)
        assertThat(viewModel.state.value.items).isNotEmpty()

        viewModel.errorShown()
        runCurrent()
        assertThat(viewModel.state.value.error).isNull()
    }

    @Test
    fun signed_out_star_states_are_unknown() = test {
        stars.signedIn = false
        trending.snapshots.getValue(TrendingPeriod.DAILY).value = TrendingSnapshot(listOf(paperclip), 1_000)
        val viewModel = viewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.items.single().starred).isNull()
    }

    @Test
    fun signed_in_star_states_are_loaded_for_the_listed_repos() = test {
        signIn()
        stars.starred += paperclip.id
        trending.snapshots.getValue(TrendingPeriod.DAILY).value = TrendingSnapshot(listOf(paperclip, hindsight), 1_000)
        val viewModel = viewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.items.map { it.starred }).containsExactly(true, false).inOrder()
        assertThat(stars.lookups.single()).containsExactly(paperclip.id, hindsight.id)
    }

    @Test
    fun signing_in_later_loads_star_states() = test {
        trending.snapshots.getValue(TrendingPeriod.DAILY).value = TrendingSnapshot(listOf(paperclip), 1_000)
        stars.starred += paperclip.id
        val viewModel = viewModel()
        advanceUntilIdle()

        signIn()
        advanceUntilIdle()

        assertThat(viewModel.state.value.items.single().starred).isTrue()
    }

    @Test
    fun starring_is_instant_and_persisted() = test {
        signIn()
        trending.snapshots.getValue(TrendingPeriod.DAILY).value = TrendingSnapshot(listOf(paperclip), 1_000)
        val viewModel = viewModel()
        advanceUntilIdle()

        val gate = kotlinx.coroutines.CompletableDeferred<Unit>().also { stars.gate = it }

        viewModel.toggleStar(paperclip.id)
        runCurrent()

        // Shown as starred while GitHub hasn't answered yet.
        assertThat(viewModel.state.value.items.single().starred).isTrue()
        assertThat(stars.starred).isEmpty()
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(stars.starred).containsExactly(paperclip.id)
    }

    @Test
    fun a_failed_star_is_rolled_back_and_reported() = test {
        signIn()
        trending.snapshots.getValue(TrendingPeriod.DAILY).value = TrendingSnapshot(listOf(paperclip), 1_000)
        stars.setFailure = ForgeError.Network
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.toggleStar(paperclip.id)
        advanceUntilIdle()

        assertThat(viewModel.state.value.items.single().starred).isFalse()
        assertThat(viewModel.state.value.starFailed).isTrue()
        viewModel.starFailureShown()
        runCurrent()
        assertThat(viewModel.state.value.starFailed).isFalse()
    }

    @Test
    fun the_resume_mark_is_fixed_for_the_visit_while_reading_moves_the_stored_one() = test {
        trending.snapshots.getValue(TrendingPeriod.DAILY).value = TrendingSnapshot(listOf(paperclip, hindsight), 1_000)
        trending.marks[TrendingPeriod.DAILY] = paperclip.id to 0
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.readThrough(1)
        advanceUntilIdle()

        assertThat(viewModel.state.value.resumeAt).isEqualTo(0)
        assertThat(trending.marks[TrendingPeriod.DAILY]).isEqualTo(hindsight.id to 1)
    }

    @Test
    fun the_mark_follows_its_repo_when_the_list_reorders() = test {
        // Regression: the mark was a rank, so after a refresh it pointed at whichever repo took that place.
        trending.snapshots.getValue(TrendingPeriod.DAILY).value = TrendingSnapshot(listOf(paperclip, hindsight), 1_000)
        trending.marks[TrendingPeriod.DAILY] = paperclip.id to 0
        val viewModel = viewModel()
        advanceUntilIdle()

        trending.snapshots.getValue(TrendingPeriod.DAILY).value = TrendingSnapshot(listOf(hindsight, paperclip), 2_000)
        advanceUntilIdle()
        assertThat(viewModel.state.value.resumeAt).isEqualTo(1)

        trending.snapshots.getValue(TrendingPeriod.DAILY).value = TrendingSnapshot(listOf(hindsight), 3_000)
        advanceUntilIdle()
        assertThat(viewModel.state.value.resumeAt).isNull()
    }

    @Test
    fun rows_name_their_forge_only_when_the_page_mixes_forges() = test {
        trending.snapshots.getValue(TrendingPeriod.DAILY).value = TrendingSnapshot(listOf(paperclip), 1_000, listOf(ForgeInstance.GitHub))
        val viewModel = viewModel()
        advanceUntilIdle()
        assertThat(viewModel.state.value.showForge).isFalse()

        val zig = trendingRepo("ziglang/zig", forge = ForgeInstance.Codeberg)
        trending.snapshots.getValue(TrendingPeriod.DAILY).value =
            TrendingSnapshot(listOf(paperclip, zig), 1_000, listOf(ForgeInstance.GitHub, ForgeInstance.Codeberg))
        advanceUntilIdle()

        assertThat(viewModel.state.value.showForge).isTrue()
        assertThat(viewModel.state.value.items.map { it.repo.id.forge }).containsExactly(ForgeInstance.GitHub, ForgeInstance.Codeberg).inOrder()
    }

    @Test
    fun one_forge_can_be_shown_alone_with_its_own_reading_mark() = test {
        val zig = trendingRepo("ziglang/zig", forge = ForgeInstance.Codeberg)
        trending.snapshots.getValue(TrendingPeriod.DAILY).value =
            TrendingSnapshot(listOf(paperclip, zig, hindsight), 1_000, listOf(ForgeInstance.GitHub, ForgeInstance.Codeberg))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.selectForge(ForgeInstance.Codeberg)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.onlyForge).isEqualTo(ForgeInstance.Codeberg)
        assertThat(state.items.map { it.repo }).containsExactly(zig)
        // One forge alone: rows needn't wear their logo.
        assertThat(state.showForge).isFalse()

        viewModel.readThrough(0)
        advanceUntilIdle()
        assertThat(trending.forgeMarks[TrendingPeriod.DAILY to "codeberg.org"]).isEqualTo(zig.id to 0)
        assertThat(trending.marks).isEmpty()

        viewModel.selectForge(null)
        advanceUntilIdle()
        assertThat(viewModel.state.value.items.map { it.repo }).containsExactly(paperclip, zig, hindsight).inOrder()
    }

    @Test
    fun trending_opens_on_the_period_chosen_in_settings() = test {
        settings.update { it.copy(trendingPeriod = TrendingPeriod.WEEKLY) }

        val viewModel = viewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.period).isEqualTo(TrendingPeriod.WEEKLY)
    }

    @Test
    fun a_period_picked_on_the_page_is_kept_over_the_one_in_settings() = test {
        settings.update { it.copy(trendingPeriod = TrendingPeriod.WEEKLY) }
        val savedState = SavedStateHandle()
        val first = viewModel(savedState)
        advanceUntilIdle()
        first.selectPeriod(TrendingPeriod.MONTHLY)
        advanceUntilIdle()

        // The page comes back (process death, rotation): where it was, not where Settings would start it.
        val again = viewModel(savedState)
        advanceUntilIdle()

        assertThat(again.state.value.period).isEqualTo(TrendingPeriod.MONTHLY)
    }
}
