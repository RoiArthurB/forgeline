package fr.arthurbrugiere.forgeline.session

import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeAuthApi
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
    private val auth = FakeForgeAuthApi()
    private val clients = FakeForgeClients(auth = auth)
    private val forgotten = mutableListOf<Account>()
    private val signedOutData = SignedOutData { forgotten += it }

    @Test
    fun signed_out_without_an_account() {
        assertThat(SessionViewModel(accounts, signedOutData, clients).session.value).isEqualTo(SessionState.SignedOut)
    }

    @Test
    fun follows_the_active_account() = runTest {
        val viewModel = SessionViewModel(accounts, signedOutData, clients)

        val account = accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "t")

        assertThat(viewModel.session.value).isEqualTo(SessionState.SignedIn(account))
    }

    @Test
    fun every_signed_in_account_is_known() = runTest {
        val viewModel = SessionViewModel(accounts, signedOutData, clients)
        val github = accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "t")
        val codeberg = accounts.signIn(ForgeInstance.Codeberg, ForgeUser("octocat", null, null), "c")

        assertThat(viewModel.session.value).isEqualTo(SessionState.SignedIn(codeberg, listOf(github, codeberg)))

        viewModel.signOut(codeberg)

        assertThat(viewModel.session.value).isEqualTo(SessionState.SignedIn(github, listOf(github)))
    }

    @Test
    fun sign_out_forgets_the_active_account() = runTest {
        val account = accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "t")
        val viewModel = SessionViewModel(accounts, signedOutData, clients)

        viewModel.signOut(account)

        assertThat(viewModel.session.value).isEqualTo(SessionState.SignedOut)
        assertThat(accounts.accounts.first()).isEmpty()
    }

    @Test
    fun sign_out_deletes_what_was_kept_for_the_account() = runTest {
        // Regression: signing out only removed the account, and everything read with it stayed on the phone.
        val github = accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "t")
        val codeberg = accounts.signIn(ForgeInstance.Codeberg, ForgeUser("octocat", null, null), "c")
        val viewModel = SessionViewModel(accounts, signedOutData, clients)

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

    @Test
    fun an_account_whose_sign_in_stops_at_public_repositories_is_marked() = runTest {
        // Sign-ins made before private repositories were asked for stay limited until renewed.
        auth.privateAccess["old"] = false
        val viewModel = SessionViewModel(accounts, signedOutData, clients)

        val account = accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "old")

        assertThat((viewModel.session.value as SessionState.SignedIn).limited).containsExactly(account.id)
    }

    @Test
    fun signing_in_again_lifts_the_mark() = runTest {
        auth.privateAccess["old"] = false
        auth.privateAccess["new"] = true
        val viewModel = SessionViewModel(accounts, signedOutData, clients)
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "old")

        accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "new")

        assertThat((viewModel.session.value as SessionState.SignedIn).limited).isEmpty()
    }

    @Test
    fun an_account_the_forge_says_nothing_about_is_not_marked() = runTest {
        val viewModel = SessionViewModel(accounts, signedOutData, clients)

        accounts.signIn(ForgeInstance.Codeberg, ForgeUser("octocat", null, null), "c")

        assertThat((viewModel.session.value as SessionState.SignedIn).limited).isEmpty()
    }

    @Test
    fun an_account_whose_sign_in_the_forge_no_longer_renews_is_marked_until_it_signs_in_again() = runTest {
        val viewModel = SessionViewModel(accounts, signedOutData, clients)
        val github = accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "t")
        val codeberg = accounts.signIn(ForgeInstance.Codeberg, ForgeUser("octocat", null, null), "c")
        assertThat((viewModel.session.value as SessionState.SignedIn).ended).isEmpty()

        accounts.ended.value = setOf(codeberg.id)

        assertThat((viewModel.session.value as SessionState.SignedIn).ended).containsExactly(codeberg.id)
        assertThat((viewModel.session.value as SessionState.SignedIn).accounts).containsExactly(github, codeberg)

        accounts.ended.value = emptySet()
        assertThat((viewModel.session.value as SessionState.SignedIn).ended).isEmpty()
    }

    @Test
    fun an_account_signed_out_is_no_longer_marked() = runTest {
        val viewModel = SessionViewModel(accounts, signedOutData, clients)
        val github = accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "t")
        val codeberg = accounts.signIn(ForgeInstance.Codeberg, ForgeUser("octocat", null, null), "c")
        accounts.ended.value = setOf(codeberg.id)

        viewModel.signOut(codeberg)

        assertThat((viewModel.session.value as SessionState.SignedIn).ended).isEmpty()
        assertThat((viewModel.session.value as SessionState.SignedIn).account).isEqualTo(github)
    }
}
