package fr.arthurbrugiere.forgeline.di

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HttpEngineTest {
    @Test
    fun a_forge_serves_many_requests_at_once_not_okhttps_five() {
        // Regression: OkHttp's default of 5 per host capped every parallel fan-out to a far forge.
        val dispatcher = forgeDispatcher()

        assertThat(dispatcher.maxRequestsPerHost).isEqualTo(REQUESTS_PER_FORGE)
        assertThat(dispatcher.maxRequestsPerHost).isGreaterThan(5)
        assertThat(dispatcher.maxRequests).isAtLeast(dispatcher.maxRequestsPerHost * 2)
    }
}
