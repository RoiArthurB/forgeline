package fr.arthurbrugiere.forgeline.core.data.work

import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeClients
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueSearchResult
import fr.arthurbrugiere.forgeline.core.model.WorkKind
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * The open conversations that wait on the people signed in, by what they are to them. [forges] are the forges asked,
 * for naming each conversation's when there are several; [failed] those that couldn't say, whose work is missing.
 */
data class Work(
    val sections: Map<WorkKind, List<IssueSearchResult>>,
    val forges: List<ForgeInstance>,
    val failed: List<ForgeInstance> = emptyList(),
) {
    val isEmpty: Boolean get() = sections.values.all { it.isEmpty() }
}

/**
 * One's own work across every account signed in: each forge is asked for each kind at once. A forge that fails is
 * named and left out, so one forge down doesn't hide the others' work; it only fails when every forge did.
 */
class WorkRepository @Inject constructor(
    private val clients: ForgeClients,
    private val accounts: AccountRepository,
) {
    suspend fun load(): ForgeResult<Work> {
        val signedIn = accounts.accounts.first()
        if (signedIn.isEmpty()) return ForgeResult.Success(Work(emptyMap(), emptyList()))
        val answers = coroutineScope {
            signedIn.flatMap { account ->
                WorkKind.entries.map { kind ->
                    async {
                        val token = accounts.token(account.id)
                        val result = if (token == null) ForgeResult.Failure(ForgeError.Unauthorized) else clients.search(account.forge).work(token, account.user.login, kind)
                        Triple(account.forge, kind, result)
                    }
                }
            }.awaitAll()
        }
        val failures = answers.filter { it.third is ForgeResult.Failure }
        if (failures.size == answers.size) return failures.first().third as ForgeResult.Failure
        val sections = WorkKind.entries.associateWith { kind ->
            // Each account's list keeps its order; they are interleaved, every account's latest first.
            interleave(answers.filter { it.second == kind }.mapNotNull { (it.third as? ForgeResult.Success)?.value }).distinct()
        }
        return ForgeResult.Success(Work(sections, signedIn.map { it.forge }.distinct(), failures.map { it.first }.distinct()))
    }

    private fun <T> interleave(lists: List<List<T>>): List<T> = buildList {
        for (rank in 0 until (lists.maxOfOrNull { it.size } ?: 0)) lists.forEach { list -> list.getOrNull(rank)?.let(::add) }
    }
}
