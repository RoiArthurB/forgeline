plugins {
    alias(libs.plugins.forgeline.android.application)
    alias(libs.plugins.forgeline.android.compose)
    alias(libs.plugins.forgeline.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.aboutlibraries)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "fr.arthurbrugiere.forgeline"

    defaultConfig {
        applicationId = "fr.arthurbrugiere.forgeline"
        versionCode = providers.gradleProperty("forgeline.versionCode").get().toInt()
        versionName = providers.gradleProperty("forgeline.versionName").get()
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.ui)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.material3.navigation.suite)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.aboutlibraries.compose.m3)

    testImplementation(projects.core.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
