package fr.arthurbrugiere.forgeline

/** Robolectric qualifiers for a typical phone (Pixel 8-sized). */
const val PHONE = "w411dp-h914dp-xxhdpi"

/**
 * How long a whole-app test waits for what it expects on screen. A wait ends as soon as it is met, so this only costs
 * when something is wrong; five seconds were too few for CI's shared runners, where the first screen after signing
 * in timed out now and then (three runs out of four on 2026-10-12, a dependency bump among them).
 */
const val FLOW_WAIT_MILLIS = 15_000L
