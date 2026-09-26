package fr.arthurbrugiere.forgeline.trending

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
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TrendingViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val trending: TrendingRepository,
    private val stars: StarRepository,
    accounts: AccountRepository,
) : ViewModel() {

    private val period = savedState.getStateFlow(PERIOD_KEY, TrendingPeriod.DAILY)
    private val snapshot = period.flatMapLatest { trending.observe(it) }
    private val starred = MutableStateFlow<Map<RepoId, Boolean>>(emptyMap())
    private val refreshing = MutableStateFlow(false)
    private val error = MutableStateFlow<ForgeError?>(null)
    private val starFailed = MutableStateFlow(false)

    val state: StateFlow<TrendingUiState> = combine(
        period,
        snapshot,
        starred,
        combine(refreshing, error, starFailed) { r, e, f -> Triple(r, e, f) },
    ) { period, snapshot, starred, (refreshing, error, starFailed) ->
        TrendingUiState(
            period = period,
            items = snapshot.repos.map { TrendingItem(it, starred[it.id]) },
            updatedAtMillis = snapshot.fetchedAtMillis,
            isRefreshing = refreshing,
            error = error,
            starFailed = starFailed,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrendingUiState(period = period.value))

    init {
        viewModelScope.launch { period.collect { refresh(it, force = false) } }
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

    private companion object {
        const val PERIOD_KEY = "period"
    }
}
