package fr.arthurbrugiere.forgeline.repo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.data.repo.RepoRepository
import fr.arthurbrugiere.forgeline.core.data.star.StarRepository
import fr.arthurbrugiere.forgeline.core.data.trending.RefreshResult
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.markdown.ReadmeContext
import fr.arthurbrugiere.forgeline.core.model.IssueSummary
import fr.arthurbrugiere.forgeline.core.model.Readme
import fr.arthurbrugiere.forgeline.core.model.Release
import fr.arthurbrugiere.forgeline.core.model.RepoDetails
import fr.arthurbrugiere.forgeline.core.model.RepoFile
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.WorkflowRun
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class RepoTab { README, CODE, ISSUES, PULLS, RELEASES, ACTIONS }

sealed interface Loadable<out T> {
    data object Idle : Loadable<Nothing>

    data object Loading : Loadable<Nothing>

    data class Loaded<T>(val value: T) : Loadable<T>

    data class Failed(val error: ForgeError) : Loadable<Nothing>
}

data class CodeState(val path: String = "", val entries: Loadable<List<RepoFile>> = Loadable.Idle)

data class RepoUiState(
    val requested: RepoId,
    val details: RepoDetails? = null,
    val readme: Readme? = null,
    val readmeContext: ReadmeContext? = null,
    val isRefreshing: Boolean = false,
    val error: ForgeError? = null,
    val starred: Boolean? = null,
    val starFailed: Boolean = false,
    val tab: RepoTab = RepoTab.README,
    val code: CodeState = CodeState(),
    val issues: Loadable<List<IssueSummary>> = Loadable.Idle,
    val pulls: Loadable<List<IssueSummary>> = Loadable.Idle,
    val releases: Loadable<List<Release>> = Loadable.Idle,
    val runs: Loadable<List<WorkflowRun>> = Loadable.Idle,
)

@HiltViewModel(assistedFactory = RepoViewModel.Factory::class)
class RepoViewModel @AssistedInject constructor(
    @Assisted private val requested: RepoId,
    private val repos: RepoRepository,
    private val stars: StarRepository,
    accounts: AccountRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(requested: RepoId): RepoViewModel
    }

    private val snapshot = repos.observe(requested)
    private val local = MutableStateFlow(RepoUiState(requested))

    val state: StateFlow<RepoUiState> = combine(snapshot, local) { snapshot, local ->
        val details = snapshot.details
        local.copy(
            details = details,
            readme = snapshot.readme,
            readmeContext = details?.let { readmeContext(it, snapshot.readme) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RepoUiState(requested))

    init {
        viewModelScope.launch { refreshDetails(force = false) }
        viewModelScope.launch {
            combine(canonicalIds(), accounts.activeAccount.map { it?.id }.distinctUntilChanged()) { id, account -> id to account }
                .collect { (id, account) ->
                    val starred = if (account == null) null else stars.starredStatus(listOf(id))[id]
                    local.update { it.copy(starred = starred) }
                }
        }
    }

    fun selectTab(tab: RepoTab) {
        local.update { it.copy(tab = tab) }
        if (tab.content(local.value) == Loadable.Idle) loadTab(tab)
    }

    fun retryTab() = loadTab(local.value.tab)

    fun refresh() {
        viewModelScope.launch { refreshDetails(force = true) }
        if (local.value.tab != RepoTab.README) loadTab(local.value.tab)
    }

    fun openDirectory(path: String) {
        local.update { it.copy(code = CodeState(path = path)) }
        loadTab(RepoTab.CODE)
    }

    fun openParentDirectory() = openDirectory(local.value.code.path.substringBeforeLast('/', missingDelimiterValue = ""))

    fun toggleStar() {
        val target = !(local.value.starred ?: false)
        local.update { it.copy(starred = target) }
        viewModelScope.launch {
            val id = canonicalId()
            if (stars.setStarred(id, target) is ForgeResult.Failure) {
                local.update { it.copy(starred = !target, starFailed = true) }
            }
        }
    }

    fun errorShown() = local.update { it.copy(error = null) }

    fun starFailureShown() = local.update { it.copy(starFailed = false) }

    private suspend fun refreshDetails(force: Boolean) {
        local.update { it.copy(isRefreshing = true, error = null) }
        val result = repos.refresh(requested, force)
        local.update { it.copy(isRefreshing = false, error = (result as? RefreshResult.Failed)?.error) }
    }

    private fun loadTab(tab: RepoTab) {
        if (tab == RepoTab.README) return
        setTab(tab, Loadable.Loading)
        viewModelScope.launch {
            // Tabs need the canonical name, which only the details know.
            val details = snapshot.map { it.details }.filterNotNull().first()
            val id = details.id
            val result: ForgeResult<Any> = when (tab) {
                RepoTab.CODE -> repos.contents(id, local.value.code.path, details.defaultBranch)
                RepoTab.ISSUES -> repos.openIssues(id)
                RepoTab.PULLS -> repos.openPullRequests(id)
                RepoTab.RELEASES -> repos.releases(id)
                RepoTab.ACTIONS -> repos.workflowRuns(id)
                RepoTab.README -> return@launch
            }
            setTab(
                tab,
                when (result) {
                    is ForgeResult.Success -> Loadable.Loaded(result.value)
                    is ForgeResult.Failure -> Loadable.Failed(result.error)
                },
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun setTab(tab: RepoTab, value: Loadable<Any>) = local.update { state ->
        when (tab) {
            RepoTab.CODE -> state.copy(code = state.code.copy(entries = value as Loadable<List<RepoFile>>))
            RepoTab.ISSUES -> state.copy(issues = value as Loadable<List<IssueSummary>>)
            RepoTab.PULLS -> state.copy(pulls = value as Loadable<List<IssueSummary>>)
            RepoTab.RELEASES -> state.copy(releases = value as Loadable<List<Release>>)
            RepoTab.ACTIONS -> state.copy(runs = value as Loadable<List<WorkflowRun>>)
            RepoTab.README -> state
        }
    }

    private fun RepoTab.content(state: RepoUiState): Loadable<*> = when (this) {
        RepoTab.README -> Loadable.Loaded(Unit)
        RepoTab.CODE -> state.code.entries
        RepoTab.ISSUES -> state.issues
        RepoTab.PULLS -> state.pulls
        RepoTab.RELEASES -> state.releases
        RepoTab.ACTIONS -> state.runs
    }

    private fun canonicalIds() = snapshot.map { it.details?.id }.filterNotNull().distinctUntilChanged()

    private suspend fun canonicalId(): RepoId = canonicalIds().first()

    private fun readmeContext(details: RepoDetails, readme: Readme?): ReadmeContext {
        val directory = readme?.path?.substringBeforeLast('/', missingDelimiterValue = "")?.let { if (it.isEmpty()) "" else "$it/" }.orEmpty()
        return ReadmeContext(
            rawBaseUrl = repos.rawBaseUrl(details.id, details.defaultBranch),
            blobBaseUrl = repos.blobBaseUrl(details.id, details.defaultBranch),
            directory = directory,
        )
    }
}
