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
    implementation(libs.coil.compose)
    implementation(libs.sora.language.textmate)
    implementation(libs.sora.editor)
    implementation(libs.sora.oniguruma.native)
    implementation(libs.gson)

    testImplementation(projects.core.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
