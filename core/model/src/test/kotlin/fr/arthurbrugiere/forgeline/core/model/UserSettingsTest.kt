package fr.arthurbrugiere.forgeline.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UserSettingsTest {
    @Test
    fun defaults_follow_the_system_with_material_you_and_no_amoled() {
        val settings = UserSettings()

        assertThat(settings.themeMode).isEqualTo(ThemeMode.SYSTEM)
        assertThat(settings.dynamicColor).isTrue()
        assertThat(settings.amoledBlack).isFalse()
    }
}
