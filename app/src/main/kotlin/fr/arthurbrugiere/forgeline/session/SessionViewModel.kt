package fr.arthurbrugiere.forgeline.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.data.account.SignedOutData
import fr.arthurbrugiere.forgeline.core.data.account.actingOn
import fr.arthurbrugiere.forgeline.core.forge.ForgeClients
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.Account
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SessionState {
    data object Loading : SessionState

    data object SignedOut : SessionState

    /**
     * [account] is the active one; [accounts] every one signed in, across forges. [limited] holds the ids of those whose
     * sign-in stops at public repositories: their private ones show as missing until they sign in again. [ended] holds
     * those whose forge refused to renew the sign-in: nothing syncs for them until they sign in again. [acting] holds
     * those chosen to act on their forge where several are signed in to one ([actingOn] says which acts).
     */
    data class SignedIn(
        val account: Account,
        val accounts: List<Account> = listOf(account),
        val limited: Set<String> = emptySet(),
        val ended: Set<String> = emptySet(),
        val acting: Set<String> = emptySet(),
    ) : SessionState
}

/**
 * The account that reads and writes on [forge] when several are signed in there, for the screens to say whose name
 * they act in; null with one account or none on it, where there is nothing to tell apart.
 */
fun SessionState.actingOn(forge: ForgeInstance): Account? {
    if (this !is SessionState.SignedIn || accounts.count { it.forge == forge } < 2) return null
    return actingOn(forge, accounts, account, acting)
}

/** Whether an account is signed in to [forge]: starring, following or running CI there needs one on that forge. */
fun SessionState.signedInOn(forge: ForgeInstance): Boolean = this is SessionState.SignedIn && accounts.any { it.forge == forge }

@HiltViewModel
class SessionViewModel @Inject constructor(
    private val accounts: AccountRepository,
    private val signedOutData: SignedOutData,
    private val clients: ForgeClients,
) : ViewModel() {
    /** Accounts whose forge says their sign-in doesn't reach private repositories; asked whenever the accounts change. */
    private val limited = MutableStateFlow<Set<String>>(emptySet())

    val session: StateFlow<SessionState> = combine(accounts.activeAccount, accounts.accounts, limited, accounts.signInEnded, accounts.acting) { active, all, limited, ended, acting ->
        val ids = all.map { account -> account.id }.toSet()
        active?.let { SessionState.SignedIn(it, all, limited.intersect(ids), ended.intersect(ids), acting.intersect(ids)) } ?: SessionState.SignedOut
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SessionState.Loading)

    init {
        viewModelScope.launch {
            // Signing in again changes the stored account without changing who it is: asked on every change.
            accounts.accounts.collectLatest { signedIn ->
                limited.value = signedIn.filter { account ->
                    val token = accounts.token(account.id) ?: return@filter false
                    (clients.auth(account.forge).reachesPrivateRepositories(token) as? ForgeResult.Success)?.value == false
                }.map { it.id }.toSet()
            }
        }
    }

    /** Makes [accountId] the one acting on its forge, then [then]: what opens next reads with it. */
    fun actAs(accountId: String, then: () -> Unit = {}) {
        viewModelScope.launch {
            accounts.actAs(accountId)
            then()
        }
    }

    fun signOut(account: Account) {
        viewModelScope.launch {
            accounts.signOut(account.id)
            // What the app kept for the account goes with it.
            signedOutData.forget(account)
        }
    }
}
