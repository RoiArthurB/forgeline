package fr.arthurbrugiere.forgeline.trending

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.data.settings.UserSettingsRepository
import fr.arthurbrugiere.forgeline.core.data.star.StarRepository
import fr.arthurbrugiere.forgeline.core.data.trending.RefreshResult
import fr.arthurbrugiere.forgeline.core.data.trending.TrendingRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import fr.arthurbrugiere.forgeline.core.model.TrendingRepo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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

    /**
     * Stars set here, which win over a lookup answered after them: a star given right after signing in races the
     * lookup signing in starts, which can still say no.
     */
    private val written = mutableMapOf<RepoId, Boolean>()
    private var lookedUpFor: String? = null

    // Saved by host so the choice survives process death; null is the mixed page.
    private val onlyHost = savedState.getStateFlow<String?>(FORGE_KEY, null)

    val state: StateFlow<TrendingUiState> = combine(
        combine(period, onlyHost) { period, host -> period to host },
        snapshot,
        starred,
        combine(refreshing, error, starFailed) { r, e, f -> Flags(r, e, f) },
    ) { (period, host), snapshot, starred, flags ->
        // A forge no longer on the page (signed out of it) falls back to the mixed page.
        val only = snapshot.forges.firstOrNull { it.host == host }?.takeIf { snapshot.forges.size > 1 }
        // Narrowed to one forge, its whole list (the balancing only shares out the mixed page); filtering the merged
        // page keeps each forge's own order.
        val repos = if (only == null) snapshot.repos else snapshot.unbalanced.filter { it.id.forge == only }
        TrendingUiState(
            period = period,
            items = repos.map { TrendingItem(it, starred[it.id]) },
            updatedAtMillis = snapshot.fetchedAtMillis,
            isRefreshing = flags.refreshing,
            error = flags.error,
            starFailed = flags.starFailed,
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
            combine(
                // Every row either view can show, so narrowing to one forge needs no new lookup.
                snapshot.map { snapshot -> snapshot.unbalanced.map { it.id } }.distinctUntilChanged(),
                accounts.activeAccount.map { it?.id }.distinctUntilChanged(),
            ) { ids, accountId -> ids to accountId }
                .collect { (ids, accountId) ->
                    // Signing in keeps what was set just before; another account, or none, has its own stars.
                    if (lookedUpFor != null && accountId != lookedUpFor) written.clear()
                    lookedUpFor = accountId
                    starred.value = if (accountId == null || ids.isEmpty()) emptyMap() else stars.starredStatus(ids) + written
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

    fun toggleStar(repo: RepoId) = setStarred(repo, !(starred.value[repo] ?: false))

    /** Stars [repo], as asked before signing in: given, never taken back, whatever was known of it signed out. */
    fun star(repo: RepoId) = setStarred(repo, true)

    private fun setStarred(repo: RepoId, target: Boolean) {
        written[repo] = target
        starred.update { it + (repo to target) }
        viewModelScope.launch {
            if (stars.setStarred(repo, target) is ForgeResult.Failure) {
                written.remove(repo)
                starred.update { it + (repo to !target) }
                starFailed.value = true
            }
        }
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
    )

    private companion object {
        const val PERIOD_KEY = "period"
        const val FORGE_KEY = "forge"
    }
}
