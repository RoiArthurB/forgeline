plugins {
    alias(libs.plugins.forgeline.jvm.library)
    alias(libs.plugins.kotlin.serialization)
    application
}

// The daily Codeberg Trending job, run by .github/workflows/codeberg-trending.yml (see docs/CODEBERG.md#trending).
application {
    mainClass.set("fr.arthurbrugiere.forgeline.tools.trending.MainKt")
}

dependencies {
    implementation(projects.forge.forgejo)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}
