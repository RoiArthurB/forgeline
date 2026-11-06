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
        // Codeberg's OAuth application (public, like GitHub's): see docs/CODEBERG_OAUTH_APP.md.
        val codebergClientId = providers.gradleProperty("forgeline.codebergClientId").getOrElse("").trim()
        buildConfigField("String", "CODEBERG_CLIENT_ID", "\"$codebergClientId\"")
        // GitLab's OAuth application (public, like Codeberg's): see docs/GITLAB.md.
        val gitlabClientId = providers.gradleProperty("forgeline.gitlabClientId").getOrElse("").trim()
        buildConfigField("String", "GITLAB_CLIENT_ID", "\"$gitlabClientId\"")
        // gitea.com's OAuth application (public, like Codeberg's): see docs/GITEA.md.
        val giteaClientId = providers.gradleProperty("forgeline.giteaClientId").getOrElse("").trim()
        buildConfigField("String", "GITEA_CLIENT_ID", "\"$giteaClientId\"")
        // Codeberg has no trending page: a daily job publishes one. Empty leaves Codeberg out of Trending.
        val codebergTrendingUrl = providers.gradleProperty("forgeline.codebergTrendingUrl").getOrElse("").trim()
        buildConfigField("String", "CODEBERG_TRENDING_URL", "\"$codebergTrendingUrl\"")
        // Nor has gitlab.com: the same job publishes its list. Empty leaves GitLab out of Trending.
        val gitlabTrendingUrl = providers.gradleProperty("forgeline.gitlabTrendingUrl").getOrElse("").trim()
        buildConfigField("String", "GITLAB_TRENDING_URL", "\"$gitlabTrendingUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }

    dependenciesInfo {
        // The APK would carry the dependency list encrypted with Google Play's key: an unreadable blob that
        // F-Droid and IzzyOnDroid flag. The bundle, which only Play takes, keeps it.
        includeInApk = false
        includeInBundle = true
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

    packaging {
        jniLibs {
            // AndroidX's two native libraries and Oniguruma's come built (stripped, or ~10 KB): stripping them again needs
            // the NDK installed for a few KB, so they are packed as they come, without the warning at every build.
            keepDebugSymbols += setOf("**/libandroidx.graphics.path.so", "**/libdatastore_shared_counter.so", "**/libonig.so", "**/liboniguruma-binding.so")
            // Native Oniguruma colors code exactly as VS Code does, at about 1 MB an ABI (native code isn't compressed):
            // it ships for 64-bit devices only. 32-bit ones fall back to joni, where a few grammar rules don't apply.
            excludes += listOf("armeabi-v7a", "x86").flatMap { abi -> listOf("lib/$abi/libonig.so", "lib/$abi/liboniguruma-binding.so") }
        }
    }
}

// StoreIconTest compares the stores' icon with the launcher's: a new icon must run it again.
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("fastlane/metadata/android/en-US/images/icon.png")).optional().withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.ui)
    implementation(projects.core.markdown)
    implementation(projects.forge.github)
    implementation(projects.forge.forgejo)
    implementation(projects.forge.gitlab)
    implementation(projects.forge.tangled)
    implementation(projects.forge.sourcehut)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.ktor3)
    implementation(libs.coil.svg)
    implementation(libs.coil.gif)
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
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.androidx.profileinstaller)
    baselineProfile(projects.baselineprofile)

    testImplementation(projects.core.testing)
    testImplementation(libs.hilt.android.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.ktor.client.mock)
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
