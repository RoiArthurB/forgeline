package fr.arthurbrugiere.forgeline.settings

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.model.FeedKind
import fr.arthurbrugiere.forgeline.core.model.InboxCheckInterval
import fr.arthurbrugiere.forgeline.core.model.ThemeMode
import fr.arthurbrugiere.forgeline.core.model.UserSettings
import fr.arthurbrugiere.forgeline.core.testing.FakeUserSettingsRepository
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class SettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeUserSettingsRepository()
    // Lazy: must be created after MainDispatcherRule has installed the test Main dispatcher.
    private val viewModel by lazy { SettingsViewModel(repository) }

    @Test
    fun reflects_repository_settings() = runTest {
        viewModel.settings.test {
            assertThat(awaitItem()).isEqualTo(UserSettings())
            repository.setThemeMode(ThemeMode.LIGHT)
            assertThat(awaitItem().themeMode).isEqualTo(ThemeMode.LIGHT)
        }
    }

    @Test
    fun forwards_every_change_to_the_repository() = runTest {
        viewModel.setThemeMode(ThemeMode.DARK)
        viewModel.setAmoledBlack(true)
        viewModel.setInboxCheckInterval(InboxCheckInterval.OFF)
        viewModel.setFeedKindShown(FeedKind.STARS, false)

        assertThat(repository.settings.first()).isEqualTo(
            UserSettings(
                themeMode = ThemeMode.DARK, amoledBlack = true, inboxCheckInterval = InboxCheckInterval.OFF,
                feedKinds = FeedKind.defaults - FeedKind.STARS,
            ),
        )
    }
}
