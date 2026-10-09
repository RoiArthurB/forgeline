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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/**
 * The open conversations that wait on the people signed in, by what they are to them. [forges] are the forges asked,
 * for naming each conversation's when there are several; [failed] those that couldn't say, whose work is missing.
 */
data class Work(
    val sections: Map<WorkKind, List<IssueSearchResult>>,
    val forges: List<ForgeInstance>,
    val failed: List<ForgeInstance> = emptyList(),
    /** The forges still to answer: what is here is only the others' so far. */
    val pending: List<ForgeInstance> = emptyList(),
) {
    val isEmpty: Boolean get() = sections.values.all { it.isEmpty() }
}

private typealias Answer = Triple<ForgeInstance, WorkKind, ForgeResult<List<IssueSearchResult>>>

/**
 * One's own work across every account signed in: each forge is asked for each kind at once. A forge that fails is
 * named and left out, so one forge down doesn't hide the others' work; it only fails when every forge did.
 */
class WorkRepository @Inject constructor(
    private val clients: ForgeClients,
    private val accounts: AccountRepository,
) {
    /**
     * The work as it comes in: each account's forge adds its own when it has answered for every kind, so a slow forge
     * (Codeberg takes ten seconds) doesn't hold back the others. The last one has nothing [Work.pending].
     */
    fun stream(): Flow<ForgeResult<Work>> = channelFlow {
        val signedIn = accounts.accounts.first()
        if (signedIn.isEmpty()) return@channelFlow send(ForgeResult.Success(Work(emptyMap(), emptyList())))
        val answered = mutableMapOf<String, List<Answer>>()
        val lock = Mutex()
        signedIn.forEach { account ->
            launch {
                val answers = coroutineScope {
                    WorkKind.entries.map { kind ->
                        async {
                            val token = accounts.token(account.id)
                            val result = if (token == null) ForgeResult.Failure(ForgeError.Unauthorized) else clients.search(account.forge).work(token, account.user.login, kind)
                            Answer(account.forge, kind, result)
                        }
                    }.awaitAll()
                }
                lock.withLock {
                    answered[account.id] = answers
                    // In the accounts' order, whichever answered first: the list doesn't reshuffle as forges come in.
                    val known = signedIn.mapNotNull { answered[it.id] }.flatten()
                    val pending = signedIn.filter { it.id !in answered }.map { it.forge }.distinct()
                    val failures = known.filter { it.third is ForgeResult.Failure }
                    when {
                        failures.size < known.size -> send(ForgeResult.Success(work(signedIn.map { it.forge }.distinct(), known, pending)))
                        // Nothing but failures: said only once nobody is left to say otherwise.
                        pending.isEmpty() -> send(failures.first().third as ForgeResult.Failure)
                    }
                }
            }
        }
    }

    /** The work once every forge has answered. */
    suspend fun load(): ForgeResult<Work> = stream().last()

    private fun work(forges: List<ForgeInstance>, answers: List<Answer>, pending: List<ForgeInstance>): Work {
        val sections = WorkKind.entries.associateWith { kind ->
            // Each account's list keeps its order; they are interleaved, every account's latest first.
            interleave(answers.filter { it.second == kind }.mapNotNull { (it.third as? ForgeResult.Success)?.value }).distinct()
        }
        return Work(sections, forges, answers.filter { it.third is ForgeResult.Failure }.map { it.first }.distinct(), pending)
    }

    private fun <T> interleave(lists: List<List<T>>): List<T> = buildList {
        for (rank in 0 until (lists.maxOfOrNull { it.size } ?: 0)) lists.forEach { list -> list.getOrNull(rank)?.let(::add) }
    }
}
