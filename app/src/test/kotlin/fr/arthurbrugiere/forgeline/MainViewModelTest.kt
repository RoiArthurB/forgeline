package fr.arthurbrugiere.forgeline

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.model.ThemeMode
import fr.arthurbrugiere.forgeline.core.model.UserSettings
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
        val viewModel = MainViewModel(FakeUserSettingsRepository(stored))

        viewModel.uiState.test {
            val state = awaitItem().let { if (it is MainUiState.Loading) awaitItem() else it }
            assertThat(state).isEqualTo(MainUiState.Ready(stored))
        }
    }

    @Test
    fun loads_settings_without_any_subscriber() = runTest {
        // The splash screen polls uiState.value before the UI subscribes; it must not wait for one.
        val viewModel = MainViewModel(FakeUserSettingsRepository())

        assertThat(viewModel.uiState.value).isEqualTo(MainUiState.Ready(UserSettings()))
    }
}
