package fr.arthurbrugiere.forgeline.signin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.forge.DeviceCode
import fr.arthurbrugiere.forgeline.core.forge.DeviceTokenPoll
import fr.arthurbrugiere.forgeline.core.forge.ForgeAuthApi
import fr.arthurbrugiere.forgeline.core.forge.ForgeClients
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.Pkce
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes

enum class SignInError { NETWORK, INVALID_TOKEN, RATE_LIMITED, DENIED, EXPIRED, NOT_A_FORGE, UNKNOWN }

/** Where to sign in: GitHub, Codeberg, or another Forgejo server by its address. */
enum class SignInForge {
    GITHUB,
    CODEBERG,
    OTHER;

    /** Whose logo the choice shows; any other server runs Forgejo. */
    val icon: ForgeInstance
        get() = when (this) {
            GITHUB -> ForgeInstance.GitHub
            CODEBERG -> ForgeInstance.Codeberg
            OTHER -> ForgeInstance(ForgeType.FORGEJO, "")
        }
}

sealed interface SignInStep {
    data object ChooseMethod : SignInStep

    data object Verifying : SignInStep

    data class AwaitingAuthorization(val userCode: String, val verificationUri: String) : SignInStep

    /** The forge's approval page is open in the browser; it comes back to the app when approved. */
    data class AwaitingBrowser(val authorizationUrl: String) : SignInStep

    data class Failed(val error: SignInError) : SignInStep

    data object SignedIn : SignInStep
}

data class SignInUiState(
    val deviceFlowAvailable: Boolean,
    val personalAccessTokenUrl: String?,
    val step: SignInStep = SignInStep.ChooseMethod,
    val forge: SignInForge = SignInForge.GITHUB,
    /** The address typed for another Forgejo server. */
    val host: String = "",
    val browserSignInAvailable: Boolean = false,
    /** The forge's name, for "Sign in with ...". */
    val forgeName: String = ForgeInstance.GitHub.displayName,
)

