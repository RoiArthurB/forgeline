package fr.arthurbrugiere.forgeline.core.testing

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/** Runs work on background threads and counts it, to check that something really was handed over to it. */
class RecordingDispatcher : CoroutineDispatcher() {
    val uses = AtomicInteger()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        uses.incrementAndGet()
        Dispatchers.Default.dispatch(context, block)
    }
}
