package fr.arthurbrugiere.forgeline

import androidx.compose.ui.test.junit4.ComposeTestRule

/** Robolectric qualifiers for a typical phone (Pixel 8-sized). */
const val PHONE = "w411dp-h914dp-xxhdpi"

/**
 * How long a whole-app test waits for what it expects on screen. A wait ends as soon as it is met, so this only costs
 * when something is wrong; five seconds were too few for CI's shared runners, where the first screen after signing
 * in timed out now and then (three runs out of four on 2026-10-12, a dependency bump among them).
 */
const val FLOW_WAIT_MILLIS = 15_000L

/** How long a fake forge takes to answer a write in a whole-app test: long enough to come back after the screen idles. */
const val WRITE_LATENCY_MILLIS = 300L

/**
 * Waits until a fake has been asked what [asked] looks for. Compose's waitUntil only runs the main thread's queue when
 * its condition reads the screen: a fake's list doesn't, so a write that went off the main thread for its token and came
 * back once the screen had gone idle never ran, and the wait ran out with nothing sent (2026-10-16, twice). Settled
 * each time round, the way a wait on the screen is.
 */
fun ComposeTestRule.waitUntilAsked(asked: () -> Boolean) = waitUntil(FLOW_WAIT_MILLIS) {
    waitForIdle()
    asked()
}
