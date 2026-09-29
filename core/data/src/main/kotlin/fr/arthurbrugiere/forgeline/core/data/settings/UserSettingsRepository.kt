package fr.arthurbrugiere.forgeline.core.data.settings

import fr.arthurbrugiere.forgeline.core.model.FeedKind
import fr.arthurbrugiere.forgeline.core.model.InboxCheckInterval
import fr.arthurbrugiere.forgeline.core.model.ThemeMode
import fr.arthurbrugiere.forgeline.core.model.UserSettings
import kotlinx.coroutines.flow.Flow

interface UserSettingsRepository {
    val settings: Flow<UserSettings>

    suspend fun setThemeMode(mode: ThemeMode)

    suspend fun setAmoledBlack(enabled: Boolean)

    suspend fun setInboxCheckInterval(interval: InboxCheckInterval)

    suspend fun setFeedKindShown(kind: FeedKind, shown: Boolean)

    suspend fun setSeparateInboxPerForge(enabled: Boolean)
}
