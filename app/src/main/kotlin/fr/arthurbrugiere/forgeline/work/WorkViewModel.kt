package fr.arthurbrugiere.forgeline.work

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.work.Work
import fr.arthurbrugiere.forgeline.core.data.work.WorkRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
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

    /** Asks every forge again. What was read stays on screen meanwhile, and when no forge can be asked. */
    fun refresh() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val result = repository.load()
            _state.update {
                when (result) {
                    is ForgeResult.Success -> it.copy(work = result.value, isLoading = false)
                    is ForgeResult.Failure -> it.copy(isLoading = false, error = result.error)
                }
            }
        }
    }

    fun errorShown() = _state.update { it.copy(error = null) }
}