@HiltViewModel
class SignInViewModel @Inject constructor(
    private val clients: ForgeClients,
    private val accounts: AccountRepository,
    private val redirects: BrowserRedirects,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(stateFor(SignInForge.GITHUB, ""))
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    private var job: Job? = null

    fun selectForge(forge: SignInForge) {
        job?.cancel()
        _state.value = stateFor(forge, _state.value.host)
    }

    fun setHost(host: String) {
        _state.value = stateFor(SignInForge.OTHER, host)
    }

    fun signInWithToken(token: String) {
        val trimmed = token.trim()
        val auth = auth()
        if (trimmed.isEmpty() || auth == null) {
            show(SignInStep.Failed(if (auth == null) SignInError.NOT_A_FORGE else SignInError.INVALID_TOKEN))
            return
        }
        launchExclusive {
            show(SignInStep.Verifying)
            completeSignIn(auth, trimmed)
        }
    }

    fun startDeviceFlow() {
        val auth = auth() ?: return
        launchExclusive {
            show(SignInStep.Verifying)
            when (val result = auth.requestDeviceCode()) {
                is ForgeResult.Failure -> fail(result.error)
                is ForgeResult.Success -> awaitAuthorization(auth, result.value)
            }
        }
    }

    /**
     * "Sign in with Codeberg": opens the forge's approval page, which sends the browser back to a loopback address on
     * this phone with a code, exchanged (with the PKCE verifier) for a token.
     */
    fun startBrowserSignIn() {
        val auth = auth()?.takeIf { it.supportsBrowserSignIn } ?: return
        launchExclusive {
            redirects.open().use { redirect ->
                val pkce = Pkce.generate()
                val expected = nonce()
                show(SignInStep.AwaitingBrowser(auth.authorizationUrl(redirect.redirectUri, expected, pkce.challenge)))
                val params = withTimeoutOrNull(BROWSER_TIMEOUT) { redirect.await(RETURN_PAGE) }
                    ?: return@launchExclusive show(SignInStep.Failed(SignInError.EXPIRED))
                val code = params["code"]
                when {
                    // A redirect with another state didn't come from this sign-in.
                    params["state"] != expected -> show(SignInStep.Failed(SignInError.UNKNOWN))
                    params["error"] == "access_denied" -> show(SignInStep.Failed(SignInError.DENIED))
                    code == null -> show(SignInStep.Failed(SignInError.UNKNOWN))
                    else -> {
                        show(SignInStep.Verifying)
                        when (val tokens = auth.exchangeCode(code, redirect.redirectUri, pkce.verifier)) {
                            is ForgeResult.Failure -> fail(tokens.error)
                            is ForgeResult.Success -> completeSignIn(
                                auth,
                                tokens.value.accessToken,
                                tokens.value.refreshToken,
                                tokens.value.expiresInSeconds?.let { clock.millis() + it * 1_000 },
                            )
                        }
                    }
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        show(SignInStep.ChooseMethod)
    }

    fun dismissError() = show(SignInStep.ChooseMethod)

    private fun forgeFor(choice: SignInForge, host: String): ForgeInstance? = when (choice) {
        SignInForge.GITHUB -> ForgeInstance.GitHub
        SignInForge.CODEBERG -> ForgeInstance.Codeberg
        SignInForge.OTHER -> normalizedHost(host)?.let { ForgeInstance(ForgeType.FORGEJO, it) }
    }

    private fun auth(): ForgeAuthApi? = forgeFor(_state.value.forge, _state.value.host)?.let(clients::auth)

    private fun stateFor(choice: SignInForge, host: String): SignInUiState {
        val auth = forgeFor(choice, host)?.let(clients::auth)
        return SignInUiState(
            deviceFlowAvailable = auth?.supportsDeviceFlow == true,
            personalAccessTokenUrl = auth?.personalAccessTokenUrl,
            forge = choice,
            host = host,
            browserSignInAvailable = auth?.supportsBrowserSignIn == true,
            forgeName = auth?.forge?.displayName ?: host,
        )
    }

    private suspend fun awaitAuthorization(auth: ForgeAuthApi, code: DeviceCode) {
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
                        return completeSignIn(auth, poll.token)
                    }
                }
            }
        }
        show(SignInStep.Failed(SignInError.EXPIRED))
    }

    private suspend fun completeSignIn(auth: ForgeAuthApi, token: String, refreshToken: String? = null, expiresAtMillis: Long? = null) {
        when (val result = auth.fetchAuthenticatedUser(token)) {
            is ForgeResult.Failure -> fail(result.error)
            is ForgeResult.Success -> {
                accounts.signIn(auth.forge, result.value, token, refreshToken, expiresAtMillis)
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
                // A server that isn't a Forgejo answers its API paths with a 404.
                is ForgeError.Http -> if (error.status == 404 && _state.value.forge == SignInForge.OTHER) SignInError.NOT_A_FORGE else SignInError.UNKNOWN
                // A server that isn't a Forgejo can also answer them with a web page.
                ForgeError.Unreadable -> if (_state.value.forge == SignInForge.OTHER) SignInError.NOT_A_FORGE else SignInError.UNKNOWN
                ForgeError.Unsupported -> SignInError.UNKNOWN
            },
        ),
    )

    private fun show(step: SignInStep) = _state.update { it.copy(step = step) }

    private fun nonce(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also(SecureRandom()::nextBytes))

    companion object {
        val BROWSER_TIMEOUT = 10.minutes

        /**
         * What the browser shows once the forge sent it back: the way home. The intent link brings Forgeline to the front
         * when tapped, in browsers that support Android intent links.
         */
        const val RETURN_PAGE = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">""" +
            """<title>Forgeline</title></head><body style="font-family:sans-serif;margin:3em 1.5em;text-align:center">""" +
            """<h1>Signed in</h1><p>You can go back to Forgeline.</p>""" +
            """<p><a href="intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;package=fr.arthurbrugiere.forgeline;end">""" +
            """Back to Forgeline</a></p></body></html>"""

        /** "https://git.example.org/" and "git.example.org" are the same server; blank or malformed is none. */
        fun normalizedHost(input: String): String? {
            val host = input.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').lowercase()
            return host.takeIf { it.isNotEmpty() && '.' in it && ' ' !in it }
        }
    }
}
