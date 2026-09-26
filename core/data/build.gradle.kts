plugins {
    alias(libs.plugins.forgeline.android.library)
    alias(libs.plugins.forgeline.hilt)
}

android {
    namespace = "fr.arthurbrugiere.forgeline.core.data"
}

dependencies {
    api(projects.core.model)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
