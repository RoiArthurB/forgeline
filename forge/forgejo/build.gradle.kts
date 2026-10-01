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

    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Contract tests against the real Codeberg, run nightly by .github/workflows/live.yml. They pin what
// docs/CODEBERG.md relies on. Most run without an account; the signed-in ones only read, need
// CODEBERG_LIVE_TOKEN and are skipped without it.
testing {
    suites {
        register<JvmTestSuite>("liveTest") {
            useJUnit(libs.versions.junit)
            dependencies {
                implementation(project())
                implementation(libs.truth)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
            }
            targets.all {
                testTask.configure {
                    environment("CODEBERG_LIVE_TOKEN", providers.environmentVariable("CODEBERG_LIVE_TOKEN").getOrElse(""))
                    outputs.upToDateWhen { false }
                }
            }
        }
    }
}
