package fr.arthurbrugiere.forgeline.session

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class SessionViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val accounts = FakeAccountRepository()

    @Test
    fun signed_out_without_an_account() {
        assertThat(SessionViewModel(accounts).session.value).isEqualTo(SessionState.SignedOut)
    }

    @Test
    fun follows_the_active_account() = runTest {
        val viewModel = SessionViewModel(accounts)

        val account = accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "t")

        assertThat(viewModel.session.value).isEqualTo(SessionState.SignedIn(account))
    }

    @Test
    fun sign_out_forgets_the_active_account() = runTest {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "t")
        val viewModel = SessionViewModel(accounts)

        viewModel.signOut()

        assertThat(viewModel.session.value).isEqualTo(SessionState.SignedOut)
        assertThat(accounts.accounts.first()).isEmpty()
    }
}
