package fr.arthurbrugiere.forgeline.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

const val PACKAGE_NAME = "fr.arthurbrugiere.forgeline"

private const val TIMEOUT_MS = 5_000L
private const val NETWORK_TIMEOUT_MS = 15_000L

/**
 * A tab is named by its description, not by its text (the label under the icon is hidden from accessibility so the
 * tab is announced once). Regression: looking for the text found nothing and skipped the tab without a word, so the
 * profile was recorded without the tabs. A tab that can't be found now fails the recording.
 */
fun MacrobenchmarkScope.openTab(label: String) {
    check(device.wait(Until.hasObject(By.desc(label)), TIMEOUT_MS)) { "No \"$label\" tab on screen" }
    device.findObject(By.desc(label)).click()
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
    // A row says "+120 today" to accessibility only (it shows "+120"), so it is found by description.
    if (!device.wait(Until.hasObject(By.descContains("today")), NETWORK_TIMEOUT_MS)) return
    device.findObject(By.scrollable(true))?.let { list ->
        list.setGestureMargin(device.displayWidth / 5)
        list.fling(Direction.DOWN)
        list.fling(Direction.UP)
    }
    device.findObject(By.descContains("today"))?.click() ?: return
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

/** Opens a header button by its description, waits for its screen, and comes back. */
private fun MacrobenchmarkScope.visit(description: String, until: androidx.test.uiautomator.BySelector) {
    check(device.wait(Until.hasObject(By.desc(description)), TIMEOUT_MS)) { "No \"$description\" button on screen" }
    device.findObject(By.desc(description)).click()
    device.wait(Until.hasObject(until), NETWORK_TIMEOUT_MS)
    device.findObject(By.scrollable(true))?.fling(Direction.DOWN)
    device.pressBack()
    device.waitForIdle()
}

/**
 * The discovery screens added in 0.10, as someone signed out meets them: First issues from Trending's header, the
 * collections from the You tab's, and a topic's page from a link to it (the app is the link's target).
 */
fun MacrobenchmarkScope.discover() {
    openTab("Trending")
    visit("First issues", By.text("First issues"))
    openTab("You")
    visit("Collections", By.text("Collections"))
    device.executeShellCommand("am start -a android.intent.action.VIEW -d https://github.com/topics/kotlin $PACKAGE_NAME")
    device.wait(Until.hasObject(By.textContains("repositories")), NETWORK_TIMEOUT_MS)
    device.findObject(By.scrollable(true))?.let { list ->
        list.setGestureMargin(device.displayWidth / 5)
        list.fling(Direction.DOWN)
        list.fling(Direction.UP)
    }
    device.pressBack()
    device.waitForIdle()
}
