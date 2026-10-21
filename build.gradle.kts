plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.aboutlibraries) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.spotless)
}

// One formatter for the whole repository: `./gradlew spotlessApply` fixes, `spotlessCheck` tells (CI). Its rules are
// the Kotlin section of .editorconfig, read here since Spotless doesn't pick its ktlint switches up from the file.
val kotlinStyle: Map<String, String> = rootProject.file(".editorconfig").readLines()
    .dropWhile { it.trim() != "[*.{kt,kts}]" }.drop(1)
    .takeWhile { !it.trim().startsWith("[") }
    .map { it.substringBefore('#').trim() }
    .filter { '=' in it }
    .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(kotlinStyle)
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(kotlinStyle)
    }
}
