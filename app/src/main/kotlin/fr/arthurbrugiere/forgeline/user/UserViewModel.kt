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
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ProfileReadme
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.UserProfile
import fr.arthurbrugiere.forgeline.repo.Loadable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** README is offered only where the profile has one, and comes first, as the profile's own words. */
enum class UserTab { README, REPOS, STARRED }

data class UserUiState(
    val login: String,
    /** The forge the profile lives on; GitHub unless the route says otherwise. */
    val forge: ForgeInstance = ForgeInstance.GitHub,
    val profile: UserProfile? = null,
    val error: ForgeError? = null,
    val tab: UserTab = UserTab.REPOS,
    val repos: Loadable<List<RepoSummary>> = Loadable.Idle,
    val starred: Loadable<List<RepoSummary>> = Loadable.Idle,
    /** The README the profile shows, once found; null while looking and when there is none. */
    val readme: ProfileReadme? = null,
    /** Null when following isn't possible or unknown (signed out, own profile, lookup failed). */
    val following: Boolean? = null,
    val followFailed: Boolean = false,
)

@HiltViewModel(assistedFactory = UserViewModel.Factory::class)
class UserViewModel @AssistedInject constructor(
    @Assisted private val forge: ForgeInstance,
    @Assisted private val login: String,
    private val repository: UserRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(forge: ForgeInstance, login: String): UserViewModel
    }

    private val _state = MutableStateFlow(UserUiState(login, forge, profile = repository.cachedUser(forge, login)))
    val state: StateFlow<UserUiState> = _state.asStateFlow()

    init {
        setTab(UserTab.REPOS, Loadable.Loading)
        viewModelScope.launch {
            when (val result = repository.user(forge, login)) {
                is ForgeResult.Success -> _state.update { it.copy(profile = result.value, error = null) }
                is ForgeResult.Failure -> _state.update { it.copy(error = result.error) }
            }
            // After the profile: whether it's an organisation decides which list holds its private repositories,
            // and where its README is kept.
            loadTab(UserTab.REPOS)
            val organization = _state.value.profile?.isOrganization == true
            repository.profileReadme(forge, login, organization)?.let { readme ->
                // Opened on the README, as on the forge, unless a tab was picked meanwhile.
                _state.update { it.copy(readme = readme, tab = if (tabPicked) it.tab else UserTab.README) }
            }
        }
        viewModelScope.launch {
            val following = repository.isFollowing(forge, login)
            _state.update { it.copy(following = following) }
        }
    }

    /** Whether a tab was picked: a README found afterwards then doesn't take the screen from it. */
    private var tabPicked = false

    fun selectTab(tab: UserTab) {
        tabPicked = true
        _state.update { it.copy(tab = tab) }
        if (tab.content(_state.value) == Loadable.Idle) loadTab(tab)
    }

    fun retry() {
        loadTab(_state.value.tab)
        if (_state.value.profile == null) {
            viewModelScope.launch {
                (repository.user(forge, login) as? ForgeResult.Success)?.let { result -> _state.update { it.copy(profile = result.value, error = null) } }
            }
        }
    }

    fun toggleFollow() {
        val current = _state.value.following ?: return
        val target = !current
        _state.update { it.copy(following = target, profile = it.profile?.adjustFollowers(if (target) 1 else -1)) }
        viewModelScope.launch {
            if (repository.setFollowing(forge, login, target) is ForgeResult.Failure) {
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
                UserTab.REPOS -> repository.repos(forge, login)
                UserTab.STARRED -> repository.starred(forge, login)
                UserTab.README -> return@launch
            }
            setTab(tab, if (result is ForgeResult.Success) Loadable.Loaded(result.value) else Loadable.Failed((result as ForgeResult.Failure).error))
        }
    }

    private fun setTab(tab: UserTab, value: Loadable<List<RepoSummary>>) = _state.update {
        when (tab) {
            UserTab.REPOS -> it.copy(repos = value)
            UserTab.STARRED -> it.copy(starred = value)
            UserTab.README -> it
        }
    }

    private fun UserTab.content(state: UserUiState) = when (this) {
        UserTab.REPOS -> state.repos
        UserTab.STARRED -> state.starred
        // Already there when its tab is offered.
        UserTab.README -> Loadable.Loaded(emptyList())
    }

    private fun UserProfile.adjustFollowers(delta: Int) = copy(followers = (followers + delta).coerceAtLeast(0))
}
