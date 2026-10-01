package fr.arthurbrugiere.forgeline.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.feed.FeedPreviewRepository
import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import fr.arthurbrugiere.forgeline.core.data.di.Computation
import fr.arthurbrugiere.forgeline.core.data.feed.FeedSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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
    /**
     * The row the last visit's reading reached, which "Where you left off" sits above: everything above it is newer.
     * Null when nothing is new, or before any reading. Fixed for the visit so the mark doesn't chase the reader.
     */
    val leftOffBefore: String? = null,
    /** Rows say which forge they're from only when more than one forge is signed in. */
    val showForge: Boolean = false,
)

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val feed: FeedRepository,
    private val previews: FeedPreviewRepository,
    settings: UserSettingsRepository,
    accounts: AccountRepository,
    @param:Computation private val computation: CoroutineDispatcher,
) : ViewModel() {

    private data class Status(val isRefreshing: Boolean = false, val isLoadingMore: Boolean = false, val error: ForgeError? = null)

    private val status = MutableStateFlow(Status())

    /** How far the last visit read, read once when the Feed opens. */
    private val readUpTo = MutableStateFlow<Instant?>(null)

    private data class Rows(val snapshot: FeedSnapshot, val items: List<FeedItem>)

    /**
     * The rows, built only when the events or the kinds shown change, and off the main thread. Regression: they were
     * rebuilt on the main thread for every preview that arrived and every refresh that started or ended.
     */
    private val rows = combine(feed.observe(), settings.settings.map { it.feedKinds }.distinctUntilChanged()) { snapshot, kinds ->
        Rows(snapshot, feedItems(snapshot.events, kinds))
    }.flowOn(computation)

    val state: StateFlow<FeedUiState> = combine(
        combine(rows, readUpTo, accounts.accounts) { rows, readUpTo, signedIn -> Triple(rows, readUpTo, signedIn.map { it.forge }.distinct().size > 1) },
        status,
        previews.observe(),
    ) { (rows, readUpTo, showForge), status, previews ->
        val (snapshot, items) = rows
        FeedUiState(
            showForge = showForge,
            items = items,
            leftOffBefore = readUpTo?.let { mark -> items.indexOfFirst { it.createdAt <= mark } }?.takeIf { it > 0 }?.let { items[it].key },
            syncedAtMillis = snapshot.syncedAtMillis,
            hasMore = snapshot.hasMore,
            isRefreshing = status.isRefreshing,
            isLoadingMore = status.isLoadingMore,
            error = status.error,
            previews = previews,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedUiState())

    init {
        viewModelScope.launch { readUpTo.value = feed.readUpTo() }
        load(force = false)
    }

    /** [item] was read: the next visit marks where reading stopped. */
    fun readThrough(item: FeedItem) {
        viewModelScope.launch { feed.markRead(item.key, item.createdAt) }
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
            // The indicator stops at the first forge with fresh activity: a slower forge's rows join when it answers.
            val result = feed.refresh(force) { status.update { it.copy(isRefreshing = false) } }
            status.update { it.copy(isRefreshing = false, error = (result as? ForgeResult.Failure)?.error) }
        }
    }
}
