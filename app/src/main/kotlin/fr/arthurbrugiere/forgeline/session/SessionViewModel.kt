package fr.arthurbrugiere.forgeline.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.model.Account
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SessionState {
    data object Loading : SessionState

    data object SignedOut : SessionState

    data class SignedIn(val account: Account) : SessionState
}

@HiltViewModel
class SessionViewModel @Inject constructor(
    private val accounts: AccountRepository,
) : ViewModel() {
    val session: StateFlow<SessionState> = accounts.activeAccount
        .map { account -> account?.let(SessionState::SignedIn) ?: SessionState.SignedOut }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SessionState.Loading)

    fun signOut() {
        val signedIn = session.value as? SessionState.SignedIn ?: return
        viewModelScope.launch { accounts.signOut(signedIn.account.id) }
    }
}
