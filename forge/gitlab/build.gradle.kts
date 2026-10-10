plugins {
    alias(libs.plugins.forgeline.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

// gitlab.com, for Trending only so far. Recording the history and building the lists is Forgejo's code, shared.
dependencies {
    api(projects.core.forge)
    api(libs.ktor.client.core)
    implementation(projects.forge.forgejo)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.truth)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Signed-in checks against the real gitlab.com, run nightly by .github/workflows/live.yml. They only read, need
// GITLAB_LIVE_TOKEN and are skipped without it. The one that writes (GitLabWriteLiveTest) also needs
// GITLAB_LIVE_WRITES=1, which no workflow sets: it is run by hand, on a scratch project of its own.
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
                    environment("GITLAB_LIVE_TOKEN", providers.environmentVariable("GITLAB_LIVE_TOKEN").getOrElse(""))
                    environment("GITLAB_LIVE_WRITES", providers.environmentVariable("GITLAB_LIVE_WRITES").getOrElse(""))
                    outputs.upToDateWhen { false }
                }
            }
        }
    }
}
