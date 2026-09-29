package fr.arthurbrugiere.forgeline.signin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.forge.DeviceCode
import fr.arthurbrugiere.forgeline.core.forge.DeviceTokenPoll
import fr.arthurbrugiere.forgeline.core.forge.ForgeAuthApi
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SignInError { NETWORK, INVALID_TOKEN, RATE_LIMITED, DENIED, EXPIRED, UNKNOWN }

sealed interface SignInStep {
    data object ChooseMethod : SignInStep

    data object Verifying : SignInStep

    data class AwaitingAuthorization(val userCode: String, val verificationUri: String) : SignInStep

    data class Failed(val error: SignInError) : SignInStep

    data object SignedIn : SignInStep
}

data class SignInUiState(
    val deviceFlowAvailable: Boolean,
    val personalAccessTokenUrl: String,
    val step: SignInStep = SignInStep.ChooseMethod,
)

@HiltViewModel
class SignInViewModel @Inject constructor(
    private val auth: ForgeAuthApi,
    private val accounts: AccountRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(
        SignInUiState(deviceFlowAvailable = auth.supportsDeviceFlow, personalAccessTokenUrl = auth.personalAccessTokenUrl),
    )
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    private var job: Job? = null

    fun signInWithToken(token: String) {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) {
            show(SignInStep.Failed(SignInError.INVALID_TOKEN))
            return
        }
        launchExclusive {
            show(SignInStep.Verifying)
            completeSignIn(trimmed)
        }
    }

    fun startDeviceFlow() = launchExclusive {
        show(SignInStep.Verifying)
        when (val result = auth.requestDeviceCode()) {
            is ForgeResult.Failure -> fail(result.error)
            is ForgeResult.Success -> awaitAuthorization(result.value)
        }
    }

    fun cancel() {
        job?.cancel()
        show(SignInStep.ChooseMethod)
    }

    fun dismissError() = show(SignInStep.ChooseMethod)

    private suspend fun awaitAuthorization(code: DeviceCode) {
        show(SignInStep.AwaitingAuthorization(code.userCode, code.verificationUri))
        var intervalSeconds = code.intervalSeconds
        var remainingSeconds = code.expiresInSeconds
        while (remainingSeconds > 0) {
            delay(intervalSeconds * 1_000L)
            remainingSeconds -= intervalSeconds
            when (val result = auth.pollDeviceToken(code.deviceCode)) {
                is ForgeResult.Failure -> if (result.error != ForgeError.Network) return fail(result.error)
                is ForgeResult.Success -> when (val poll = result.value) {
                    DeviceTokenPoll.Pending -> Unit
                    is DeviceTokenPoll.SlowDown -> intervalSeconds = poll.intervalSeconds
                    DeviceTokenPoll.Expired -> return show(SignInStep.Failed(SignInError.EXPIRED))
                    DeviceTokenPoll.Denied -> return show(SignInStep.Failed(SignInError.DENIED))
                    is DeviceTokenPoll.Authorized -> {
                        show(SignInStep.Verifying)
                        return completeSignIn(poll.token)
                    }
                }
            }
        }
        show(SignInStep.Failed(SignInError.EXPIRED))
    }

    private suspend fun completeSignIn(token: String) {
        when (val result = auth.fetchAuthenticatedUser(token)) {
            is ForgeResult.Failure -> fail(result.error)
            is ForgeResult.Success -> {
                accounts.signIn(auth.forge, result.value, token)
                show(SignInStep.SignedIn)
            }
        }
    }

    private fun launchExclusive(block: suspend () -> Unit) {
        job?.cancel()
        job = viewModelScope.launch { block() }
    }

    private fun fail(error: ForgeError) = show(
        SignInStep.Failed(
            when (error) {
                ForgeError.Network -> SignInError.NETWORK
                ForgeError.Unauthorized -> SignInError.INVALID_TOKEN
                is ForgeError.RateLimited -> SignInError.RATE_LIMITED
                is ForgeError.Http, ForgeError.Unsupported -> SignInError.UNKNOWN
            },
        ),
    )

    private fun show(step: SignInStep) = _state.update { it.copy(step = step) }
}
