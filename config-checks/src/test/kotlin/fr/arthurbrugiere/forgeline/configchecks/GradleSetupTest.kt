package fr.arthurbrugiere.forgeline.configchecks

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GradleSetupTest {
    @Test
    fun gradle_ignores_host_java_installs_and_provisions_a_full_jdk() {
        // The owner's laptop only has a JRE: Hilt's compile task picked it and had no javac.
        assertThat(Repo.text("gradle.properties")).contains("org.gradle.java.installations.auto-detect=false")
        assertThat(Repo.text("gradle/gradle-daemon-jvm.properties")).contains("toolchainVersion=21")
    }

    @Test
    fun unit_tests_open_the_jdk_internals_robolectric_needs_for_android_16() {
        val conventions = Repo.text("build-logic/convention/src/main/kotlin/ProjectExtensions.kt")
        assertThat(conventions).contains("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
    }

    @Test
    fun pure_kotlin_modules_run_their_tests_under_the_ci_unit_test_task() {
        // CI runs `testDebugUnitTest`, which only Android modules had: the pure Kotlin modules'
        // tests (including the GitHub API ones) silently never ran in CI.
        assertThat(Repo.text(".github/workflows/ci.yml")).contains("./gradlew testDebugUnitTest")
        val jvmConvention = Repo.text("build-logic/convention/src/main/kotlin/JvmLibraryConventionPlugin.kt")
        assertThat(jvmConvention).contains("""tasks.register("testDebugUnitTest")""")
    }
}
