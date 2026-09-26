package fr.arthurbrugiere.forgeline.signin

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.DeviceTokenPoll
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeAuthApi
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SignInViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val octocat = ForgeUser("octocat", "The Octocat", null)
    private val auth = FakeForgeAuthApi().apply { users["ghp_valid"] = octocat }
    private val accounts = FakeAccountRepository()

    private fun viewModel() = SignInViewModel(auth, accounts)

    private fun test(block: suspend kotlinx.coroutines.test.TestScope.() -> Unit) =
        runTest(mainDispatcherRule.testDispatcher) { block() }

    @Test
    fun offers_the_device_flow_only_when_the_forge_supports_it() {
        assertThat(viewModel().state.value.deviceFlowAvailable).isTrue()
        assertThat(SignInViewModel(FakeForgeAuthApi(supportsDeviceFlow = false), accounts).state.value.deviceFlowAvailable)
            .isFalse()
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
}
