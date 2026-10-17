package fr.arthurbrugiere.forgeline.core.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Answers after [millis] spent on another thread, the way a forge's reply comes back from the network: the caller then
 * resumes on its own thread later, once whatever was waiting for it may have gone idle. Nothing when [millis] is 0.
 */
internal suspend fun answerLate(millis: Long) {
    if (millis > 0) withContext(Dispatchers.IO) { Thread.sleep(millis) }
}
