plugins {
    alias(libs.plugins.forgeline.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.core.forge)
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jsoup)

    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Contract tests against the real GitHub, run nightly by .github/workflows/live.yml.
// Authenticated checks need LIVE_TEST_PAT and are skipped without it.
testing {
    suites {
        register<JvmTestSuite>("liveTest") {
            useJUnit(libs.versions.junit)
            dependencies {
                implementation(project())
                implementation(libs.truth)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.kotlinx.coroutines.core)
            }
            targets.all {
                testTask.configure {
                    environment("LIVE_TEST_PAT", providers.environmentVariable("LIVE_TEST_PAT").getOrElse(""))
                    outputs.upToDateWhen { false }
                }
            }
        }
    }
}
