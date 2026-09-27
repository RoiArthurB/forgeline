package fr.arthurbrugiere.forgeline.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generate with `./gradlew :app:generateBaselineProfile` (uses a Gradle managed emulator), or run
 * the Performance workflow and commit its artifact to app/src/release/generated/baselineProfiles/.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    /** Launch only: the startup profile also orders the DEX so launch code loads first. */
    @Test
    fun startup() = rule.collect(packageName = PACKAGE_NAME, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
    }

    /** Everyday journeys, precompiled so the first scroll and screen of the day don't jank. */
    @Test
    fun journeys() = rule.collect(packageName = PACKAGE_NAME) {
        pressHome()
        startActivityAndWait()
        browseTabs()
        readTrending()
        search("kotlin")
    }
}
