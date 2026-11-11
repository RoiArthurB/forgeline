package fr.arthurbrugiere.forgeline.signin

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.DeviceTokenPoll
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeType
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeAuthApi
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class SignInViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val octocat = ForgeUser("octocat", "The Octocat", null)
    private val auth = FakeForgeAuthApi().apply { users["ghp_valid"] = octocat }
    private val accounts = FakeAccountRepository()

    private val clients = FakeForgeClients(auth = auth)
    private val redirect = FakeRedirect()
    private var now = 1_000L
    private val clock = object : Clock() {
        override fun instant(): Instant = Instant.ofEpochMilli(now)
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
    }

    /**
     * What each address runs, as the servers would say; any other server is a Forgejo, as before GitLab, and nothing
     * answers under a path (a repository's page) unless said.
     */
    private val kinds = mutableMapOf<String, ForgeType?>()
    private val probed = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val probe = ForgeProbe { host ->
        probed += host
        if (host in kinds) kinds[host] else ForgeType.FORGEJO.takeIf { '/' !in host }
    }
    private val hosts = FakeForgeHosts()
    private val redirectPaths = mutableListOf<String>()

    @org.junit.After
    fun forgetServers() = fr.arthurbrugiere.forgeline.core.model.KnownForges.clear()

    private fun viewModel() = SignInViewModel(
        clients, accounts,
        { path ->
            redirectPaths += path
            redirect
        },
        clock, probe, hosts,
    )

    /** The browser's return, scripted: [params] answer the wait unless null, which never returns. */
    private class FakeRedirect : BrowserRedirect {
        override val redirectUri = "http://127.0.0.1:43123/oauth/codeberg"
        var params: ((String) -> Map<String, String>)? = null
        var closed = false

        override suspend fun await(page: String): Map<String, String> =
            params?.invoke(page) ?: kotlinx.coroutines.awaitCancellation()

        override fun close() {
            closed = true
        }
    }

    private fun test(block: suspend kotlinx.coroutines.test.TestScope.() -> Unit) =
        runTest(mainDispatcherRule.testDispatcher) { block() }

    @Test
    fun offers_the_device_flow_only_when_the_forge_supports_it() {
        assertThat(viewModel().state.value.deviceFlowAvailable).isTrue()
        val noDeviceFlow = SignInViewModel(FakeForgeClients(auth = FakeForgeAuthApi(supportsDeviceFlow = false)), accounts, { redirect }, clock, probe, hosts)
        assertThat(noDeviceFlow.state.value.deviceFlowAvailable).isFalse()
    }

    @Test
    fun a_valid_token_signs_in() = test {
        val viewModel = viewModel()

        viewModel.signInWithToken("  ghp_valid \n")
        advanceUntilIdle()

        assertThat(viewModel.state.value.step).isEqualTo(SignInStep.SignedIn)
        assertThat(accounts.activeAccount.first()?.user).isEqualTo(octocat)
        assertThat(accounts.token(accounts.activeAccount.first()!!.id)).isEqualTo("ghp_valid")
    }

    @Test
    fun a_blank_token_is_rejected_without_calling_the_forge() = test {
        val viewModel = viewModel()

        viewModel.signInWithToken("   ")

        assertThat(viewModel.state.value.step).isEqualTo(SignInStep.Failed(SignInError.INVALID_TOKEN))
    }

    @Test
    fun a_rejected_token_reports_invalid_token() = test {
        val viewModel = viewModel()

        viewModel.signInWithToken("ghp_wrong")
        advanceUntilIdle()

        assertThat(viewModel.state.value.step).isEqualTo(SignInStep.Failed(SignInError.INVALID_TOKEN))
        assertThat(accounts.activeAccount.first()).isNull()
    }

    @Test
    fun forge_errors_map_to_user_facing_errors() = test {
        suspend fun errorFor(failure: ForgeError): SignInStep {
            auth.userFailure = failure
            val viewModel = viewModel()
            viewModel.signInWithToken("ghp_valid")
            advanceUntilIdle()
            return viewModel.state.value.step
        }

        assertThat(errorFor(ForgeError.Network)).isEqualTo(SignInStep.Failed(SignInError.NETWORK))
        assertThat(errorFor(ForgeError.RateLimited(null))).isEqualTo(SignInStep.Failed(SignInError.RATE_LIMITED))
        assertThat(errorFor(ForgeError.Http(500, "boom"))).isEqualTo(SignInStep.Failed(SignInError.UNKNOWN))
    }

    @Test
    fun device_flow_shows_the_code_then_signs_in_once_authorized() = test {
        auth.pollResults += ForgeResult.Success(DeviceTokenPoll.Pending)
        auth.pollResults += ForgeResult.Success(DeviceTokenPoll.Authorized("ghp_valid"))
        val viewModel = viewModel()

        viewModel.startDeviceFlow()
        advanceTimeBy(1_000)

        assertThat(viewModel.state.value.step)
            .isEqualTo(SignInStep.AwaitingAuthorization("ABCD-1234", "https://github.com/login/device"))
        assertThat(auth.pollCount).isEqualTo(0)

        advanceUntilIdle()

        assertThat(auth.pollCount).isEqualTo(2)
        assertThat(viewModel.state.value.step).isEqualTo(SignInStep.SignedIn)
        assertThat(accounts.activeAccount.first()?.user).isEqualTo(octocat)
    }

    @Test
    fun device_flow_polls_at_the_requested_interval_and_slows_down_when_asked() = test {
        auth.pollResults += ForgeResult.Success(DeviceTokenPoll.SlowDown(10))
        auth.pollResults += ForgeResult.Success(DeviceTokenPoll.Authorized("ghp_valid"))
        val viewModel = viewModel()
        viewModel.startDeviceFlow()

        advanceTimeBy(5_001)
        assertThat(auth.pollCount).isEqualTo(1)
        advanceTimeBy(9_000)
        assertThat(auth.pollCount).isEqualTo(1)
        advanceTimeBy(1_001)
        assertThat(auth.pollCount).isEqualTo(2)
    }

    @Test
    fun transient_network_errors_while_polling_are_retried() = test {
        auth.pollResults += ForgeResult.Failure(ForgeError.Network)
        auth.pollResults += ForgeResult.Success(DeviceTokenPoll.Authorized("ghp_valid"))
        val viewModel = viewModel()

        viewModel.startDeviceFlow()
        advanceUntilIdle()

        assertThat(viewModel.state.value.step).isEqualTo(SignInStep.SignedIn)
    }

    @Test
    fun device_flow_reports_denial_and_expiry() = test {
        auth.pollResults += ForgeResult.Success(DeviceTokenPoll.Denied)
        val denied = viewModel().apply { startDeviceFlow() }
        advanceUntilIdle()
        assertThat(denied.state.value.step).isEqualTo(SignInStep.Failed(SignInError.DENIED))

        auth.pollResults += ForgeResult.Success(DeviceTokenPoll.Expired)
        val expired = viewModel().apply { startDeviceFlow() }
        advanceUntilIdle()
        assertThat(expired.state.value.step).isEqualTo(SignInStep.Failed(SignInError.EXPIRED))
    }

    @Test
    fun device_flow_gives_up_when_the_code_expires() = test {
        // The fake answers Pending forever once its queue is empty.
        val viewModel = viewModel()

        viewModel.startDeviceFlow()
        advanceUntilIdle()

        assertThat(viewModel.state.value.step).isEqualTo(SignInStep.Failed(SignInError.EXPIRED))
        assertThat(auth.pollCount).isEqualTo(900 / 5)
    }

    @Test
    fun cancelling_stops_polling() = test {
        val viewModel = viewModel()
        viewModel.startDeviceFlow()
        advanceTimeBy(5_001)

        viewModel.cancel()
        advanceUntilIdle()

        assertThat(auth.pollCount).isEqualTo(1)
        assertThat(viewModel.state.value.step).isEqualTo(SignInStep.ChooseMethod)
    }

    @Test
    fun signing_in_starts_at_the_list_of_forges_and_a_pick_opens_its_form() = test {
        val viewModel = viewModel()
        assertThat(viewModel.state.value.choosingForge).isTrue()

        viewModel.selectForge(SignInForge.CODEBERG)

        assertThat(viewModel.state.value.choosingForge).isFalse()
        assertThat(viewModel.state.value.forge).isEqualTo(SignInForge.CODEBERG)
    }

    @Test
    fun going_back_to_the_forges_stops_what_was_under_way() = test {
        val viewModel = viewModel().apply { selectForge(SignInForge.GITHUB) }
        viewModel.startDeviceFlow()
        advanceTimeBy(5_001)

        viewModel.chooseAnotherForge()
        advanceUntilIdle()

        assertThat(auth.pollCount).isEqualTo(1)
        assertThat(viewModel.state.value.choosingForge).isTrue()
        assertThat(viewModel.state.value.step).isEqualTo(SignInStep.ChooseMethod)
    }

    @Test
    fun failing_to_get_a_device_code_is_reported() = test {
        auth.deviceCodeResult = ForgeResult.Failure(ForgeError.Network)
        val viewModel = viewModel()

        viewModel.startDeviceFlow()
        advanceUntilIdle()

        assertThat(viewModel.state.value.step).isEqualTo(SignInStep.Failed(SignInError.NETWORK))
    }

    @Test
    fun dismissing_an_error_returns_to_the_method_choice() = test {
        val viewModel = viewModel()
        viewModel.signInWithToken("")

        viewModel.dismissError()

        assertThat(viewModel.state.value.step).isEqualTo(SignInStep.ChooseMethod)
    }

    private val gitlabAuth = FakeForgeAuthApi(supportsDeviceFlow = false, forge = ForgeInstance.GitLab, supportsBrowserSignIn = true)
        .apply { users["glpat_valid"] = ForgeUser("tanuki", "The Tanuki", null) }

    private fun gitlabViewModel(): SignInViewModel {
        clients.put(ForgeInstance.GitLab, FakeForgeClients(auth = gitlabAuth))
        return viewModel().also { it.selectForge(SignInForge.GITLAB) }
    }

    @Test
    fun gitlab_selection_updates_ui_state() {
        val state = gitlabViewModel().state.value

        assertThat(state.forge).isEqualTo(SignInForge.GITLAB)
        assertThat(state.forgeName).isEqualTo("GitLab")
        assertThat(state.browserSignInAvailable).isTrue()
        assertThat(state.deviceFlowAvailable).isFalse()
    }

    @Test
    fun gitlab_signs_in_with_token() = test {
        val viewModel = gitlabViewModel()

        viewModel.signInWithToken("glpat_valid")
        advanceUntilIdle()

        assertThat(viewModel.state.value.step).isEqualTo(SignInStep.SignedIn)
        val account = accounts.activeAccount.first()!!
        assertThat(account.forge).isEqualTo(ForgeInstance.GitLab)
        assertThat(account.user.login).isEqualTo("tanuki")
        assertThat(accounts.token(account.id)).isEqualTo("glpat_valid")
    }

    private val codebergAuth = FakeForgeAuthApi(supportsDeviceFlow = false, forge = ForgeInstance.Codeberg, supportsBrowserSignIn = true)
        .apply { users["access"] = ForgeUser("alice", null, null) }

    private fun codebergViewModel(): SignInViewModel {
        clients.put(ForgeInstance.Codeberg, FakeForgeClients(auth = codebergAuth))
        return viewModel().also { it.selectForge(SignInForge.CODEBERG) }
    }

    @Test
    fun codeberg_offers_the_browser_instead_of_a_device_code() {
        val state = codebergViewModel().state.value

        assertThat(state.forgeName).isEqualTo("Codeberg")
        assertThat(state.browserSignInAvailable).isTrue()
        assertThat(state.deviceFlowAvailable).isFalse()
    }

    @Test
    fun the_browser_sign_in_exchanges_the_code_with_its_verifier_and_keeps_the_refresh_token() = test {
        val viewModel = codebergViewModel()
        var page = ""
        var authorizationUrl = ""
        // The forge sends the browser back with the sign-in's own state.
        redirect.params = { html ->
            page = html
            authorizationUrl = (viewModel.state.value.step as SignInStep.AwaitingBrowser).authorizationUrl
            mapOf("code" to "code-1", "state" to stateOf(viewModel))
        }

        viewModel.startBrowserSignIn()
        advanceUntilIdle()

        assertThat(viewModel.state.value.step).isEqualTo(SignInStep.SignedIn)
        val account = accounts.activeAccount.first()!!
        assertThat(account.forge).isEqualTo(ForgeInstance.Codeberg)
        assertThat(accounts.token(account.id)).isEqualTo("access")
        assertThat(accounts.refreshTokens[account.id]).isEqualTo("refresh" to now + 3_600_000)
        val (code, verifier, redirectUri) = codebergAuth.exchanges.single().split(' ')
        assertThat(code).isEqualTo("code-1")
        assertThat(redirectUri).isEqualTo(redirect.redirectUri)
        // The verifier kept on the phone is the one whose challenge went to the forge.
        assertThat(fr.arthurbrugiere.forgeline.core.forge.Pkce.of(verifier).challenge)
            .isEqualTo(io.ktor.http.Url(authorizationUrl).parameters["code_challenge"])
        assertThat(page).contains("Back to Forgeline")
        assertThat(redirect.closed).isTrue()
    }

    private val tangledAuth = FakeForgeAuthApi(supportsDeviceFlow = false, forge = ForgeInstance.Tangled, supportsPasswordSignIn = true)
        .apply { users["access"] = ForgeUser("me.bsky.social", null, null) }

    private fun tangledViewModel(): SignInViewModel {
        clients.put(ForgeInstance.Tangled, FakeForgeClients(auth = tangledAuth))
        return viewModel().also { it.selectForge(SignInForge.TANGLED) }
    }

    @Test
    fun tangled_asks_for_a_handle_and_an_app_password() {
        val state = tangledViewModel().state.value

        assertThat(state.forgeName).isEqualTo("Tangled")
        assertThat(state.passwordSignInAvailable).isTrue()
        assertThat(state.deviceFlowAvailable).isFalse()
        assertThat(state.browserSignInAvailable).isFalse()
    }

    @Test
    fun tangled_keeps_the_session_and_its_refresh_token_not_the_password() = test {
        val viewModel = tangledViewModel()

        viewModel.signInWithPassword("  me.bsky.social ", "abcd-efgh-ijkl-mnop ")
        advanceUntilIdle()

        assertThat(viewModel.state.value.step).isEqualTo(SignInStep.SignedIn)
        assertThat(tangledAuth.passwordSignIns.single()).isEqualTo("me.bsky.social abcd-efgh-ijkl-mnop")
        val account = accounts.activeAccount.first()!!
        assertThat(account.forge).isEqualTo(ForgeInstance.Tangled)
        assertThat(account.user.login).isEqualTo("me.bsky.social")
        assertThat(accounts.token(account.id)).isEqualTo("access")
        assertThat(accounts.refreshTokens[account.id]).isEqualTo("refresh" to now + 7_200_000)
    }

    @Test
    fun a_refused_app_password_says_so() = test {
        val viewModel = tangledViewModel()
        tangledAuth.passwordResult = ForgeResult.Failure(ForgeError.Unauthorized)

        viewModel.signInWithPassword("me.bsky.social", "wrong")
        advanceUntilIdle()

        assertThat(viewModel.state.value.step).isEqualTo(SignInStep.Failed(SignInError.INVALID_TOKEN))
        assertThat(accounts.activeAccount.first()).isNull()
    }

    @Test
    fun a_redirect_from_another_sign_in_is_refused() = test {
        val viewModel = codebergViewModel()
        redirect.params = { mapOf("code" to "code-1", "state" to "someone-else") }

        viewModel.startBrowserSignIn()
        advanceUntilIdle()

        assertThat(viewModel.state.value.step).isEqualTo(SignInStep.Failed(SignInError.UNKNOWN))
        assertThat(codebergAuth.exchanges).isEmpty()
        assertThat(accounts.activeAccount.first()).isNull()
    }

    @Test
    fun declining_on_the_forge_says_so() = test {
        val viewModel = codebergViewModel()
        redirect.params = { mapOf("error" to "access_denied", "state" to stateOf(viewModel)) }

        viewModel.startBrowserSignIn()
        advanceUntilIdle()

        assertThat(viewModel.state.value.step).isEqualTo(SignInStep.Failed(SignInError.DENIED))
    }

    @Test
    fun cancelling_the_browser_sign_in_stops_listening() = test {
        val viewModel = codebergViewModel()

        viewModel.startBrowserSignIn()
        runCurrent()
        viewModel.cancel()
        runCurrent()

        assertThat(viewModel.state.value.step).isEqualTo(SignInStep.ChooseMethod)
        assertThat(redirect.closed).isTrue()
    }

    @Test
    fun another_forgejo_server_signs_in_with_a_token_at_its_address() = test {
        val selfHosted = ForgeInstance(ForgeType.FORGEJO, "git.example.org")
        clients.put(selfHosted, FakeForgeClients(auth = FakeForgeAuthApi(supportsDeviceFlow = false, forge = selfHosted).apply { users["tok"] = octocat }))
        val viewModel = viewModel()

        viewModel.selectForge(SignInForge.OTHER)
        viewModel.setHost(" https://Git.Example.org/ ")
        viewModel.signInWithToken("tok")
        advanceUntilIdle()

        assertThat(viewModel.state.value.step).isEqualTo(SignInStep.SignedIn)
        assertThat(accounts.activeAccount.first()?.forge).isEqualTo(selfHosted)
    }

    @Test
    fun a_server_answering_with_a_web_page_is_not_a_forge() = test {
        val website = ForgeInstance(ForgeType.FORGEJO, "example.org")
        clients.put(website, FakeForgeClients(auth = FakeForgeAuthApi(supportsDeviceFlow = false, forge = website).apply { userFailure = ForgeError.Unreadable }))
        val viewModel = viewModel()

        viewModel.selectForge(SignInForge.OTHER)
        viewModel.setHost("example.org")
        viewModel.signInWithToken("tok")
        advanceUntilIdle()

        assertThat(viewModel.state.value.step).isEqualTo(SignInStep.Failed(SignInError.NOT_A_FORGE))
    }

    @Test
    fun without_an_address_there_is_nothing_to_sign_in_to() = test {
        val viewModel = viewModel()

        viewModel.selectForge(SignInForge.OTHER)
        viewModel.signInWithToken("tok")

        assertThat(viewModel.state.value.step).isEqualTo(SignInStep.Failed(SignInError.NOT_A_FORGE))
    }

    @Test
    fun a_server_installed_under_a_path_is_found_there_and_signed_in_to() = test {
        // Nothing answers at the server's root: the forge is under /git.
        kinds["example.org"] = null
        kinds["example.org/git"] = ForgeType.GITEA
        val underPath = ForgeInstance(ForgeType.GITEA, "example.org/git")
        clients.put(underPath, FakeForgeClients(auth = FakeForgeAuthApi(supportsDeviceFlow = false, forge = underPath).apply { users["tok"] = octocat }))
        val viewModel = viewModel().also { it.selectForge(SignInForge.OTHER) }

        viewModel.setHost("https://example.org/git/")
        advanceUntilIdle()

        assertThat(probed).containsExactly("example.org", "example.org/git")
        assertThat(viewModel.state.value.otherType).isEqualTo(ForgeType.GITEA)
        assertThat(viewModel.state.value.address).isEqualTo("example.org/git")

        viewModel.signInWithToken("tok")
        advanceUntilIdle()

        assertThat(viewModel.state.value.step).isEqualTo(SignInStep.SignedIn)
        assertThat(accounts.activeAccount.first()!!.forge).isEqualTo(underPath)
    }

    @Test
    fun a_server_under_a_path_is_found_though_something_answers_at_the_root() = test {
        // Regression: the root was asked first and taken: another forge there, or a gateway answering GitLab's way.
        kinds["example.org"] = ForgeType.GITLAB
        kinds["example.org/git"] = ForgeType.FORGEJO
        val viewModel = viewModel().also { it.selectForge(SignInForge.OTHER) }

        viewModel.setHost("https://example.org/git")
        advanceUntilIdle()

        assertThat(viewModel.state.value.otherType).isEqualTo(ForgeType.FORGEJO)
        assertThat(viewModel.state.value.address).isEqualTo("example.org/git")
    }

    @Test
    fun a_page_s_address_pasted_whole_finds_the_forge_at_its_root() = test {
        val viewModel = viewModel().also { it.selectForge(SignInForge.OTHER) }

        viewModel.setHost("https://git.example.org/alice/tool/src/branch/main")
        advanceUntilIdle()

        assertThat(probed).containsExactly("git.example.org", "git.example.org/alice", "git.example.org/alice/tool")
        assertThat(viewModel.state.value.address).isEqualTo("git.example.org")
    }

    @Test
    fun the_places_a_forge_may_stand_in_an_address_go_from_its_root_down() {
        assertThat(SignInViewModel.addressesIn("https://Example.org/Git/alice/tool?tab=1"))
            .containsExactly("example.org", "example.org/git", "example.org/git/alice").inOrder()
        assertThat(SignInViewModel.addressesIn("codeberg.org")).containsExactly("codeberg.org")
        assertThat(SignInViewModel.addressesIn("localhost/git")).isEmpty()
    }

    @Test
    fun server_addresses_are_read_forgivingly() {
        assertThat(SignInViewModel.normalizedHost("https://Git.Example.org/explore")).isEqualTo("git.example.org")
        assertThat(SignInViewModel.normalizedHost("codeberg.org")).isEqualTo("codeberg.org")
        assertThat(SignInViewModel.normalizedHost("  ")).isNull()
        assertThat(SignInViewModel.normalizedHost("localhost")).isNull()
    }

    private fun stateOf(viewModel: SignInViewModel): String =
        io.ktor.http.Url((viewModel.state.value.step as SignInStep.AwaitingBrowser).authorizationUrl).parameters["state"]!!

    // Another server that turns out to be a GitLab

    private val ownGitLab = ForgeInstance(ForgeType.GITLAB, "gitlab.example.org")
    private val ownGitLabAuth = FakeForgeAuthApi(supportsDeviceFlow = false, forge = ownGitLab, supportsBrowserSignIn = true)
        .apply {
            users["glpat_own"] = ForgeUser("tanuki", null, null)
            // What the browser sign-in's exchange hands back.
            users["access"] = ForgeUser("tanuki", null, null)
        }

    private fun ownGitLabViewModel(): SignInViewModel {
        kinds["gitlab.example.org"] = ForgeType.GITLAB
        clients.put(ownGitLab, FakeForgeClients(auth = ownGitLabAuth))
        return viewModel().also { it.selectForge(SignInForge.OTHER) }
    }

    @Test
    fun another_server_says_what_it_runs_once_its_address_rests() = test {
        val viewModel = ownGitLabViewModel()

        viewModel.setHost("https://gitlab.example.org/")
        assertThat(viewModel.state.value.otherType).isNull()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.otherType).isEqualTo(ForgeType.GITLAB)
        assertThat(state.personalAccessTokenUrl).isEqualTo(ownGitLabAuth.personalAccessTokenUrl)
        // Through the browser only once its application's ID is given.
        assertThat(state.browserSignInAvailable).isFalse()
        assertThat(probed).containsExactly("gitlab.example.org")
    }

    @Test
    fun an_address_still_being_typed_is_not_asked_letter_by_letter() = test {
        val viewModel = ownGitLabViewModel()

        "gitlab.example.org".indices.forEach { viewModel.setHost("gitlab.example.org".take(it + 1)) }
        advanceUntilIdle()

        assertThat(probed).containsExactly("gitlab.example.org")
    }

    @Test
    fun a_token_signs_in_to_a_self_hosted_gitlab_and_the_server_is_remembered_as_one() = test {
        val viewModel = ownGitLabViewModel()
        viewModel.setHost("gitlab.example.org")
        advanceUntilIdle()

        viewModel.signInWithToken("glpat_own")
        advanceUntilIdle()

        assertThat(viewModel.state.value.step).isEqualTo(SignInStep.SignedIn)
        assertThat(accounts.activeAccount.first()!!.forge).isEqualTo(ownGitLab)
        // The rest of the app only carries the host: it must now resolve to a GitLab.
        assertThat(ForgeInstance.of("gitlab.example.org")).isEqualTo(ownGitLab)
        assertThat(hosts.remembered).containsKey(ownGitLab)
    }

    @Test
    fun signing_in_before_the_address_rested_still_asks_the_server_first() = test {
        // Regression guard: typed and submitted at once, the server would be taken for a Forgejo and its token refused.
        val viewModel = ownGitLabViewModel()
        viewModel.setHost("gitlab.example.org")

        viewModel.signInWithToken("glpat_own")
        advanceUntilIdle()

        assertThat(viewModel.state.value.step).isEqualTo(SignInStep.SignedIn)
        assertThat(accounts.activeAccount.first()!!.forge.type).isEqualTo(ForgeType.GITLAB)
    }

    @Test
    fun an_address_where_no_forge_answers_says_so_and_signs_nobody_in() = test {
        kinds["www.example.org"] = null
        val viewModel = viewModel().also { it.selectForge(SignInForge.OTHER) }
        viewModel.setHost("www.example.org")
        advanceUntilIdle()

        viewModel.signInWithToken("anything")
        advanceUntilIdle()

        assertThat(viewModel.state.value.step).isEqualTo(SignInStep.Failed(SignInError.NOT_A_FORGE))
        assertThat(accounts.activeAccount.first()).isNull()
        assertThat(hosts.remembered).isEmpty()
    }

    @Test
    fun a_self_hosted_gitlab_signs_in_through_the_browser_with_its_own_application() = test {
        val viewModel = ownGitLabViewModel()
        viewModel.setHost("gitlab.example.org")
        advanceUntilIdle()

        viewModel.setOauthClientId("  app-id-123 ")
        assertThat(viewModel.state.value.browserSignInAvailable).isTrue()
        redirect.params = { mapOf("code" to "the-code", "state" to stateOf(viewModel)) }
        viewModel.startBrowserSignIn()
        advanceUntilIdle()

        assertThat(viewModel.state.value.step).isEqualTo(SignInStep.SignedIn)
        // The application's ID is kept for the server: renewing the sign-in later needs it.
        assertThat(hosts.oauthClientId("gitlab.example.org")).isEqualTo("app-id-123")
        // GitLab's applications are registered with their own redirect path.
        assertThat(redirectPaths).containsExactly("/oauth/gitlab")
    }

    @Test
    fun codeberg_keeps_the_redirect_its_application_was_registered_with() = test {
        val viewModel = codebergViewModel()
        redirect.params = { mapOf("code" to "the-code", "state" to stateOf(viewModel)) }

        viewModel.startBrowserSignIn()
        advanceUntilIdle()

        assertThat(redirectPaths).containsExactly("/oauth/codeberg")
    }

    @Test
    fun gitea_com_signs_in_through_the_browser_to_its_own_redirect() = test {
        kinds["gitea.com"] = ForgeType.GITEA
        val giteaAuth = FakeForgeAuthApi(supportsDeviceFlow = false, forge = ForgeInstance.Gitea, supportsBrowserSignIn = true)
            .apply { users["access"] = ForgeUser("alice", null, null) }
        clients.put(ForgeInstance.Gitea, FakeForgeClients(auth = giteaAuth))
        val viewModel = viewModel().also { it.selectForge(SignInForge.OTHER) }
        viewModel.setHost("gitea.com")
        advanceUntilIdle()

        assertThat(viewModel.state.value.forgeName).isEqualTo("Gitea")
        assertThat(viewModel.state.value.browserSignInAvailable).isTrue()
        redirect.params = { mapOf("code" to "the-code", "state" to stateOf(viewModel)) }
        viewModel.startBrowserSignIn()
        advanceUntilIdle()

        assertThat(viewModel.state.value.step).isEqualTo(SignInStep.SignedIn)
        assertThat(redirectPaths).containsExactly("/oauth/gitea")
    }

    @Test
    fun gitea_com_is_a_choice_of_its_own_signing_in_through_the_browser() = test {
        // Regression: gitea.com's application was wired but the screen offered no Gitea, only "Forgejo" and an address.
        val giteaAuth = FakeForgeAuthApi(supportsDeviceFlow = false, forge = ForgeInstance.Gitea, supportsBrowserSignIn = true)
            .apply { users["access"] = ForgeUser("alice", null, null) }
        clients.put(ForgeInstance.Gitea, FakeForgeClients(auth = giteaAuth))
        val viewModel = viewModel().also { it.selectForge(SignInForge.GITEA) }
        advanceUntilIdle()

        assertThat(viewModel.state.value.usesHost).isFalse()
        assertThat(viewModel.state.value.forgeName).isEqualTo("Gitea")
        assertThat(viewModel.state.value.browserSignInAvailable).isTrue()
        redirect.params = { mapOf("code" to "the-code", "state" to stateOf(viewModel)) }
        viewModel.startBrowserSignIn()
        advanceUntilIdle()

        assertThat(viewModel.state.value.step).isEqualTo(SignInStep.SignedIn)
        assertThat(redirectPaths).containsExactly("/oauth/gitea")
    }

    @Test
    fun another_server_that_is_a_forgejo_asks_for_no_application() = test {
        val viewModel = viewModel().also { it.selectForge(SignInForge.OTHER) }
        viewModel.setHost("git.example.org")
        advanceUntilIdle()

        assertThat(viewModel.state.value.otherType).isEqualTo(ForgeType.FORGEJO)
        viewModel.setOauthClientId("ignored")
        assertThat(viewModel.state.value.oauthClientId).isEmpty()
        assertThat(viewModel.state.value.browserSignInAvailable).isFalse()
    }

    // A GitLab of one's own, chosen under GitLab

    private fun gitlabOwnViewModel(): SignInViewModel {
        kinds["gitlab.example.org"] = ForgeType.GITLAB
        clients.put(ForgeInstance.GitLab, FakeForgeClients(auth = gitlabAuth))
        clients.put(ownGitLab, FakeForgeClients(auth = ownGitLabAuth))
        return viewModel().also {
            it.selectForge(SignInForge.GITLAB)
            it.selectGitLabServer(own = true)
        }
    }

    @Test
    fun gitlab_is_gitlab_com_until_a_server_of_ones_own_is_chosen() = test {
        clients.put(ForgeInstance.GitLab, FakeForgeClients(auth = gitlabAuth))
        val viewModel = viewModel().also { it.selectForge(SignInForge.GITLAB) }

        assertThat(viewModel.state.value.gitlabOwnServer).isFalse()
        assertThat(viewModel.state.value.usesHost).isFalse()
        assertThat(viewModel.state.value.forgeName).isEqualTo("GitLab")

        viewModel.selectGitLabServer(own = true)

        val state = viewModel.state.value
        assertThat(state.forge).isEqualTo(SignInForge.GITLAB)
        assertThat(state.usesHost).isTrue()
        // No address yet: nothing to name, and nothing to sign in to through the browser.
        assertThat(state.forgeName).isEmpty()
        assertThat(state.browserSignInAvailable).isFalse()

        viewModel.selectGitLabServer(own = false)

        assertThat(viewModel.state.value.usesHost).isFalse()
        assertThat(viewModel.state.value.browserSignInAvailable).isTrue()
    }

    @Test
    fun a_gitlab_of_ones_own_signs_in_with_a_token_and_is_remembered() = test {
        val viewModel = gitlabOwnViewModel()
        viewModel.setHost("gitlab.example.org")
        advanceUntilIdle()
        assertThat(viewModel.state.value.otherType).isEqualTo(ForgeType.GITLAB)
        assertThat(viewModel.state.value.forge).isEqualTo(SignInForge.GITLAB)

        viewModel.signInWithToken("glpat_own")
        advanceUntilIdle()

        assertThat(viewModel.state.value.step).isEqualTo(SignInStep.SignedIn)
        assertThat(accounts.activeAccount.first()!!.forge).isEqualTo(ownGitLab)
        assertThat(ForgeInstance.of("gitlab.example.org")).isEqualTo(ownGitLab)
    }

    @Test
    fun a_gitlab_of_ones_own_takes_its_application_before_the_server_has_answered() = test {
        // Chosen under GitLab, it is one until it says otherwise: its application can be typed right away.
        val viewModel = gitlabOwnViewModel()
        viewModel.setHost("gitlab.example.org")

        viewModel.setOauthClientId("app-id-123")

        assertThat(viewModel.state.value.oauthClientId).isEqualTo("app-id-123")
        assertThat(viewModel.state.value.browserSignInAvailable).isTrue()
    }

    @Test
    fun an_address_under_gitlab_that_is_a_forgejo_signs_in_as_one() = test {
        // Nobody is turned away for having picked the wrong tab: the server says what it is.
        val forgejo = ForgeInstance(ForgeType.FORGEJO, "git.example.org")
        clients.put(forgejo, FakeForgeClients(auth = FakeForgeAuthApi(supportsDeviceFlow = false, forge = forgejo).apply { users["tok"] = ForgeUser("alice", null, null) }))
        val viewModel = gitlabOwnViewModel()
        viewModel.setHost("git.example.org")
        advanceUntilIdle()

        viewModel.signInWithToken("tok")
        advanceUntilIdle()

        assertThat(viewModel.state.value.step).isEqualTo(SignInStep.SignedIn)
        assertThat(accounts.activeAccount.first()!!.forge).isEqualTo(forgejo)
    }

    @Test
    fun changing_forge_drops_the_address_typed_for_another() = test {
        val viewModel = gitlabOwnViewModel()
        viewModel.setHost("gitlab.example.org")
        advanceUntilIdle()

        viewModel.selectForge(SignInForge.OTHER)

        assertThat(viewModel.state.value.host).isEmpty()
        assertThat(viewModel.state.value.otherType).isNull()
    }
}
