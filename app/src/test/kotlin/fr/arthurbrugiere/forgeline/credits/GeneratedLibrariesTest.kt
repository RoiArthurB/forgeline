package fr.arthurbrugiere.forgeline.credits

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.util.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeneratedLibrariesTest {
    @Test
    fun credits_cover_the_dependencies_the_app_ships_with() {
        val libs = Libs.Builder().withContext(ApplicationProvider.getApplicationContext()).build()

        val ids = libs.libraries.map { it.uniqueId }
        assertThat(ids).containsAtLeast(
            "com.google.dagger:hilt-android",
            "androidx.navigation3:navigation3-ui",
            "com.mikepenz:aboutlibraries-compose-m3",
        )
        assertThat(libs.libraries.all { it.licenses.isNotEmpty() || it.uniqueId.startsWith("fr.arthurbrugiere") })
            .isTrue()
    }
}
