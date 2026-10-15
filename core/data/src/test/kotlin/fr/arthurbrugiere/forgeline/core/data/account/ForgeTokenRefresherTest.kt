package fr.arthurbrugiere.forgeline.core.data.account

import com.google.common.truth.Truth.assertThat
import dagger.Lazy
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ForgeTokenRefresherTest {
    @Test
    fun the_forges_clients_are_built_when_a_token_runs_out_not_when_the_accounts_are_read() = runTest {
        var built = 0
        val refresher = ForgeTokenRefresher(Lazy { built++; FakeForgeClients() })
        // The accounts' store is made at launch, on the main thread: building every HTTP client there cost milliseconds.
        assertThat(built).isEqualTo(0)

        refresher.refresh(ForgeInstance.GitHub, "refresh")

        assertThat(built).isEqualTo(1)
    }
}
