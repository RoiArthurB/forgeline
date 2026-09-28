package fr.arthurbrugiere.forgeline.core.testing

import fr.arthurbrugiere.forgeline.core.data.settings.UserSettingsRepository
import fr.arthurbrugiere.forgeline.core.model.FeedKind
import fr.arthurbrugiere.forgeline.core.model.InboxCheckInterval
import fr.arthurbrugiere.forgeline.core.model.ThemeMode
import fr.arthurbrugiere.forgeline.core.model.UserSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class FakeUserSettingsRepository(initial: UserSettings = UserSettings()) : UserSettingsRepository {
    private val state = MutableStateFlow(initial)

    override val settings: StateFlow<UserSettings> = state

    override suspend fun setThemeMode(mode: ThemeMode) = state.update { it.copy(themeMode = mode) }


    override suspend fun setAmoledBlack(enabled: Boolean) = state.update { it.copy(amoledBlack = enabled) }

    override suspend fun setFeedKindShown(kind: FeedKind, shown: Boolean) =
        state.update { it.copy(feedKinds = if (shown) it.feedKinds + kind else it.feedKinds - kind) }

    override suspend fun setInboxCheckInterval(interval: InboxCheckInterval) = state.update { it.copy(inboxCheckInterval = interval) }
}
