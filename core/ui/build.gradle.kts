plugins {
    alias(libs.plugins.forgeline.android.library)
    alias(libs.plugins.forgeline.android.compose)
}

android {
    namespace = "fr.arthurbrugiere.forgeline.core.ui"
}

dependencies {
    api(projects.core.model)
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.material3)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
