plugins {
    alias(libs.plugins.forgeline.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

// tangled.org: read without an account from its index (api.tangled.org), stars and follows written to the person's AT Protocol server: see docs/TANGLED.md.
dependencies {
    api(projects.forge.common)
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Contract tests against the real Tangled, run nightly by .github/workflows/live.yml. Everything is public: no account.
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
                testTask.configure { outputs.upToDateWhen { false } }
            }
        }
    }
}
