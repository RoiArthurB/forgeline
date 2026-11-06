package fr.arthurbrugiere.forgeline.core.data.account

import fr.arthurbrugiere.forgeline.core.model.Account
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import kotlinx.coroutines.flow.first

/**
 * The account to use on [forge]: the one chosen to act there ([AccountRepository.acting]), else the active one when
 * it's on that forge, else the first signed in there; null when no account is, and calls go anonymous.
 */
suspend fun AccountRepository.accountOn(forge: ForgeInstance): Account? =
    actingOn(forge, accounts.first(), activeAccount.first(), acting.first())

/** Which of [accounts] acts on [forge], by [AccountRepository.accountOn]'s rule, from what is known of them. */
fun actingOn(forge: ForgeInstance, accounts: List<Account>, active: Account?, acting: Set<String>): Account? {
    val there = accounts.filter { it.forge == forge }
    return there.firstOrNull { it.id in acting } ?: active?.takeIf { it.forge == forge } ?: there.firstOrNull()
}

suspend fun AccountRepository.tokenOn(forge: ForgeInstance): String? = accountOn(forge)?.let { token(it.id) }
