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
