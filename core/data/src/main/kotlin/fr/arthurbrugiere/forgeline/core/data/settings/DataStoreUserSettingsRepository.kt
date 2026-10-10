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

    override val settings: Flow<UserSettings> = dataStore.data.map { it.toSettings() }.distinctUntilChanged()

    /** A name stored by an older or newer version that no longer means anything reads as the default. */
    private inline fun <reified T : Enum<T>> Preferences.choice(key: Preferences.Key<String>, default: T): T =
        this[key]?.let { stored -> enumValues<T>().firstOrNull { it.name == stored } } ?: default

    private fun Preferences.toSettings(): UserSettings {
        val prefs = this
        val defaults = UserSettings()
        return UserSettings(
                startTab = choice(START_TAB, defaults.startTab),
                trendingPeriod = choice(TRENDING_PERIOD, defaults.trendingPeriod),
                inboxSwipeRight = choice(SWIPE_RIGHT, defaults.inboxSwipeRight),
                inboxSwipeLeft = choice(SWIPE_LEFT, defaults.inboxSwipeLeft),
                undoDelay = choice(UNDO_DELAY, defaults.undoDelay),
                shareTap = choice(SHARE_TAP, defaults.shareTap),
                loadConversationsAhead = prefs[LOAD_AHEAD] ?: defaults.loadConversationsAhead,
                doubleTapReaction = prefs[DOUBLE_TAP] ?: defaults.doubleTapReaction,
                swipeToReply = prefs[SWIPE_REPLY] ?: defaults.swipeToReply,
                openAtUnread = prefs[OPEN_AT_UNREAD] ?: defaults.openAtUnread,
                readingMarks = prefs[READING_MARKS] ?: defaults.readingMarks,
                themeMode = prefs[THEME_MODE]
                    ?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
                    ?: defaults.themeMode,
                amoledBlack = prefs[AMOLED_BLACK] ?: defaults.amoledBlack,
                separateInboxPerForge = prefs[SEPARATE_INBOX] ?: defaults.separateInboxPerForge,
                measuredTrending = prefs[MEASURED_TRENDING] ?: defaults.measuredTrending,
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

    override suspend fun update(change: (UserSettings) -> UserSettings) {
        dataStore.edit { prefs ->
            val wanted = change(prefs.toSettings())
            prefs[THEME_MODE] = wanted.themeMode.name
            prefs[AMOLED_BLACK] = wanted.amoledBlack
            prefs[SEPARATE_INBOX] = wanted.separateInboxPerForge
            prefs[INBOX_CHECK] = wanted.inboxCheckInterval.name
            prefs[START_TAB] = wanted.startTab.name
            prefs[TRENDING_PERIOD] = wanted.trendingPeriod.name
            prefs[SWIPE_RIGHT] = wanted.inboxSwipeRight.name
            prefs[SWIPE_LEFT] = wanted.inboxSwipeLeft.name
            prefs[UNDO_DELAY] = wanted.undoDelay.name
            prefs[SHARE_TAP] = wanted.shareTap.name
            prefs[LOAD_AHEAD] = wanted.loadConversationsAhead
            prefs[DOUBLE_TAP] = wanted.doubleTapReaction
            prefs[SWIPE_REPLY] = wanted.swipeToReply
            prefs[OPEN_AT_UNREAD] = wanted.openAtUnread
            prefs[READING_MARKS] = wanted.readingMarks
        }
    }

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

    override suspend fun setTrendingMeasured(host: String, measured: Boolean) {
        dataStore.edit { prefs ->
            val hosts = prefs[MEASURED_TRENDING].orEmpty()
            prefs[MEASURED_TRENDING] = if (measured) hosts + host else hosts - host
        }
    }

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val AMOLED_BLACK = booleanPreferencesKey("amoled_black")
        val SEPARATE_INBOX = booleanPreferencesKey("separate_inbox_per_forge")
        val MEASURED_TRENDING = stringSetPreferencesKey("measured_trending_hosts")
        val INBOX_CHECK = stringPreferencesKey("inbox_check_interval")
        val FEED_SHOWN = stringSetPreferencesKey("feed_kinds_shown")
        val FEED_HIDDEN = stringSetPreferencesKey("feed_kinds_hidden")
        val START_TAB = stringPreferencesKey("start_tab")
        val TRENDING_PERIOD = stringPreferencesKey("trending_period")
        val SWIPE_RIGHT = stringPreferencesKey("inbox_swipe_right")
        val SWIPE_LEFT = stringPreferencesKey("inbox_swipe_left")
        val UNDO_DELAY = stringPreferencesKey("undo_delay")
        val SHARE_TAP = stringPreferencesKey("share_tap")
        val LOAD_AHEAD = booleanPreferencesKey("load_conversations_ahead")
        val DOUBLE_TAP = booleanPreferencesKey("double_tap_reaction")
        val SWIPE_REPLY = booleanPreferencesKey("swipe_to_reply")
        val OPEN_AT_UNREAD = booleanPreferencesKey("open_at_unread")
        val READING_MARKS = booleanPreferencesKey("reading_marks")
    }
}
