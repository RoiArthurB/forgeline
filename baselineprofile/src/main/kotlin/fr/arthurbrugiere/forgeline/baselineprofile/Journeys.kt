package fr.arthurbrugiere.forgeline.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

const val PACKAGE_NAME = "fr.arthurbrugiere.forgeline"

private const val TIMEOUT_MS = 5_000L
private const val NETWORK_TIMEOUT_MS = 15_000L

fun MacrobenchmarkScope.openTab(label: String) {
    device.wait(Until.hasObject(By.text(label)), TIMEOUT_MS)
    device.findObject(By.text(label))?.click()
    device.waitForIdle()
}

/** The daily tab tour; everything signed-out users see. */
fun MacrobenchmarkScope.browseTabs() {
    openTab("Feed")
    openTab("Trending")
    openTab("You")
    openTab("Inbox")
}

/**
 * The daily ritual: Trending, scroll it, open a repo and read its README. Content comes from the
 * real GitHub, so every wait tolerates a slow or rate-limited network instead of failing.
 */
fun MacrobenchmarkScope.readTrending() {
    openTab("Trending")
    if (!device.wait(Until.hasObject(By.textContains("today")), NETWORK_TIMEOUT_MS)) return
    device.findObject(By.scrollable(true))?.let { list ->
        list.setGestureMargin(device.displayWidth / 5)
        list.fling(Direction.DOWN)
        list.fling(Direction.UP)
    }
    device.findObject(By.textContains("today"))?.click() ?: return
    device.wait(Until.hasObject(By.text("README")), NETWORK_TIMEOUT_MS)
    device.findObject(By.scrollable(true))?.fling(Direction.DOWN)
    device.pressBack()
    device.waitForIdle()
}

fun MacrobenchmarkScope.search(query: String) {
    device.wait(Until.hasObject(By.desc("Search")), TIMEOUT_MS)
    device.findObject(By.desc("Search"))?.click() ?: return
    device.wait(Until.hasObject(By.clazz("android.widget.EditText")), TIMEOUT_MS)
    device.findObject(By.clazz("android.widget.EditText"))?.text = query
    device.pressEnter()
    device.wait(Until.hasObject(By.textContains("results")), NETWORK_TIMEOUT_MS)
    device.findObject(By.text("People"))?.click()
    device.waitForIdle()
    device.pressBack()
    device.waitForIdle()
}
