plugins {
    alias(libs.plugins.forgeline.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

// git.sr.ht, through its GraphQL API, with a personal access token: see docs/SOURCEHUT.md.
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

// Contract tests against the real git.sr.ht, run nightly by .github/workflows/live.yml when SOURCEHUT_LIVE_TOKEN is set:
// SourceHut answers nothing without a token.
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
                    environment("SOURCEHUT_LIVE_TOKEN", providers.environmentVariable("SOURCEHUT_LIVE_TOKEN").getOrElse(""))
                    outputs.upToDateWhen { false }
                }
            }
        }
    }
}
