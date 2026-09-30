package fr.arthurbrugiere.forgeline.settings

import fr.arthurbrugiere.forgeline.core.data.trending.TrendingRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.settings.UserSettingsRepository
import fr.arthurbrugiere.forgeline.core.model.FeedKind
import fr.arthurbrugiere.forgeline.core.model.InboxCheckInterval
import fr.arthurbrugiere.forgeline.core.model.ThemeMode
import fr.arthurbrugiere.forgeline.core.model.UserSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: UserSettingsRepository,
    trending: TrendingRepository,
) : ViewModel() {
    val settings: StateFlow<UserSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    /** When each forge measured on the phone was last measured, by host. */
    val measuredAt: StateFlow<Map<String, Long>> = trending.observeMeasuredAt()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun setTrendingMeasured(host: String, measured: Boolean) {
        viewModelScope.launch { repository.setTrendingMeasured(host, measured) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { repository.setThemeMode(mode) }
    }

    fun setAmoledBlack(enabled: Boolean) {
        viewModelScope.launch { repository.setAmoledBlack(enabled) }
    }

    fun setInboxCheckInterval(interval: InboxCheckInterval) {
        viewModelScope.launch { repository.setInboxCheckInterval(interval) }
    }

    fun setSeparateInboxPerForge(enabled: Boolean) {
        viewModelScope.launch { repository.setSeparateInboxPerForge(enabled) }
    }

    fun setFeedKindShown(kind: FeedKind, shown: Boolean) {
        viewModelScope.launch { repository.setFeedKindShown(kind, shown) }
    }
}
