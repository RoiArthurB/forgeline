package fr.arthurbrugiere.forgeline

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.model.ThemeMode
import fr.arthurbrugiere.forgeline.core.model.UserSettings
import fr.arthurbrugiere.forgeline.core.testing.FakeInboxRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeUserSettingsRepository
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class MainViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun exposes_the_stored_settings_once_loaded() = runTest {
        val stored = UserSettings(themeMode = ThemeMode.DARK, amoledBlack = true)
        val viewModel = MainViewModel(FakeUserSettingsRepository(stored), FakeInboxRepository())

        viewModel.uiState.test {
            val state = awaitItem().let { if (it is MainUiState.Loading) awaitItem() else it }
            assertThat(state).isEqualTo(MainUiState.Ready(stored))
        }
    }

    @Test
    fun loads_settings_without_any_subscriber() = runTest {
        // The splash screen polls uiState.value before the UI subscribes; it must not wait for one.
        val viewModel = MainViewModel(FakeUserSettingsRepository(), FakeInboxRepository())

        assertThat(viewModel.uiState.value).isEqualTo(MainUiState.Ready(UserSettings()))
    }

    @Test
    fun a_thread_opened_from_its_notification_is_read() = runTest {
        // Regression: a thread opened from its notification or the widget stayed unread in the Inbox.
        val inbox = FakeInboxRepository()
        val viewModel = MainViewModel(FakeUserSettingsRepository(), inbox)

        viewModel.threadOpened("github:github.com:me", "42")

        assertThat(inbox.actions).containsExactly("read:42")
    }
}
