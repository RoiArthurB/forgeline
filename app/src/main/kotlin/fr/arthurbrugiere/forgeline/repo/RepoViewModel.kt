package fr.arthurbrugiere.forgeline.repo

import fr.arthurbrugiere.forgeline.core.model.DiscussionSummary
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
import fr.arthurbrugiere.forgeline.core.model.IssueQuery
import fr.arthurbrugiere.forgeline.core.model.IssueSummary
import kotlinx.coroutines.Job
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

enum class RepoTab { README, CODE, ISSUES, PULLS, DISCUSSIONS, RELEASES, ACTIONS }

sealed interface Loadable<out T> {
    data object Idle : Loadable<Nothing>

    data object Loading : Loadable<Nothing>

    data class Loaded<T>(val value: T) : Loadable<T>

    data class Failed(val error: ForgeError) : Loadable<Nothing>
}

/** Where a list of issues or pull requests stands beyond its first page. */
data class ListPaging(
    /** The page to ask for next; null once the forge has no more. */
    val next: Int? = null,
    val isLoading: Boolean = false,
    /** The next page couldn't be loaded: it is then asked for by hand. */
    val failed: Boolean = false,
)

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
    /** Whether the account is told of everything here; null when unknown, and nothing is offered then. */
    val watching: Boolean? = null,
    val watchFailed: Boolean = false,
    val isForking: Boolean = false,
    /** Where the fork just made is, to go there once. */
    val forkedTo: RepoId? = null,
    val forkError: ForgeError? = null,
    val tab: RepoTab = RepoTab.README,
    val code: CodeState = CodeState(),
    val issues: Loadable<List<IssueSummary>> = Loadable.Idle,
    val pulls: Loadable<List<IssueSummary>> = Loadable.Idle,
    /** Which issues and pull requests are listed: the open ones unless asked otherwise. */
    val issueQuery: IssueQuery = IssueQuery(),
    val pullQuery: IssueQuery = IssueQuery(),
    val issuePaging: ListPaging = ListPaging(),
    val pullPaging: ListPaging = ListPaging(),
    /** The issues the repository pins above its list. */
    val pinned: List<IssueSummary> = emptyList(),
    val discussions: Loadable<List<DiscussionSummary>> = Loadable.Idle,
    val discussionPaging: ListPaging = ListPaging(),
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
    val tabs: List<RepoTab>
        get() = RepoTab.entries.filter {
            when (it) {
                RepoTab.ACTIONS -> details?.hasActions != false
                // Only where the repository holds some: most don't.
                RepoTab.DISCUSSIONS -> details?.hasDiscussions == true
                else -> true
            }
        }

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
                    // Asked side by side: neither waits for the other.
                    viewModelScope.launch {
                        val watching = if (account == null) null else stars.isWatching(id)
                        local.update { it.copy(watching = watching) }
                    }
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

    /** Lists the open or the closed conversations of the tab shown. */
    fun showOpen(open: Boolean) {
        val tab = local.value.tab
        if (query(tab)?.open == open) return
        setQuery(tab) { it.copy(open = open) }
        searching[tab]?.cancel()
        loadTab(tab)
    }

    /** Looks for [text] among the conversations of the tab shown, once the writing pauses. */
    fun search(text: String) {
        val tab = local.value.tab
        if (query(tab)?.text == text) return
        setQuery(tab) { it.copy(text = text) }
        searching[tab]?.cancel()
        searching[tab] = viewModelScope.launch {
            delay(SEARCH_PAUSE_MILLIS)
            loadTab(tab)
        }
    }

    private val searching = mutableMapOf<RepoTab, Job>()

    private fun query(tab: RepoTab): IssueQuery? = when (tab) {
        RepoTab.ISSUES -> local.value.issueQuery
        RepoTab.PULLS -> local.value.pullQuery
        else -> null
    }

    private fun setQuery(tab: RepoTab, change: (IssueQuery) -> IssueQuery) = local.update {
        when (tab) {
            RepoTab.ISSUES -> it.copy(issueQuery = change(it.issueQuery))
            RepoTab.PULLS -> it.copy(pullQuery = change(it.pullQuery))
            else -> it
        }
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

    fun toggleWatch() {
        val target = !(local.value.watching ?: return)
        local.update { it.copy(watching = target) }
        viewModelScope.launch {
            if (stars.setWatching(canonicalId(), target) is ForgeResult.Failure) {
                local.update { it.copy(watching = !target, watchFailed = true) }
            }
        }
    }

    /** Copies the repository to the reader's account; the copy is then where the screen goes. */
    fun fork() {
        if (local.value.isForking) return
        local.update { it.copy(isForking = true, forkError = null) }
        viewModelScope.launch {
            val result = stars.fork(canonicalId())
            local.update {
                when (result) {
                    is ForgeResult.Success -> it.copy(isForking = false, forkedTo = result.value)
                    is ForgeResult.Failure -> it.copy(isForking = false, forkError = result.error)
                }
            }
        }
    }

    fun forkOpened() = local.update { it.copy(forkedTo = null) }

    fun forkErrorShown() = local.update { it.copy(forkError = null) }

    fun watchFailureShown() = local.update { it.copy(watchFailed = false) }

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

    /** Loads the next page of the list shown, and adds it under what is there. */
    fun loadMore() {
        val tab = local.value.tab
        if (tab == RepoTab.DISCUSSIONS) return loadMoreDiscussions()
        val query = query(tab) ?: return
        val paging = paging(tab)
        val page = paging.next ?: return
        if (paging.isLoading) return
        setPaging(tab) { it.copy(isLoading = true, failed = false) }
        viewModelScope.launch {
            val id = canonicalId()
            val asked = query.copy(page = page)
            val result = if (tab == RepoTab.ISSUES) repos.issues(id, asked) else repos.pullRequests(id, asked)
            // A page that arrives after the reader asked for another list, or after this one was loaded again, is dropped.
            if (query != query(tab) || paging(tab).next != page) return@launch
            when (result) {
                is ForgeResult.Failure -> setPaging(tab) { it.copy(isLoading = false, failed = true) }
                is ForgeResult.Success -> {
                    local.update { state ->
                        // What moved down a page while the reader was reading is not listed twice.
                        fun Loadable<List<IssueSummary>>.plus(more: List<IssueSummary>) =
                            if (this is Loadable.Loaded) Loadable.Loaded((value + more).distinctBy { it.number }) else this
                        if (tab == RepoTab.ISSUES) state.copy(issues = state.issues.plus(result.value)) else state.copy(pulls = state.pulls.plus(result.value))
                    }
                    setPaging(tab) { ListPaging(next = (page + 1).takeIf { result.value.size >= IssueQuery.PAGE_SIZE }) }
                }
            }
        }
    }

    /** What asks GitHub for the discussions after those listed; null at the list's end. */
    private var discussionsAfter: String? = null

    private fun loadMoreDiscussions() {
        val after = discussionsAfter ?: return
        if (local.value.discussionPaging.isLoading) return
        local.update { it.copy(discussionPaging = it.discussionPaging.copy(isLoading = true, failed = false)) }
        viewModelScope.launch {
            val result = repos.discussions(canonicalId(), after)
            // A page that arrives after the list was loaded again is dropped.
            if (discussionsAfter != after) return@launch
            when (result) {
                is ForgeResult.Failure -> local.update { it.copy(discussionPaging = it.discussionPaging.copy(isLoading = false, failed = true)) }
                is ForgeResult.Success -> {
                    discussionsAfter = result.value.next
                    local.update { state ->
                        val listed = (state.discussions as? Loadable.Loaded)?.value.orEmpty()
                        state.copy(
                            discussions = Loadable.Loaded((listed + result.value.items).distinctBy { it.number }),
                            discussionPaging = ListPaging(next = result.value.next?.let { (state.discussionPaging.next ?: 2) + 1 }),
                        )
                    }
                }
            }
        }
    }

    private fun paging(tab: RepoTab) = if (tab == RepoTab.ISSUES) local.value.issuePaging else local.value.pullPaging

    private fun setPaging(tab: RepoTab, change: (ListPaging) -> ListPaging) = local.update {
        when (tab) {
            RepoTab.ISSUES -> it.copy(issuePaging = change(it.issuePaging))
            RepoTab.PULLS -> it.copy(pullPaging = change(it.pullPaging))
            else -> it
        }
    }

    /** What the session remembers of the list [tab] is about to ask for; null when it wasn't asked, or can't be told yet. */
    private fun remembered(tab: RepoTab): List<Any>? {
        val details = state.value.details ?: return null
        return when (tab) {
            RepoTab.CODE -> repos.rememberedContents(details.id, local.value.code.path, local.value.ref ?: details.defaultBranch)
            RepoTab.ISSUES -> repos.rememberedIssues(details.id, local.value.issueQuery)
            RepoTab.PULLS -> repos.rememberedPullRequests(details.id, local.value.pullQuery)
            RepoTab.RELEASES -> repos.rememberedReleases(details.id)
            else -> null
        }
    }

    private fun loadTab(tab: RepoTab) {
        if (tab == RepoTab.README) return
        // What was listed last time shows at once, and is replaced when the forge answers.
        val remembered = remembered(tab)
        setTab(tab, remembered?.let { Loadable.Loaded(it) } ?: Loadable.Loading)
        val ref = local.value.ref
        val path = local.value.code.path
        val query = query(tab)
        viewModelScope.launch {
            // Tabs need the canonical name, which only the details know.
            val details = snapshot.map { it.details }.filterNotNull().first()
            val id = details.id
            val result: ForgeResult<Any> = when (tab) {
                RepoTab.CODE -> repos.contents(id, path, ref ?: details.defaultBranch)
                RepoTab.ISSUES -> {
                    // The pinned issues head the plain list only, and are asked alongside it. Without them the list still shows.
                    if (query?.isDefault == true) launch { (repos.pinnedIssues(id) as? ForgeResult.Success)?.let { pinned -> local.update { it.copy(pinned = pinned.value) } } }
                    repos.issues(id, query ?: IssueQuery())
                }
                RepoTab.PULLS -> repos.pullRequests(id, query ?: IssueQuery())
                RepoTab.DISCUSSIONS -> when (val page = repos.discussions(id)) {
                    is ForgeResult.Failure -> page
                    is ForgeResult.Success -> ForgeResult.Success(page.value.items).also { discussionsAfter = page.value.next }
                }
                RepoTab.RELEASES -> repos.releases(id)
                RepoTab.ACTIONS -> repos.workflowRuns(id)
                RepoTab.README -> return@launch
            }
            // A folder listing that arrives after the reader moved to another ref or folder is dropped.
            if (tab == RepoTab.CODE && (local.value.ref != ref || local.value.code.path != path)) return@launch
            // So is a list that arrives after the reader asked for another.
            if (query != query(tab)) return@launch
            // What was remembered stays when the forge can't be asked: an old list reads better than an error.
            if (result is ForgeResult.Failure && remembered != null) return@launch
            setTab(tab, result.toLoadable())
        }
    }

    private fun <T> ForgeResult<T>.toLoadable(): Loadable<T> = when (this) {
        is ForgeResult.Success -> Loadable.Loaded(value)
        is ForgeResult.Failure -> Loadable.Failed(error)
    }

    @Suppress("UNCHECKED_CAST")
    private fun setTab(tab: RepoTab, value: Loadable<Any>) = local.update { state ->
        // A first page as full as a page gets may have another after it.
        val paging = ListPaging(next = 2.takeIf { ((value as? Loadable.Loaded)?.value as? List<*>)?.size?.let { it >= IssueQuery.PAGE_SIZE } == true })
        when (tab) {
            RepoTab.CODE -> state.copy(code = state.code.copy(entries = value as Loadable<List<RepoFile>>))
            RepoTab.ISSUES -> state.copy(issues = value as Loadable<List<IssueSummary>>, issuePaging = paging)
            RepoTab.PULLS -> state.copy(pulls = value as Loadable<List<IssueSummary>>, pullPaging = paging)
            // Discussions are paged by what the forge gave as next, not by how full the page is.
            RepoTab.DISCUSSIONS -> state.copy(
                discussions = value as Loadable<List<DiscussionSummary>>,
                discussionPaging = ListPaging(next = 2.takeIf { value is Loadable.Loaded<*> && discussionsAfter != null }),
            )
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
        RepoTab.DISCUSSIONS -> state.discussions
        RepoTab.RELEASES -> state.releases
        RepoTab.ACTIONS -> state.runs
    }

    private fun canonicalIds() = snapshot.map { it.details?.id }.filterNotNull().distinctUntilChanged()

    private suspend fun canonicalId(): RepoId = canonicalIds().first()

    companion object {
        /** GitHub lists a run started by hand a few seconds after accepting it. */
        const val WORKFLOW_QUEUE_MILLIS = 3_000L

        /** How long the writing must pause before a search is sent: GitHub allows few searches a minute. */
        const val SEARCH_PAUSE_MILLIS = 400L
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
