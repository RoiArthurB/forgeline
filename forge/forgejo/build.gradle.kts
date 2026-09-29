plugins {
    alias(libs.plugins.forgeline.jvm.library)
}

dependencies {
    api(projects.core.forge)
}

// Contract tests against the real Codeberg, run nightly by .github/workflows/live.yml. They pin what
// docs/CODEBERG.md relies on before the Forgejo client exists, and all run without an account.
testing {
    suites {
        register<JvmTestSuite>("liveTest") {
            useJUnit(libs.versions.junit)
            dependencies {
                implementation(libs.truth)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
            }
            targets.all {
                testTask.configure { outputs.upToDateWhen { false } }
            }
        }
    }
}
