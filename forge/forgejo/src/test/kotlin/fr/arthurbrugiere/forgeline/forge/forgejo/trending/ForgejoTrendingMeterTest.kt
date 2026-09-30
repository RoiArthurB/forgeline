package fr.arthurbrugiere.forgeline.forge.forgejo.trending

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.TrendingMeasurement
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeType
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import fr.arthurbrugiere.forgeline.forge.forgejo.forgejoHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class ForgejoTrendingMeterTest {
    private val server = ForgeInstance(ForgeType.FORGEJO, "git.example.org")
    private var now = Instant.parse("2026-09-30T08:00:00Z")
    private val clock = object : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId?) = this
    }
    private var stars = 10
    private var failing = false

    private fun repos() = """{"ok":true,"data":[
        {"id":1,"name":"tool","owner":{"login":"team","avatar_url":"https://git.example.org/avatars/t"},"language":"Go",
         "stars_count":$stars,"forks_count":1,"created_at":"2024-01-01T00:00:00Z"}]}"""

    private val meter = ForgejoTrendingMeter(
        forgejoHttpClient(
            MockEngine { request ->
                when {
                    failing -> respond("", HttpStatusCode.BadGateway)
                    request.url.parameters["mode"] == "fork" -> respond("""{"ok":true,"data":[]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    else -> respond(repos(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            },
        ),
        server,
        clock,
        retryDelayMillis = 0,
    )

    @Test
    fun a_day_later_the_stars_gained_trend_on_the_servers_own_repositories() = runTest {
        val first = (meter.measure("t", previousState = null) as ForgeResult.Success<TrendingMeasurement>).value
        // One measurement: nothing to compare with yet.
        assertThat(first.lists.getValue(TrendingPeriod.DAILY)).isEmpty()

        now = now.plusSeconds(24 * 3600)
        stars = 15
        val second = (meter.measure("t", first.state) as ForgeResult.Success<TrendingMeasurement>).value

        val daily = second.lists.getValue(TrendingPeriod.DAILY).single()
        assertThat(daily.id.forge).isEqualTo(server)
        assertThat(daily.id.fullName).isEqualTo("team/tool")
        assertThat(daily.periodStars).isEqualTo(5)
        assertThat(daily.language).isEqualTo("Go")
    }

    @Test
    fun an_unreadable_history_starts_afresh() = runTest {
        val result = meter.measure("t", previousState = "not json")

        assertThat(result).isInstanceOf(ForgeResult.Success::class.java)
    }

    @Test
    fun a_server_that_cant_be_read_is_a_network_error_and_keeps_no_partial_history() = runTest {
        failing = true

        assertThat(meter.measure("t", previousState = null)).isEqualTo(ForgeResult.Failure(ForgeError.Network))
    }
}
