package fr.arthurbrugiere.forgeline.configchecks

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Regression tests for CI bugs hit on 2026-09-26. */
class CiWorkflowTest {
    private val ci = Repo.text(".github/workflows/ci.yml")
    private val emulatorJob = ci.substringAfter("\n  emulator:")

    @Test
    fun emulator_tests_use_the_ATD_image_whose_launcher_cannot_steal_focus() {
        // A Pixel launcher ANR on the google_apis image stole window focus and broke Espresso.
        assertThat(emulatorJob).containsMatch("""target:\s*aosp_atd""")
    }

    @Test
    fun emulator_tests_close_system_dialogs_first() {
        assertThat(emulatorJob).contains("android.intent.action.CLOSE_SYSTEM_DIALOGS")
    }

    @Test
    fun emulator_tests_have_a_per_test_timeout() {
        // Without it a stuck test ran silently for 54 minutes until the job was killed.
        assertThat(emulatorJob).containsMatch("""timeout_msec=\d+""")
    }

    @Test
    fun emulator_job_is_capped_well_below_the_one_hour_default() {
        val minutes = Regex("""timeout-minutes:\s*(\d+)""").find(emulatorJob)!!.groupValues[1].toInt()
        assertThat(minutes).isAtMost(45)
    }

    @Test
    fun one_failing_module_does_not_skip_the_other_modules_emulator_tests() {
        // Without --continue, the app's failure hid that core:data's Keystore test never ran.
        val gradleLine = emulatorJob.lines().first { "connectedDebugAndroidTest" in it && "./gradlew" in it }
        assertThat(gradleLine).contains("--continue")
    }

    @Test
    fun emulator_logcat_and_reports_are_uploaded_even_when_the_job_is_cancelled() {
        assertThat(emulatorJob).contains("adb logcat")
        assertThat(emulatorJob).contains("if: failure() || cancelled()")
        assertThat(emulatorJob).contains("logcat.txt")
    }
}
