package fr.arthurbrugiere.forgeline.core.data.user

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.OwnerSettingsApi
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.Owner
import fr.arthurbrugiere.forgeline.core.model.OwnerSettingsChange
import fr.arthurbrugiere.forgeline.core.model.OwnerText
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import fr.arthurbrugiere.forgeline.core.testing.FakeOwnerSettingsApi
import fr.arthurbrugiere.forgeline.core.testing.FakeUserApi
import fr.arthurbrugiere.forgeline.core.testing.userProfile
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultOwnerSettingsRepositoryTest {
    private val gitHub = FakeOwnerSettingsApi()
    private val gitLab = FakeOwnerSettingsApi()
    private val users = FakeUserApi().apply { this.users["mona"] = userProfile("mona"); this.users["octo"] = userProfile("octo") }
    private val clients = FakeForgeClients(ownerSettings = gitHub, users = users).apply { put(ForgeInstance.GitLab, FakeForgeClients(ownerSettings = gitLab)) }
    private val accounts = FakeAccountRepository()
    private val userRepository = DefaultUserRepository(clients, accounts)
    private val repository = DefaultOwnerSettingsRepository(clients, accounts, userRepository)

    private suspend fun signIn(forge: ForgeInstance = ForgeInstance.GitHub) = accounts.signIn(forge, ForgeUser("mona", null, null), "tok")

    @Test
    fun one_s_own_profile_offers_its_settings_and_someone_else_s_doesn_t() = runTest {
        assertThat(repository.offersSettings(ForgeInstance.GitHub, "mona", organization = false)).isFalse()
        signIn()

        assertThat(repository.offersSettings(ForgeInstance.GitHub, "Mona", organization = false)).isTrue()
        assertThat(repository.offersSettings(ForgeInstance.GitHub, "hubot", organization = false)).isFalse()
        // Signed in on GitHub only.
        assertThat(repository.offersSettings(ForgeInstance.GitLab, "mona", organization = false)).isFalse()
    }

    @Test
    fun an_organisation_offers_them_to_its_owners_and_to_a_sign_in_that_can_t_tell() = runTest {
        signIn()

        assertThat(repository.offersSettings(ForgeInstance.GitHub, "octo", organization = true)).isTrue()
        assertThat(repository.offersSettings(ForgeInstance.GitHub, "elsewhere", organization = true)).isFalse()
        val cantTell = object : OwnerSettingsApi {
            override val organizationsSupported = true

            override suspend fun administers(token: String, organization: String) = ForgeResult.Failure(ForgeError.Unauthorized)
        }
        val old = DefaultOwnerSettingsRepository(FakeForgeClients(ownerSettings = cantTell), accounts, userRepository)
        assertThat(old.offersSettings(ForgeInstance.GitHub, "octo", organization = true)).isTrue()
    }

    @Test
    fun a_forge_without_them_offers_none() = runTest {
        signIn()
        val none = DefaultOwnerSettingsRepository(FakeForgeClients(ownerSettings = FakeOwnerSettingsApi(profileSupported = false, organizationsSupported = false)), accounts, userRepository)

        assertThat(none.offersSettings(ForgeInstance.GitHub, "mona", organization = false)).isFalse()
        assertThat(none.offersSettings(ForgeInstance.GitHub, "octo", organization = true)).isFalse()
    }

    @Test
    fun a_change_goes_to_its_forge_and_the_profile_is_read_again() = runTest {
        signIn()

        val changed = repository.change(ForgeInstance.GitHub, Owner.Me, OwnerSettingsChange.Text(OwnerText.LOCATION, "Lyon"))

        assertThat((changed as ForgeResult.Success).value.texts[OwnerText.LOCATION]).isEqualTo("Lyon")
        assertThat(gitLab.calls).isEmpty()
        assertThat(userRepository.cachedUser(ForgeInstance.GitHub, "mona")).isNotNull()
    }

    @Test
    fun signed_in_nowhere_on_its_forge_nothing_is_asked() = runTest {
        signIn()

        assertThat(repository.settings(ForgeInstance.GitLab, Owner.Me)).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
        assertThat(gitLab.calls).isEmpty()
    }
}
