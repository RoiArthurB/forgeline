package fr.arthurbrugiere.forgeline.core.data.account

import fr.arthurbrugiere.forgeline.core.model.Account
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import kotlinx.coroutines.flow.first

/**
 * The account to use on [forge]: the active one when it's on that forge, else the first signed in there; null when
 * no account is, and calls go anonymous.
 */
suspend fun AccountRepository.accountOn(forge: ForgeInstance): Account? =
    activeAccount.first()?.takeIf { it.forge == forge } ?: accounts.first().firstOrNull { it.forge == forge }

suspend fun AccountRepository.tokenOn(forge: ForgeInstance): String? = accountOn(forge)?.let { token(it.id) }
