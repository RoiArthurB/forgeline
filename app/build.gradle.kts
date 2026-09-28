plugins {
    alias(libs.plugins.forgeline.android.application)
    alias(libs.plugins.forgeline.android.compose)
    alias(libs.plugins.forgeline.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.aboutlibraries)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "fr.arthurbrugiere.forgeline"

    defaultConfig {
        applicationId = "fr.arthurbrugiere.forgeline"
        versionCode = providers.gradleProperty("forgeline.versionCode").get().toInt()
        versionName = providers.gradleProperty("forgeline.versionName").get()
        testInstrumentationRunner = "fr.arthurbrugiere.forgeline.HiltTestRunner"

        // Public by design: the device flow needs no client secret. Empty disables the device flow.
        val githubClientId = providers.gradleProperty("forgeline.githubClientId").getOrElse("").trim()
        buildConfigField("String", "GITHUB_CLIENT_ID", "\"$githubClientId\"")
    }

    buildFeatures {
        buildConfig = true
    }

    val keystorePath = providers.environmentVariable("FORGELINE_KEYSTORE_PATH")
    if (keystorePath.isPresent) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath.get())
                storePassword = providers.environmentVariable("FORGELINE_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("FORGELINE_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("FORGELINE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            // Debug-signed when no release key is configured, so local and benchmark builds install.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.ui)
    implementation(projects.core.markdown)
    implementation(projects.forge.github)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.ktor3)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.work.runtime)

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
    implementation(libs.androidx.profileinstaller)
    baselineProfile(projects.baselineprofile)

    testImplementation(projects.core.testing)
    testImplementation(libs.hilt.android.testing)
    testImplementation(libs.androidx.work.testing)
    kspTest(libs.hilt.compiler)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(projects.core.testing)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
}
