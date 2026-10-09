package fr.arthurbrugiere.forgeline.work

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.work.Work
import fr.arthurbrugiere.forgeline.core.data.work.WorkRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WorkUiState(
    val work: Work? = null,
    val isLoading: Boolean = true,
    val error: ForgeError? = null,
)

@HiltViewModel
class WorkViewModel @Inject constructor(private val repository: WorkRepository) : ViewModel() {
    private val _state = MutableStateFlow(WorkUiState())
    val state: StateFlow<WorkUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    private var reading: Job? = null

    /**
     * Asks every forge again. What was read stays on screen until a forge answers, each forge's work shows as it comes
     * in, and what was read is kept when no forge can be asked.
     */
    fun refresh() {
        reading?.cancel()
        _state.update { it.copy(isLoading = true, error = null) }
        reading = viewModelScope.launch {
            repository.stream().collect { result ->
                _state.update {
                    when (result) {
                        is ForgeResult.Success -> it.copy(work = result.value)
                        is ForgeResult.Failure -> it.copy(error = result.error)
                    }
                }
            }
            _state.update { it.copy(isLoading = false) }
        }
    }

    fun errorShown() = _state.update { it.copy(error = null) }
}
