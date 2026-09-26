package fr.arthurbrugiere.forgeline.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

const val PACKAGE_NAME = "fr.arthurbrugiere.forgeline"

private const val TIMEOUT_MS = 5_000L

fun MacrobenchmarkScope.openTab(label: String) {
    device.wait(Until.hasObject(By.text(label)), TIMEOUT_MS)
    device.findObject(By.text(label)).click()
    device.waitForIdle()
}

/** The journeys a user does daily; their code gets precompiled by the baseline profile. */
fun MacrobenchmarkScope.browseTabs() {
    openTab("Feed")
    openTab("Trending")
    openTab("You")
    openTab("Inbox")
}
