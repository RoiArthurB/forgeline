package fr.arthurbrugiere.forgeline.collections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.data.collections.CollectionsRepository
import fr.arthurbrugiere.forgeline.core.data.collections.ImportReport
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.Account
import fr.arthurbrugiere.forgeline.core.model.RepoCollection
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CollectionsUiState(
    /** Null while read for the first time. */
    val collections: List<RepoCollection>? = null,
    /** The accounts whose lists can be brought over (GitHub's). */
    val importable: List<Account> = emptyList(),
    /** The account whose lists are being brought over. */
    val importing: String? = null,
    val imported: ImportReport? = null,
    val importFailed: ForgeError? = null,
)

@HiltViewModel
class CollectionsViewModel @Inject constructor(
    private val repository: CollectionsRepository,
    accounts: AccountRepository,
) : ViewModel() {
    private val progress = MutableStateFlow(CollectionsUiState())

    val state: StateFlow<CollectionsUiState> = combine(repository.observeAll(), accounts.accounts, progress) { collections, signedIn, progress ->
        progress.copy(collections = collections, importable = repository.importableAccounts(signedIn))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CollectionsUiState())

    private val _created = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** Each collection made here, by id, to be opened. */
    val created: SharedFlow<String> = _created

    fun create(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { _created.emit(repository.create(name)) }
    }

    fun import(account: Account) {
        if (progress.value.importing != null) return
        progress.update { it.copy(importing = account.id, imported = null, importFailed = null) }
        viewModelScope.launch {
            when (val result = repository.import(account)) {
                is ForgeResult.Success -> progress.update { it.copy(importing = null, imported = result.value) }
                is ForgeResult.Failure -> progress.update { it.copy(importing = null, importFailed = result.error) }
            }
        }
    }

    fun importShown() = progress.update { it.copy(imported = null, importFailed = null) }
}

data class CollectionUiState(
    val id: String,
    /** Null while read, and once deleted. */
    val name: String? = null,
    val repos: List<RepoSummary>? = null,
)

@HiltViewModel(assistedFactory = CollectionViewModel.Factory::class)
class CollectionViewModel @AssistedInject constructor(
    @Assisted private val id: String,
    private val repository: CollectionsRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(id: String): CollectionViewModel
    }

    val state: StateFlow<CollectionUiState> = combine(repository.observeName(id), repository.observeRepos(id)) { name, repos ->
        CollectionUiState(id, name, repos)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CollectionUiState(id))

    fun rename(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.rename(id, name) }
    }

    fun remove(repo: RepoId) {
        viewModelScope.launch { repository.remove(id, repo) }
    }

    /** Puts back what [remove] took out, for Undo. */
    fun restore(repo: RepoSummary) {
        viewModelScope.launch { repository.add(id, repo) }
    }

    fun delete() {
        viewModelScope.launch { repository.delete(id) }
    }
}

data class CollectionPickerUiState(
    val collections: List<RepoCollection> = emptyList(),
    /** The collections the repository is in. */
    val holding: Set<String> = emptySet(),
)

/** Which collections one repository is in, and putting it in or out of them, from its page. */
@HiltViewModel(assistedFactory = CollectionPickerViewModel.Factory::class)
class CollectionPickerViewModel @AssistedInject constructor(
    @Assisted private val repo: RepoId,
    private val repository: CollectionsRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(repo: RepoId): CollectionPickerViewModel
    }

    val state: StateFlow<CollectionPickerUiState> = combine(repository.observeAll(), repository.observeHolding(repo)) { all, holding ->
        CollectionPickerUiState(all, holding)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CollectionPickerUiState())

    /** Puts [summary] in collection [id], or takes it out when it is there. */
    fun toggle(id: String, summary: RepoSummary) {
        viewModelScope.launch {
            if (id in state.value.holding) repository.remove(id, repo) else repository.add(id, summary)
        }
    }

    /** Makes a collection named [name] holding [summary]. */
    fun createWith(name: String, summary: RepoSummary) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.add(repository.create(name), summary) }
    }
}
