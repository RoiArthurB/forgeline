package fr.arthurbrugiere.forgeline.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.model.Account
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SessionState {
    data object Loading : SessionState

    data object SignedOut : SessionState

    /** [account] is the active one; [accounts] every one signed in, across forges. */
    data class SignedIn(val account: Account, val accounts: List<Account> = listOf(account)) : SessionState
}

@HiltViewModel
class SessionViewModel @Inject constructor(
    private val accounts: AccountRepository,
) : ViewModel() {
    val session: StateFlow<SessionState> = combine(accounts.activeAccount, accounts.accounts) { active, all ->
        active?.let { SessionState.SignedIn(it, all) } ?: SessionState.SignedOut
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SessionState.Loading)

    fun signOut(account: Account) {
        viewModelScope.launch { accounts.signOut(account.id) }
    }
}
