plugins {
    alias(libs.plugins.forgeline.jvm.library)
    alias(libs.plugins.kotlin.serialization)
    application
}

// The daily Trending job for forges that have none (Codeberg, gitlab.com), run by .github/workflows/trending.yml
// (see docs/CODEBERG.md#trending and docs/GITLAB.md#trending).
application {
    mainClass.set("fr.arthurbrugiere.forgeline.tools.trending.MainKt")
}

dependencies {
    implementation(projects.forge.forgejo)
    implementation(projects.forge.gitlab)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}
