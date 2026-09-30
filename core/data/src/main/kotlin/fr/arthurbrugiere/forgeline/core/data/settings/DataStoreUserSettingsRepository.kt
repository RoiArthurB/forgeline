package fr.arthurbrugiere.forgeline.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import fr.arthurbrugiere.forgeline.core.data.di.SettingsDataStore
import fr.arthurbrugiere.forgeline.core.model.FeedKind
import fr.arthurbrugiere.forgeline.core.model.InboxCheckInterval
import fr.arthurbrugiere.forgeline.core.model.ThemeMode
import fr.arthurbrugiere.forgeline.core.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class DataStoreUserSettingsRepository @Inject constructor(
    @param:SettingsDataStore private val dataStore: DataStore<Preferences>,
) : UserSettingsRepository {

    override val settings: Flow<UserSettings> = dataStore.data
        .map { prefs ->
            val defaults = UserSettings()
            UserSettings(
                themeMode = prefs[THEME_MODE]
                    ?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
                    ?: defaults.themeMode,
                amoledBlack = prefs[AMOLED_BLACK] ?: defaults.amoledBlack,
                separateInboxPerForge = prefs[SEPARATE_INBOX] ?: defaults.separateInboxPerForge,
                inboxCheckInterval = prefs[INBOX_CHECK]
                    ?.let { stored -> InboxCheckInterval.entries.firstOrNull { it.name == stored } }
                    ?: defaults.inboxCheckInterval,
                feedKinds = FeedKind.entries.filterTo(mutableSetOf()) { kind ->
                    when (kind.name) {
                        in prefs[FEED_SHOWN].orEmpty() -> true
                        in prefs[FEED_HIDDEN].orEmpty() -> false
                        else -> kind.shownByDefault
                    }
                },
            )
        }
        .distinctUntilChanged()

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[THEME_MODE] = mode.name }
    }

    override suspend fun setAmoledBlack(enabled: Boolean) {
        dataStore.edit { it[AMOLED_BLACK] = enabled }
    }

    override suspend fun setInboxCheckInterval(interval: InboxCheckInterval) {
        dataStore.edit { it[INBOX_CHECK] = interval.name }
    }

    // Stored as differences from the defaults, so kinds added later still get their own default.
    override suspend fun setFeedKindShown(kind: FeedKind, shown: Boolean) {
        dataStore.edit { prefs ->
            prefs[FEED_SHOWN] = prefs[FEED_SHOWN].orEmpty() - kind.name + listOfNotNull(kind.name.takeIf { shown && !kind.shownByDefault })
            prefs[FEED_HIDDEN] = prefs[FEED_HIDDEN].orEmpty() - kind.name + listOfNotNull(kind.name.takeIf { !shown && kind.shownByDefault })
        }
    }

    override suspend fun setSeparateInboxPerForge(enabled: Boolean) {
        dataStore.edit { it[SEPARATE_INBOX] = enabled }
    }

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val AMOLED_BLACK = booleanPreferencesKey("amoled_black")
        val SEPARATE_INBOX = booleanPreferencesKey("separate_inbox_per_forge")
        val INBOX_CHECK = stringPreferencesKey("inbox_check_interval")
        val FEED_SHOWN = stringSetPreferencesKey("feed_kinds_shown")
        val FEED_HIDDEN = stringSetPreferencesKey("feed_kinds_hidden")
    }
}
