plugins {
    alias(libs.plugins.forgeline.android.library)
    alias(libs.plugins.forgeline.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "fr.arthurbrugiere.forgeline.core.data"
}

dependencies {
    api(projects.core.model)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
