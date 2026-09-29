package fr.arthurbrugiere.forgeline.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.feed.FeedPreviewRepository
import fr.arthurbrugiere.forgeline.core.data.feed.FeedRepository
import fr.arthurbrugiere.forgeline.core.model.FeedPreviews
import fr.arthurbrugiere.forgeline.core.data.settings.UserSettingsRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FeedUiState(
    val items: List<FeedItem> = emptyList(),
    val syncedAtMillis: Long? = null,
    val hasMore: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: ForgeError? = null,
    /** Repo previews and pull request titles fetched so far; rows fill in as these arrive. */
    val previews: FeedPreviews = FeedPreviews(),
)

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val feed: FeedRepository,
    private val previews: FeedPreviewRepository,
    settings: UserSettingsRepository,
) : ViewModel() {

    private data class Status(val isRefreshing: Boolean = false, val isLoadingMore: Boolean = false, val error: ForgeError? = null)

    private val status = MutableStateFlow(Status())

    val state: StateFlow<FeedUiState> = combine(feed.observe(), settings.settings, status, previews.observe()) { snapshot, settings, status, previews ->
        FeedUiState(
            items = feedItems(snapshot.events, settings.feedKinds),
            syncedAtMillis = snapshot.syncedAtMillis,
            hasMore = snapshot.hasMore,
            isRefreshing = status.isRefreshing,
            isLoadingMore = status.isLoadingMore,
            error = status.error,
            previews = previews,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedUiState())

    init {
        load(force = false)
    }

    fun refresh() = load(force = true)

    fun loadMore() {
        if (status.value.isLoadingMore) return
        status.value = status.value.copy(isLoadingMore = true, error = null)
        viewModelScope.launch {
            val result = feed.loadMore()
            status.value = status.value.copy(isLoadingMore = false, error = (result as? ForgeResult.Failure)?.error)
        }
    }

    /** Rows on screen: fetch the previews they need (repositories they name, pull request titles). */
    fun onVisible(items: List<FeedItem>) {
        val repos = items.mapNotNull { it.previewRepo }.toSet()
        val pulls = items.mapNotNull { it.previewPull }.toSet()
        if (repos.isEmpty() && pulls.isEmpty()) return
        viewModelScope.launch { previews.ensure(repos, pulls) }
    }

    fun errorShown() {
        status.value = status.value.copy(error = null)
    }

    private fun load(force: Boolean) {
        status.value = status.value.copy(isRefreshing = true, error = null)
        viewModelScope.launch {
            val result = feed.refresh(force)
            status.value = status.value.copy(isRefreshing = false, error = (result as? ForgeResult.Failure)?.error)
        }
    }
}
