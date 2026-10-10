package fr.arthurbrugiere.forgeline.trending

import fr.arthurbrugiere.forgeline.core.data.settings.UserSettingsRepository
import kotlinx.coroutines.flow.first
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.data.star.StarRepository
import fr.arthurbrugiere.forgeline.core.data.trending.RefreshResult
import fr.arthurbrugiere.forgeline.core.data.trending.TrendingRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import fr.arthurbrugiere.forgeline.core.model.TrendingRepo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** [starred] is null when unknown: signed out, or the lookup failed. */
data class TrendingItem(val repo: TrendingRepo, val starred: Boolean?)

data class TrendingUiState(
    val period: TrendingPeriod = TrendingPeriod.DAILY,
    val items: List<TrendingItem> = emptyList(),
    val updatedAtMillis: Long? = null,
    val isRefreshing: Boolean = false,
    val error: ForgeError? = null,
    val starFailed: Boolean = false,
    /**
     * Where the last browse of this period stopped: the row (0-based) of the repo read furthest, wherever the list has
     * moved it since. Fixed for this visit so the mark doesn't chase the reader.
     */
    val resumeAt: Int? = null,
    /** Whether the page mixes several forges, so each row names its own. */
    val showForge: Boolean = false,
    /** The forges on the page; one can be picked when there are several. */
    val forges: List<ForgeInstance> = emptyList(),
    /** The one forge shown, or null for the mixed page. */
    val onlyForge: ForgeInstance? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TrendingViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val trending: TrendingRepository,
    private val stars: StarRepository,
    accounts: AccountRepository,
    settings: UserSettingsRepository,
) : ViewModel() {

    // Asked before the flow below is made: making it writes its first value into the saved state.
    private val periodKept = savedState.contains(PERIOD_KEY)
    private val period = savedState.getStateFlow(PERIOD_KEY, TrendingPeriod.DAILY)
    private val snapshot = period.flatMapLatest { trending.observe(it) }
    private val starred = MutableStateFlow<Map<RepoId, Boolean>>(emptyMap())
    private val refreshing = MutableStateFlow(false)
    private val error = MutableStateFlow<ForgeError?>(null)
    private val starFailed = MutableStateFlow(false)
    /** Where each view (a period, mixed or narrowed to one forge's host) was left, fixed for this visit. */
    private val resumeAt = MutableStateFlow<Map<Pair<TrendingPeriod, String?>, RepoId?>>(emptyMap())

    // Saved by host so the choice survives process death; null is the mixed page.
    private val onlyHost = savedState.getStateFlow<String?>(FORGE_KEY, null)

    val state: StateFlow<TrendingUiState> = combine(
        combine(period, onlyHost) { period, host -> period to host },
        snapshot,
        starred,
        combine(refreshing, error, starFailed, resumeAt) { r, e, f, m -> Flags(r, e, f, m) },
    ) { (period, host), snapshot, starred, flags ->
        // A forge no longer on the page (signed out of it) falls back to the mixed page.
        val only = snapshot.forges.firstOrNull { it.host == host }?.takeIf { snapshot.forges.size > 1 }
        // Filtering the merged page keeps each forge's own order.
        val repos = snapshot.repos.filter { only == null || it.id.forge == only }
        TrendingUiState(
            period = period,
            items = repos.map { TrendingItem(it, starred[it.id]) },
            updatedAtMillis = snapshot.fetchedAtMillis,
            isRefreshing = flags.refreshing,
            error = flags.error,
            starFailed = flags.starFailed,
            resumeAt = flags.resumeAt[period to only?.host]?.let { mark -> repos.indexOfFirst { it.id == mark }.takeIf { it >= 0 } },
            showForge = snapshot.forges.size > 1 && only == null,
            forges = snapshot.forges,
            onlyForge = only,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrendingUiState(period = period.value))

    init {
        viewModelScope.launch {
            // Opens on the period chosen in Settings, unless one was picked here before (kept across process death).
            if (!periodKept) savedState[PERIOD_KEY] = settings.settings.first().trendingPeriod
            period.collect { period -> refresh(period, force = false) }
        }
        viewModelScope.launch {
            combine(period, onlyHost) { period, host -> period to host }.collect { view ->
                if (view !in resumeAt.value) {
                    val only = view.second?.let { host -> trending.observe(view.first).first().forges.firstOrNull { it.host == host } }
                    resumeAt.update { it + (view to trending.readThrough(view.first, only)) }
                }
            }
        }
        viewModelScope.launch {
            combine(
                snapshot.map { snapshot -> snapshot.repos.map { it.id } }.distinctUntilChanged(),
                accounts.activeAccount.map { it?.id }.distinctUntilChanged(),
            ) { ids, accountId -> ids to accountId }
                .collect { (ids, accountId) ->
                    starred.value = if (accountId == null || ids.isEmpty()) emptyMap() else stars.starredStatus(ids)
                }
        }
    }

    fun selectPeriod(period: TrendingPeriod) {
        error.value = null
        savedState[PERIOD_KEY] = period
    }

    /** Shows [forge]'s ranking alone, or the mixed page when null. */
    fun selectForge(forge: ForgeInstance?) {
        savedState[FORGE_KEY] = forge?.host
    }

    fun refresh() {
        viewModelScope.launch { refresh(period.value, force = true) }
    }

    fun toggleStar(repo: RepoId) {
        val target = !(starred.value[repo] ?: false)
        starred.update { it + (repo to target) }
        viewModelScope.launch {
            if (stars.setStarred(repo, target) is ForgeResult.Failure) {
                starred.update { it + (repo to !target) }
                starFailed.value = true
            }
        }
    }

    /** The list has been read down to [rank] (0-based), as shown now. */
    fun readThrough(rank: Int) {
        val period = period.value
        val shown = state.value
        val repo = shown.items.getOrNull(rank)?.repo?.id ?: return
        viewModelScope.launch { trending.markReadThrough(period, repo, rank, shown.onlyForge) }
    }

    fun errorShown() {
        error.value = null
    }

    fun starFailureShown() {
        starFailed.value = false
    }

    private suspend fun refresh(period: TrendingPeriod, force: Boolean) {
        refreshing.value = true
        error.value = null
        val result = trending.refresh(period, force)
        error.value = (result as? RefreshResult.Failed)?.error
        refreshing.value = false
    }

    private data class Flags(
        val refreshing: Boolean,
        val error: ForgeError?,
        val starFailed: Boolean,
        val resumeAt: Map<Pair<TrendingPeriod, String?>, RepoId?>,
    )

    private companion object {
        const val PERIOD_KEY = "period"
        const val FORGE_KEY = "forge"
    }
}
