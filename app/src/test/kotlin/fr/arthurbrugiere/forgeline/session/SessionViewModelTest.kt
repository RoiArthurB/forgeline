package fr.arthurbrugiere.forgeline.session

import fr.arthurbrugiere.forgeline.core.model.Account
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.account.SignedOutData
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
    private val forgotten = mutableListOf<Account>()
    private val signedOutData = SignedOutData { forgotten += it }

    @Test
    fun signed_out_without_an_account() {
        assertThat(SessionViewModel(accounts, signedOutData).session.value).isEqualTo(SessionState.SignedOut)
    }

    @Test
    fun follows_the_active_account() = runTest {
        val viewModel = SessionViewModel(accounts, signedOutData)

        val account = accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "t")

        assertThat(viewModel.session.value).isEqualTo(SessionState.SignedIn(account))
    }

    @Test
    fun every_signed_in_account_is_known() = runTest {
        val viewModel = SessionViewModel(accounts, signedOutData)
        val github = accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "t")
        val codeberg = accounts.signIn(ForgeInstance.Codeberg, ForgeUser("octocat", null, null), "c")

        assertThat(viewModel.session.value).isEqualTo(SessionState.SignedIn(codeberg, listOf(github, codeberg)))

        viewModel.signOut(codeberg)

        assertThat(viewModel.session.value).isEqualTo(SessionState.SignedIn(github, listOf(github)))
    }

    @Test
    fun sign_out_forgets_the_active_account() = runTest {
        val account = accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "t")
        val viewModel = SessionViewModel(accounts, signedOutData)

        viewModel.signOut(account)

        assertThat(viewModel.session.value).isEqualTo(SessionState.SignedOut)
        assertThat(accounts.accounts.first()).isEmpty()
    }

    @Test
    fun sign_out_deletes_what_was_kept_for_the_account() = runTest {
        // Regression: signing out only removed the account, and everything read with it stayed on the phone.
        val github = accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "t")
        val codeberg = accounts.signIn(ForgeInstance.Codeberg, ForgeUser("octocat", null, null), "c")
        val viewModel = SessionViewModel(accounts, signedOutData)

        viewModel.signOut(codeberg)

        assertThat(forgotten).containsExactly(codeberg)
        assertThat(accounts.accounts.first()).containsExactly(github)
    }

    @Test
    fun being_signed_in_counts_on_the_accounts_own_forge_only() {
        // A GitHub account can't star or run CI on Codeberg: screens there ask to sign in to Codeberg instead.
        val gitHub = Account(Account.idFor(ForgeInstance.GitHub, "me"), ForgeInstance.GitHub, ForgeUser("me", null, null))
        val codeberg = Account(Account.idFor(ForgeInstance.Codeberg, "me"), ForgeInstance.Codeberg, ForgeUser("me", null, null))

        assertThat(SessionState.SignedOut.signedInOn(ForgeInstance.GitHub)).isFalse()
        assertThat(SessionState.SignedIn(gitHub).signedInOn(ForgeInstance.GitHub)).isTrue()
        assertThat(SessionState.SignedIn(gitHub).signedInOn(ForgeInstance.Codeberg)).isFalse()
        assertThat(SessionState.SignedIn(gitHub, listOf(gitHub, codeberg)).signedInOn(ForgeInstance.Codeberg)).isTrue()
    }
}
