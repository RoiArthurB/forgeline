package fr.arthurbrugiere.forgeline.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.model.FeedKind
import fr.arthurbrugiere.forgeline.core.model.InboxCheckInterval
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
        repository.setAmoledBlack(true)
        repository.setInboxCheckInterval(InboxCheckInterval.MIN_15)

        assertThat(repository.settings.first()).isEqualTo(
            UserSettings(themeMode = ThemeMode.DARK, amoledBlack = true, inboxCheckInterval = InboxCheckInterval.MIN_15),
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

    @Test
    fun feed_kinds_start_from_the_curated_defaults() = runTest {
        val repository = DataStoreUserSettingsRepository(dataStore())

        repository.setFeedKindShown(FeedKind.STARS, false)
        repository.setFeedKindShown(FeedKind.PUSHES, true)

        assertThat(repository.settings.first().feedKinds).isEqualTo(FeedKind.defaults - FeedKind.STARS + FeedKind.PUSHES)
    }

    @Test
    fun feed_choices_are_stored_as_differences_so_new_kinds_get_their_default() = runTest {
        val store = dataStore()
        store.edit {
            it[stringSetPreferencesKey("feed_kinds_hidden")] = setOf("STARS", "SOME_FUTURE_KIND")
            it[stringSetPreferencesKey("feed_kinds_shown")] = setOf("COMMENTS")
        }

        val kinds = DataStoreUserSettingsRepository(store).settings.first().feedKinds

        assertThat(kinds).isEqualTo(FeedKind.defaults - FeedKind.STARS + FeedKind.COMMENTS)
    }

    private val changed = UserSettings(
        themeMode = ThemeMode.DARK,
        amoledBlack = true,
        inboxCheckInterval = InboxCheckInterval.HOUR_6,
        separateInboxPerForge = true,
        startTab = fr.arthurbrugiere.forgeline.core.model.StartTab.FEED,
        trendingPeriod = fr.arthurbrugiere.forgeline.core.model.TrendingPeriod.MONTHLY,
        inboxSwipeRight = fr.arthurbrugiere.forgeline.core.model.SwipeAction.DONE,
        inboxSwipeLeft = fr.arthurbrugiere.forgeline.core.model.SwipeAction.NONE,
        undoDelay = fr.arthurbrugiere.forgeline.core.model.UndoDelay.SEC_10,
        loadConversationsAhead = false,
        doubleTapReaction = false,
        swipeToReply = false,
        shareTap = fr.arthurbrugiere.forgeline.core.model.ShareTap.COPY,
        openAtUnread = false,
        readingMarks = false,
    )

    @Test
    fun every_simple_choice_is_kept_and_read_back_by_a_new_repository() = runTest {
        val store = dataStore()

        DataStoreUserSettingsRepository(store).update { changed }

        assertThat(DataStoreUserSettingsRepository(store).settings.first()).isEqualTo(changed)
    }

    @Test
    fun no_choice_shares_its_default_with_what_the_test_changes() {
        // Guards the test above: a field left at its default there would prove nothing.
        val defaults = UserSettings()
        assertThat(changed.startTab).isNotEqualTo(defaults.startTab)
        assertThat(changed.trendingPeriod).isNotEqualTo(defaults.trendingPeriod)
        assertThat(changed.inboxSwipeRight).isNotEqualTo(defaults.inboxSwipeRight)
        assertThat(changed.inboxSwipeLeft).isNotEqualTo(defaults.inboxSwipeLeft)
        assertThat(changed.undoDelay).isNotEqualTo(defaults.undoDelay)
        assertThat(changed.loadConversationsAhead).isNotEqualTo(defaults.loadConversationsAhead)
        assertThat(changed.doubleTapReaction).isNotEqualTo(defaults.doubleTapReaction)
        assertThat(changed.swipeToReply).isNotEqualTo(defaults.swipeToReply)
        assertThat(changed.shareTap).isNotEqualTo(defaults.shareTap)
        assertThat(changed.openAtUnread).isNotEqualTo(defaults.openAtUnread)
        assertThat(changed.readingMarks).isNotEqualTo(defaults.readingMarks)
    }

    @Test
    fun changing_one_choice_leaves_the_others_and_the_feed_s_kinds_alone() = runTest {
        val repository = DataStoreUserSettingsRepository(dataStore())
        repository.setFeedKindShown(FeedKind.entries.first { !it.shownByDefault }, true)
        repository.setTrendingMeasured("git.example.org", true)
        val before = repository.settings.first()

        repository.update { it.copy(swipeToReply = false) }

        assertThat(repository.settings.first()).isEqualTo(before.copy(swipeToReply = false))
    }

    @Test
    fun the_defaults_are_how_forgeline_behaved_before_the_choices_existed() {
        val defaults = UserSettings()

        assertThat(defaults.startTab).isEqualTo(fr.arthurbrugiere.forgeline.core.model.StartTab.AUTOMATIC)
        assertThat(defaults.trendingPeriod).isEqualTo(fr.arthurbrugiere.forgeline.core.model.TrendingPeriod.DAILY)
        assertThat(defaults.inboxSwipeRight).isEqualTo(fr.arthurbrugiere.forgeline.core.model.SwipeAction.MARK_READ)
        assertThat(defaults.inboxSwipeLeft).isEqualTo(fr.arthurbrugiere.forgeline.core.model.SwipeAction.DONE)
        assertThat(defaults.undoDelay.millis).isEqualTo(5_000)
        assertThat(defaults.shareTap).isEqualTo(fr.arthurbrugiere.forgeline.core.model.ShareTap.SHARE)
        assertThat(listOf(defaults.loadConversationsAhead, defaults.doubleTapReaction, defaults.swipeToReply, defaults.openAtUnread, defaults.readingMarks)).doesNotContain(false)
    }

    @Test
    fun a_choice_stored_under_a_name_this_version_does_not_know_reads_as_the_default() = runTest {
        val store = dataStore()
        store.edit {
            it[stringPreferencesKey("start_tab")] = "DASHBOARD"
            it[stringPreferencesKey("undo_delay")] = "SEC_60"
        }

        val settings = DataStoreUserSettingsRepository(store).settings.first()

        assertThat(settings.startTab).isEqualTo(fr.arthurbrugiere.forgeline.core.model.StartTab.AUTOMATIC)
        assertThat(settings.undoDelay).isEqualTo(fr.arthurbrugiere.forgeline.core.model.UndoDelay.SEC_5)
    }
}
