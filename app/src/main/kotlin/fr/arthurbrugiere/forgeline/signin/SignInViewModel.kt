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

/**
 * Where to sign in: GitHub, GitLab (gitlab.com or a server of one's own), Codeberg, gitea.com, SourceHut, Tangled, or a Forgejo
 * (or Gitea) server of one's own.
 * A server of one's own is given by its address, and says itself what it runs.
 */
enum class SignInForge {
    GITHUB,
    GITLAB,
    CODEBERG,
    GITEA,
    SOURCEHUT,
    TANGLED,
    OTHER;

    /** Whose logo the choice shows. */
    val icon: ForgeInstance
        get() = when (this) {
            GITHUB -> ForgeInstance.GitHub
            GITLAB -> ForgeInstance.GitLab
            CODEBERG -> ForgeInstance.Codeberg
            GITEA -> ForgeInstance.Gitea
            SOURCEHUT -> ForgeInstance.SourceHut
            TANGLED -> ForgeInstance.Tangled
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
    /** Under GitLab: a server of one's own rather than gitlab.com. */
    val gitlabOwnServer: Boolean = false,
    /** The address typed for a server of one's own. */
    val host: String = "",
    /** What the server at [host] runs, once it answered: null while unknown, and when nothing known answers there. */
    val otherType: ForgeType? = null,
    /**
     * Where that server answered, once it did: the address typed, down to the path a server installed under one keeps
     * (`example.org/git`); null until then, when the address typed is taken from its server's root.
     */
    val address: String? = null,
    /** The ID typed for the OAuth application of a self-hosted GitLab, which is what lets it sign in through the browser. */
    val oauthClientId: String = "",
    val browserSignInAvailable: Boolean = false,
    /** Whether the forge signs in with an account name and an app password (Tangled) instead of a token. */
    val passwordSignInAvailable: Boolean = false,
    /** The forge's name, for "Sign in with ...". */
    val forgeName: String = ForgeInstance.GitHub.displayName,
    /** Whether the forges are listed to pick one, which is where signing in starts: every forge in sight, at any text size. */
    val choosingForge: Boolean = false,
) {
    /** Whether the server is given by its address: a Forgejo of one's own, or a GitLab of one's own. */
    val usesHost: Boolean get() = forge == SignInForge.OTHER || (forge == SignInForge.GITLAB && gitlabOwnServer)
}

@HiltViewModel
class SignInViewModel @Inject constructor(
    private val clients: ForgeClients,
    private val accounts: AccountRepository,
    private val redirects: BrowserRedirects,
    private val clock: Clock,
    private val probe: ForgeProbe,
    private val hosts: ForgeHosts,
) : ViewModel() {

    private val _state = MutableStateFlow(stateFor(SignInForge.GITHUB, "").copy(choosingForge = true))
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var probing: Job? = null

    fun selectForge(forge: SignInForge) {
        job?.cancel()
        // The address typed under one choice doesn't follow to another: a GitLab's isn't a Forgejo's.
        _state.value = stateFor(forge, host = "", own = false)
        probing?.cancel()
    }

    /** Back to the list of forges, from one's form: what was under way there stops. */
    fun chooseAnotherForge() {
        job?.cancel()
        probing?.cancel()
        _state.update { it.copy(choosingForge = true, step = SignInStep.ChooseMethod) }
    }

    /** Under GitLab: gitlab.com, or a server of one's own, which then asks for its address. */
    fun selectGitLabServer(own: Boolean) {
        job?.cancel()
        _state.value = stateFor(SignInForge.GITLAB, _state.value.host, own = own)
        if (own) setHost(_state.value.host)
    }

    /** The address of one's own server. What it runs is asked once the typing pauses, so the screen can say what it needs. */
    fun setHost(host: String) {
        val current = _state.value
        _state.value = stateFor(current.forge, host, type = null, clientId = "", own = current.gitlabOwnServer)
        probing?.cancel()
        val address = normalizedHost(host) ?: return
        probing = viewModelScope.launch {
            delay(PROBE_AFTER_MILLIS)
            val (answered, type) = answeringServer(host) ?: return@launch
            _state.update { state ->
                if (state.usesHost && normalizedHost(state.host) == address) {
                    stateFor(state.forge, state.host, type, hosts.oauthClientId(answered), state.gitlabOwnServer, answered).copy(step = state.step)
                } else {
                    state
                }
            }
        }
    }

    /** The ID of the OAuth application created on a self-hosted GitLab: with one, signing in goes through the browser. */
    fun setOauthClientId(id: String) {
        _state.update { stateFor(it.forge, it.host, it.otherType, id, it.gitlabOwnServer, it.address).copy(step = it.step) }
    }

    /**
     * Makes sure another server's kind is known before signing in to it: asked now if the typing didn't pause long
     * enough. False when nothing known answers at the address.
     */
    private suspend fun otherServerKnown(): Boolean {
        val state = _state.value
        if (!state.usesHost || state.otherType != null) return true
        probing?.cancel()
        val (address, type) = answeringServer(state.host) ?: return false
        _state.update { stateFor(it.forge, it.host, type, it.oauthClientId.ifEmpty { hosts.oauthClientId(address) }, it.gitlabOwnServer, address).copy(step = it.step) }
        return true
    }

    /**
     * The first address under [typed] where a forge answers, and what it runs: its server's root first, then each path
     * typed after it, for a server installed under a path (`example.org/git`). A page's address pasted whole (a
     * repository's) still finds the forge at its root, asked first.
     */
    private suspend fun answeringServer(typed: String): Pair<String, ForgeType>? {
        for (address in addressesIn(typed)) probe.typeOf(address)?.let { return address to it }
        return null
    }

    fun signInWithToken(token: String) {
        val trimmed = token.trim()
        if (trimmed.isEmpty() || auth() == null) {
            show(SignInStep.Failed(if (auth() == null) SignInError.NOT_A_FORGE else SignInError.INVALID_TOKEN))
            return
        }
        launchExclusive {
            show(SignInStep.Verifying)
            if (!otherServerKnown()) return@launchExclusive show(SignInStep.Failed(SignInError.NOT_A_FORGE))
            completeSignIn(auth() ?: return@launchExclusive show(SignInStep.Failed(SignInError.NOT_A_FORGE)), trimmed)
        }
    }

    /** Tangled: the account's handle and an app password, which open a session renewed on its own afterwards. */
    fun signInWithPassword(identifier: String, password: String) {
        val auth = auth()?.takeIf { it.supportsPasswordSignIn } ?: return
        val name = identifier.trim()
        if (name.isEmpty() || password.isBlank()) return show(SignInStep.Failed(SignInError.INVALID_TOKEN))
        launchExclusive {
            show(SignInStep.Verifying)
            when (val tokens = auth.signInWithPassword(name, password.trim())) {
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
     * "Sign in with Codeberg" or GitLab: opens the forge's approval page, which sends the browser back to a loopback
     * address on this phone with a code, exchanged (with the PKCE verifier) for a token.
     */
    fun startBrowserSignIn() {
        // A self-hosted server's application is the one just typed: its client must know it before it is asked anything.
        rememberOtherServer()
        val auth = auth()?.takeIf { it.supportsBrowserSignIn } ?: return
        launchExclusive {
            redirects.open(LoopbackRedirects.pathFor(auth.forge.type)).use { redirect ->
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

    private fun forgeFor(choice: SignInForge, host: String, type: ForgeType? = null, own: Boolean = false, address: String? = null): ForgeInstance? = when (choice) {
        SignInForge.GITHUB -> ForgeInstance.GitHub
        // One's own GitLab is taken for one until it says otherwise: the address typed there may still be a Forgejo's.
        SignInForge.GITLAB -> if (own) (address ?: normalizedHost(host))?.let { ForgeInstance(type ?: ForgeType.GITLAB, it) } else ForgeInstance.GitLab
        SignInForge.CODEBERG -> ForgeInstance.Codeberg
        SignInForge.GITEA -> ForgeInstance.Gitea
        SignInForge.SOURCEHUT -> ForgeInstance.SourceHut
        SignInForge.TANGLED -> ForgeInstance.Tangled
        // Until the server says what it runs, it is taken for a Forgejo: that is what this choice is for.
        SignInForge.OTHER -> (address ?: normalizedHost(host))?.let { ForgeInstance(type ?: ForgeType.FORGEJO, it) }
    }

    private fun auth(): ForgeAuthApi? = _state.value.let { forgeFor(it.forge, it.host, it.otherType, it.gitlabOwnServer, it.address) }?.let(clients::auth)

    /** Remembers what the other server runs and its application's ID, so the rest of the app takes the host for what it is. */
    private fun rememberOtherServer() {
        val state = _state.value
        if (!state.usesHost) return
        val forge = forgeFor(state.forge, state.host, state.otherType, state.gitlabOwnServer, state.address) ?: return
        hosts.remember(forge, state.oauthClientId)
    }

    private fun stateFor(choice: SignInForge, host: String, type: ForgeType? = null, clientId: String = "", own: Boolean = false, address: String? = null): SignInUiState {
        val ownGitLab = own && choice == SignInForge.GITLAB
        val usesHost = choice == SignInForge.OTHER || ownGitLab
        val other = type.takeIf { usesHost }
        // What the server is taken for, for what the screen asks: what it said, else what the choice is for.
        val kind = other ?: if (ownGitLab) ForgeType.GITLAB else null
        val answered = address.takeIf { usesHost && other != null }
        val auth = forgeFor(choice, host, other, ownGitLab, answered)?.let(clients::auth)
        return SignInUiState(
            deviceFlowAvailable = auth?.supportsDeviceFlow == true,
            personalAccessTokenUrl = auth?.personalAccessTokenUrl,
            forge = choice,
            gitlabOwnServer = ownGitLab,
            host = if (usesHost) host else "",
            otherType = other,
            address = answered,
            oauthClientId = if (usesHost && kind == ForgeType.GITLAB) clientId else "",
            // A self-hosted GitLab signs in through the browser once its application's ID is typed.
            browserSignInAvailable = if (usesHost && kind == ForgeType.GITLAB) clientId.isNotBlank() else auth?.supportsBrowserSignIn == true,
            passwordSignInAvailable = auth?.supportsPasswordSignIn == true,
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
                rememberOtherServer()
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
                // A server that isn't a forge answers its API paths with a 404.
                is ForgeError.Http -> if (error.status == 404 && _state.value.usesHost) SignInError.NOT_A_FORGE else SignInError.UNKNOWN
                // A server that isn't a forge can also answer them with a web page.
                ForgeError.Unreadable -> if (_state.value.usesHost) SignInError.NOT_A_FORGE else SignInError.UNKNOWN
                ForgeError.Unsupported, ForgeError.Conflict -> SignInError.UNKNOWN
            },
        ),
    )

    private fun show(step: SignInStep) = _state.update { it.copy(step = step) }

    private fun nonce(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also(SecureRandom()::nextBytes))

    companion object {
        val BROWSER_TIMEOUT = 10.minutes

        /** How long the address must rest before the server is asked what it runs. */
        const val PROBE_AFTER_MILLIS = 600L

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

        /** How deep under its server a forge is looked for: `example.org/a/b` at most. */
        private const val DEEPEST_PATH = 2

        /**
         * Where a forge may stand in the address typed: its server, then the server and each path segment after it,
         * up to [DEEPEST_PATH] of them. Empty for an address that isn't one.
         */
        fun addressesIn(input: String): List<String> {
            val server = normalizedHost(input) ?: return emptyList()
            val path = input.trim().removePrefix("https://").removePrefix("http://").substringAfter('/', "")
                .substringBefore('?').substringBefore('#').lowercase()
                .split('/').filter { it.isNotEmpty() && ' ' !in it }.take(DEEPEST_PATH)
            return (0..path.size).map { depth -> (listOf(server) + path.take(depth)).joinToString("/") }
        }
    }
}
