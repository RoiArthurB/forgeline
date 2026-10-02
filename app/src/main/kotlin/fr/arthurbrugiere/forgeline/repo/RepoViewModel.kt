package fr.arthurbrugiere.forgeline.repo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.data.issue.IssueRepository
import fr.arthurbrugiere.forgeline.core.data.repo.RepoRepository
import fr.arthurbrugiere.forgeline.core.data.star.StarRepository
import fr.arthurbrugiere.forgeline.core.data.trending.RefreshResult
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.markdown.ReadmeContext
import fr.arthurbrugiere.forgeline.core.model.GitRefs
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
import kotlinx.coroutines.delay
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
    /** The branch or tag browsed; null for the default branch. */
    val ref: String? = null,
    val refs: Loadable<GitRefs> = Loadable.Idle,
    /** The README at [ref]; only the default branch's README is cached. */
    val refReadme: Loadable<Readme?> = Loadable.Idle,
    /** A workflow was just started by hand, to say so once. */
    val workflowStarted: Boolean = false,
) {
    /** The tabs this repository fills: Actions unless its CI is switched off (Forgejo repositories can). */
    val tabs: List<RepoTab> get() = RepoTab.entries.filter { it != RepoTab.ACTIONS || details?.hasActions != false }

    /** What the README and Code tabs show: [ref], else the default branch once known. */
    val browsedRef: String? get() = ref ?: details?.defaultBranch
}

@HiltViewModel(assistedFactory = RepoViewModel.Factory::class)
class RepoViewModel @AssistedInject constructor(
    @Assisted private val requested: RepoId,
    private val repos: RepoRepository,
    private val stars: StarRepository,
    accounts: AccountRepository,
    conversations: IssueRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(requested: RepoId): RepoViewModel
    }

    private val snapshot = repos.observe(requested)
    private val local = MutableStateFlow(RepoUiState(requested))

    val state: StateFlow<RepoUiState> = combine(snapshot, local) { snapshot, local ->
        val details = snapshot.details
        val readme = if (local.ref == null) snapshot.readme else (local.refReadme as? Loadable.Loaded)?.value
        local.copy(
            details = details,
            readme = readme,
            readmeContext = details?.let { readmeContext(it, local.ref ?: it.defaultBranch, readme) },
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

        viewModelScope.launch {
            // What was opened, closed or reopened from the app shows in the lists already loaded. The forge is asked
            // rather than the list patched: an issue and a pull request share their numbering.
            conversations.changed.collect { ref ->
                if (ref.repo != canonicalId()) return@collect
                if (local.value.issues != Loadable.Idle) loadTab(RepoTab.ISSUES)
                if (local.value.pulls != Loadable.Idle) loadTab(RepoTab.PULLS)
            }
        }
    }

    fun selectTab(tab: RepoTab) {
        local.update { it.copy(tab = tab) }
        if (tab.content(local.value) == Loadable.Idle) loadTab(tab)
    }

    fun retryTab() {
        if (local.value.tab == RepoTab.README) loadRefReadme() else loadTab(local.value.tab)
    }

    /** Loads the branches and tags the first time the picker opens, and again after a failure. */
    fun loadRefs() {
        if (local.value.refs is Loadable.Loaded || local.value.refs == Loadable.Loading) return
        local.update { it.copy(refs = Loadable.Loading) }
        viewModelScope.launch {
            val result = repos.refs(canonicalId())
            local.update { it.copy(refs = result.toLoadable()) }
        }
    }

    /** Browses [name]; the default branch goes back to the cached README. */
    fun selectRef(name: String) {
        viewModelScope.launch {
            val details = snapshot.map { it.details }.filterNotNull().first()
            val ref = name.takeUnless { it == details.defaultBranch }
            // A folder may not exist on the other ref, so browsing starts again at the root.
            local.update { it.copy(ref = ref, refReadme = Loadable.Idle, code = CodeState()) }
            loadRefReadme()
            if (local.value.tab == RepoTab.CODE) loadTab(RepoTab.CODE)
        }
    }

    fun refresh() {
        viewModelScope.launch { refreshDetails(force = true) }
        if (local.value.tab == RepoTab.README) loadRefReadme() else loadTab(local.value.tab)
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

    /** Says so, then lists the runs again once the forge has queued the new one. */
    fun workflowStarted() {
        local.update { it.copy(workflowStarted = true) }
        viewModelScope.launch {
            delay(WORKFLOW_QUEUE_MILLIS)
            loadTab(RepoTab.ACTIONS)
        }
    }

    fun workflowStartShown() = local.update { it.copy(workflowStarted = false) }

    fun errorShown() = local.update { it.copy(error = null) }

    fun starFailureShown() = local.update { it.copy(starFailed = false) }

    private suspend fun refreshDetails(force: Boolean) {
        local.update { it.copy(isRefreshing = true, error = null) }
        val result = repos.refresh(requested, force)
        local.update { it.copy(isRefreshing = false, error = (result as? RefreshResult.Failed)?.error) }
    }

    private fun loadRefReadme() {
        val ref = local.value.ref ?: return
        local.update { it.copy(refReadme = Loadable.Loading) }
        viewModelScope.launch {
            val result = repos.readme(canonicalId(), ref)
            // A late answer for a ref the reader already left is dropped.
            local.update { if (it.ref == ref) it.copy(refReadme = result.toLoadable()) else it }
        }
    }

    private fun loadTab(tab: RepoTab) {
        if (tab == RepoTab.README) return
        setTab(tab, Loadable.Loading)
        val ref = local.value.ref
        val path = local.value.code.path
        viewModelScope.launch {
            // Tabs need the canonical name, which only the details know.
            val details = snapshot.map { it.details }.filterNotNull().first()
            val id = details.id
            val result: ForgeResult<Any> = when (tab) {
                RepoTab.CODE -> repos.contents(id, path, ref ?: details.defaultBranch)
                RepoTab.ISSUES -> repos.openIssues(id)
                RepoTab.PULLS -> repos.openPullRequests(id)
                RepoTab.RELEASES -> repos.releases(id)
                RepoTab.ACTIONS -> repos.workflowRuns(id)
                RepoTab.README -> return@launch
            }
            // A folder listing that arrives after the reader moved to another ref or folder is dropped.
            if (tab == RepoTab.CODE && (local.value.ref != ref || local.value.code.path != path)) return@launch
            setTab(tab, result.toLoadable())
        }
    }

    private fun <T> ForgeResult<T>.toLoadable(): Loadable<T> = when (this) {
        is ForgeResult.Success -> Loadable.Loaded(value)
        is ForgeResult.Failure -> Loadable.Failed(error)
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

    companion object {
        /** GitHub lists a run started by hand a few seconds after accepting it. */
        const val WORKFLOW_QUEUE_MILLIS = 3_000L
    }

    private fun readmeContext(details: RepoDetails, ref: String, readme: Readme?): ReadmeContext {
        val directory = readme?.path?.substringBeforeLast('/', missingDelimiterValue = "")?.let { if (it.isEmpty()) "" else "$it/" }.orEmpty()
        return ReadmeContext(
            rawBaseUrl = repos.rawBaseUrl(details.id, ref),
            blobBaseUrl = repos.blobBaseUrl(details.id, ref),
            directory = directory,
        )
    }
}
