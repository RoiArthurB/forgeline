package fr.arthurbrugiere.forgeline.user

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.user.UserRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.UserProfile
import fr.arthurbrugiere.forgeline.repo.Loadable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class UserTab { REPOS, STARRED }

data class UserUiState(
    val login: String,
    val profile: UserProfile? = null,
    val error: ForgeError? = null,
    val tab: UserTab = UserTab.REPOS,
    val repos: Loadable<List<RepoSummary>> = Loadable.Idle,
    val starred: Loadable<List<RepoSummary>> = Loadable.Idle,
    /** Null when following isn't possible or unknown (signed out, own profile, lookup failed). */
    val following: Boolean? = null,
    val followFailed: Boolean = false,
)

@HiltViewModel(assistedFactory = UserViewModel.Factory::class)
class UserViewModel @AssistedInject constructor(
    @Assisted private val login: String,
    private val repository: UserRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(login: String): UserViewModel
    }

    private val _state = MutableStateFlow(UserUiState(login, profile = repository.cachedUser(login)))
    val state: StateFlow<UserUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            when (val result = repository.user(login)) {
                is ForgeResult.Success -> _state.update { it.copy(profile = result.value, error = null) }
                is ForgeResult.Failure -> _state.update { it.copy(error = result.error) }
            }
        }
        viewModelScope.launch {
            val following = repository.isFollowing(login)
            _state.update { it.copy(following = following) }
        }
        loadTab(UserTab.REPOS)
    }

    fun selectTab(tab: UserTab) {
        _state.update { it.copy(tab = tab) }
        if (tab.content(_state.value) == Loadable.Idle) loadTab(tab)
    }

    fun retry() {
        loadTab(_state.value.tab)
        if (_state.value.profile == null) {
            viewModelScope.launch {
                (repository.user(login) as? ForgeResult.Success)?.let { result -> _state.update { it.copy(profile = result.value, error = null) } }
            }
        }
    }

    fun toggleFollow() {
        val current = _state.value.following ?: return
        val target = !current
        _state.update { it.copy(following = target, profile = it.profile?.adjustFollowers(if (target) 1 else -1)) }
        viewModelScope.launch {
            if (repository.setFollowing(login, target) is ForgeResult.Failure) {
                _state.update {
                    it.copy(following = current, profile = it.profile?.adjustFollowers(if (target) -1 else 1), followFailed = true)
                }
            }
        }
    }

    fun followFailureShown() = _state.update { it.copy(followFailed = false) }

    private fun loadTab(tab: UserTab) {
        setTab(tab, Loadable.Loading)
        viewModelScope.launch {
            val result = when (tab) {
                UserTab.REPOS -> repository.repos(login)
                UserTab.STARRED -> repository.starred(login)
            }
            setTab(tab, if (result is ForgeResult.Success) Loadable.Loaded(result.value) else Loadable.Failed((result as ForgeResult.Failure).error))
        }
    }

    private fun setTab(tab: UserTab, value: Loadable<List<RepoSummary>>) = _state.update {
        when (tab) {
            UserTab.REPOS -> it.copy(repos = value)
            UserTab.STARRED -> it.copy(starred = value)
        }
    }

    private fun UserTab.content(state: UserUiState) = when (this) {
        UserTab.REPOS -> state.repos
        UserTab.STARRED -> state.starred
    }

    private fun UserProfile.adjustFollowers(delta: Int) = copy(followers = (followers + delta).coerceAtLeast(0))
}
