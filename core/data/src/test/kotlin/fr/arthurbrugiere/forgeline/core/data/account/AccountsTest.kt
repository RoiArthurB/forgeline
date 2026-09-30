package fr.arthurbrugiere.forgeline.core.data.account

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AccountsTest {
    private val accounts = FakeAccountRepository()

    @Test
    fun each_forge_uses_the_account_signed_in_there() = runTest {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "gh_token")
        accounts.signIn(ForgeInstance.Codeberg, ForgeUser("me", null, null), "cb_token")

        assertThat(accounts.tokenOn(ForgeInstance.GitHub)).isEqualTo("gh_token")
        assertThat(accounts.tokenOn(ForgeInstance.Codeberg)).isEqualTo("cb_token")
    }

    @Test
    fun a_forge_without_an_account_goes_anonymous() = runTest {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "gh_token")

        assertThat(accounts.accountOn(ForgeInstance.Codeberg)).isNull()
        assertThat(accounts.tokenOn(ForgeInstance.Codeberg)).isNull()
    }
}
