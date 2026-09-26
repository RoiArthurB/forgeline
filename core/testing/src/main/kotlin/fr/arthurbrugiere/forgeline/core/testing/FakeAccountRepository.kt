package fr.arthurbrugiere.forgeline.core.testing

import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.model.Account
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class FakeAccountRepository : AccountRepository {
    private data class Entry(val account: Account, val token: String)

    private val entries = MutableStateFlow<List<Entry>>(emptyList())
    private val activeId = MutableStateFlow<String?>(null)

    override val accounts: Flow<List<Account>> = entries.map { list -> list.map { it.account } }

    override val activeAccount: Flow<Account?> = kotlinx.coroutines.flow.combine(entries, activeId) { list, id ->
        list.firstOrNull { it.account.id == id }?.account
    }

    override suspend fun signIn(forge: ForgeInstance, user: ForgeUser, token: String): Account {
        val account = Account(Account.idFor(forge, user.login), forge, user)
        entries.update { list -> list.filterNot { it.account.id == account.id } + Entry(account, token) }
        activeId.value = account.id
        return account
    }

    override suspend fun token(accountId: String): String? = entries.value.firstOrNull { it.account.id == accountId }?.token

    override suspend fun signOut(accountId: String) {
        entries.update { list -> list.filterNot { it.account.id == accountId } }
        if (activeId.value == accountId) activeId.value = entries.value.lastOrNull()?.account?.id
    }
}
