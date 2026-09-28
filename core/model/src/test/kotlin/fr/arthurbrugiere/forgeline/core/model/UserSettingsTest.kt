package fr.arthurbrugiere.forgeline.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UserSettingsTest {
    @Test
    fun defaults_follow_the_system_without_amoled() {
        val settings = UserSettings()

        assertThat(settings.themeMode).isEqualTo(ThemeMode.SYSTEM)
        assertThat(settings.amoledBlack).isFalse()
    }

    @Test
    fun the_inbox_is_checked_hourly_by_default_and_15_minutes_is_the_minimum() {
        assertThat(UserSettings().inboxCheckInterval).isEqualTo(InboxCheckInterval.HOUR_1)
        // WorkManager can't run periodic work more often than every 15 minutes.
        assertThat(InboxCheckInterval.entries.mapNotNull { it.minutes }.min()).isEqualTo(15)
    }
}
