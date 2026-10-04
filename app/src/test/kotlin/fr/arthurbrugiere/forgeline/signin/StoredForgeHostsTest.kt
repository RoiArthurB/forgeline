package fr.arthurbrugiere.forgeline.signin

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeType
import fr.arthurbrugiere.forgeline.core.model.KnownForges
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StoredForgeHostsTest {
    private val ownGitLab = ForgeInstance(ForgeType.GITLAB, "gitlab.example.org")

    private fun hosts() = StoredForgeHosts(ApplicationProvider.getApplicationContext())

    @After
    fun forget() {
        KnownForges.clear()
        ApplicationProvider.getApplicationContext<android.content.Context>().getSharedPreferences("forge_hosts", 0).edit().clear().commit()
    }

    @Test
    fun a_server_remembered_is_known_again_at_the_next_launch() {
        hosts().remember(ownGitLab, "app-id-123")
        // The process ends: what was in memory is gone.
        KnownForges.clear()
        assertThat(ForgeInstance.of("gitlab.example.org").type).isEqualTo(ForgeType.FORGEJO)

        val relaunched = hosts()
        relaunched.load()

        assertThat(ForgeInstance.of("gitlab.example.org")).isEqualTo(ownGitLab)
        assertThat(relaunched.oauthClientId("gitlab.example.org")).isEqualTo("app-id-123")
    }

    @Test
    fun signing_in_again_with_a_token_keeps_the_application_given_before() {
        val hosts = hosts()
        hosts.remember(ownGitLab, "app-id-123")

        hosts.remember(ownGitLab, "")
        hosts.remember(ownGitLab)

        assertThat(hosts.oauthClientId("gitlab.example.org")).isEqualTo("app-id-123")
    }

    @Test
    fun a_server_never_seen_has_no_application() {
        assertThat(hosts().oauthClientId("git.example.org")).isEmpty()
    }
}
