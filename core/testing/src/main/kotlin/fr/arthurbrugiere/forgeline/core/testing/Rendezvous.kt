package fr.arthurbrugiere.forgeline.core.testing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/**
 * Proves requests run side by side: each one arriving waits until all [expected] have arrived. Asked one after another,
 * the first would wait for a second that never comes, and time out; asked together, they all go through.
 */
class Rendezvous(private val expected: Int) {
    private val arrived = mutableSetOf<String>()
    private val everyone = CompletableDeferred<Unit>()

    /** Waits for the others; [name] tells the arrivals apart. */
    suspend fun arrive(name: String) {
        synchronized(arrived) {
            arrived += name
            if (arrived.size >= expected) everyone.complete(Unit)
        }
        withTimeout(5_000) { everyone.await() }
    }
}
