plugins {
    alias(libs.plugins.forgeline.android.application)
    alias(libs.plugins.forgeline.android.compose)
    alias(libs.plugins.forgeline.hilt)
}

android {
    namespace = "fr.arthurbrugiere.forgeline"

    defaultConfig {
        applicationId = "fr.arthurbrugiere.forgeline"
        versionCode = providers.gradleProperty("forgeline.versionCode").get().toInt()
        versionName = providers.gradleProperty("forgeline.versionName").get()
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
