plugins {
    alias(libs.plugins.forgeline.android.library)
}

android {
    namespace = "fr.arthurbrugiere.forgeline.core.testing"
}

dependencies {
    api(projects.core.data)
    api(projects.core.forge)
    api(libs.junit)
    api(libs.kotlinx.coroutines.test)
    api(libs.turbine)
    api(libs.truth)
}
