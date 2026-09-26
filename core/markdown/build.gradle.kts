plugins {
    alias(libs.plugins.forgeline.android.library)
    alias(libs.plugins.forgeline.android.compose)
}

android {
    namespace = "fr.arthurbrugiere.forgeline.core.markdown"
}

dependencies {
    api(projects.core.ui)
    api(libs.markdown.renderer.m3)
    implementation(libs.markdown.renderer.coil3)
    implementation(libs.markdown.renderer.code)
    implementation(libs.coil.compose)
    implementation(libs.highlights)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
