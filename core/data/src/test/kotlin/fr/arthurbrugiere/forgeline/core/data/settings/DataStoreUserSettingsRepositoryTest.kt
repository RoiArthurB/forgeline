package fr.arthurbrugiere.forgeline.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.model.ThemeMode
import fr.arthurbrugiere.forgeline.core.model.UserSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreUserSettingsRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.dataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = backgroundScope) {
            tmp.newFile("settings.preferences_pb").also { it.delete() }
        }

    @Test
    fun emits_defaults_when_nothing_is_stored() = runTest {
        val repository = DataStoreUserSettingsRepository(dataStore())

        assertThat(repository.settings.first()).isEqualTo(UserSettings())
    }

    @Test
    fun persists_every_setting() = runTest {
        val repository = DataStoreUserSettingsRepository(dataStore())

        repository.setThemeMode(ThemeMode.DARK)
        repository.setDynamicColor(false)
        repository.setAmoledBlack(true)

        assertThat(repository.settings.first()).isEqualTo(
            UserSettings(themeMode = ThemeMode.DARK, dynamicColor = false, amoledBlack = true),
        )
    }

    @Test
    fun unknown_theme_value_falls_back_to_system() = runTest {
        val store = dataStore()
        store.edit { it[stringPreferencesKey("theme_mode")] = "SEPIA" }

        val repository = DataStoreUserSettingsRepository(store)

        assertThat(repository.settings.first().themeMode).isEqualTo(ThemeMode.SYSTEM)
    }

    @Test
    fun emits_updates_as_they_happen() = runTest {
        val repository = DataStoreUserSettingsRepository(dataStore())

        repository.settings.test {
            assertThat(awaitItem().amoledBlack).isFalse()
            repository.setAmoledBlack(true)
            assertThat(awaitItem().amoledBlack).isTrue()
        }
    }
}
