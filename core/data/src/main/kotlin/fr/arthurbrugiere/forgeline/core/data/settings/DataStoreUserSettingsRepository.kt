package fr.arthurbrugiere.forgeline.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.arthurbrugiere.forgeline.core.model.ThemeMode
import fr.arthurbrugiere.forgeline.core.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class DataStoreUserSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : UserSettingsRepository {

    override val settings: Flow<UserSettings> = dataStore.data
        .map { prefs ->
            val defaults = UserSettings()
            UserSettings(
                themeMode = prefs[THEME_MODE]
                    ?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
                    ?: defaults.themeMode,
                dynamicColor = prefs[DYNAMIC_COLOR] ?: defaults.dynamicColor,
                amoledBlack = prefs[AMOLED_BLACK] ?: defaults.amoledBlack,
            )
        }
        .distinctUntilChanged()

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[THEME_MODE] = mode.name }
    }

    override suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { it[DYNAMIC_COLOR] = enabled }
    }

    override suspend fun setAmoledBlack(enabled: Boolean) {
        dataStore.edit { it[AMOLED_BLACK] = enabled }
    }

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val AMOLED_BLACK = booleanPreferencesKey("amoled_black")
    }
}
