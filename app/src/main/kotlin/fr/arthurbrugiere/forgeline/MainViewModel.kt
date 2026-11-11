package fr.arthurbrugiere.forgeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.inbox.InboxRepository
import fr.arthurbrugiere.forgeline.core.data.settings.UserSettingsRepository
import fr.arthurbrugiere.forgeline.core.model.InboxAction
import fr.arthurbrugiere.forgeline.core.model.UserSettings
import fr.arthurbrugiere.forgeline.notifications.actOnNotification
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface MainUiState {
    data object Loading : MainUiState

    data class Ready(val settings: UserSettings) : MainUiState
}

@HiltViewModel
class MainViewModel @Inject constructor(
    settingsRepository: UserSettingsRepository,
    private val inbox: InboxRepository,
) : ViewModel() {
    val uiState: StateFlow<MainUiState> = settingsRepository.settings
        .map<UserSettings, MainUiState> { MainUiState.Ready(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MainUiState.Loading)

    /**
     * An unread thread was opened from its phone notification or the widget: it's read now, as when opened from the
     * Inbox. Regression: it stayed unread there. Held first, as a notification's own "Mark as read" is.
     */
    fun threadOpened(accountId: String, threadId: String) {
        viewModelScope.launch { inbox.actOnNotification(InboxAction.READ, accountId, threadId) }
    }
}
