package fr.arthurbrugiere.forgeline.forge.gitlab.trending

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.TrendingMeasurement
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
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

class GitLabTrendingMeterTest {
    private var now = Instant.parse("2026-09-30T08:00:00Z")
    private val clock = object : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId?) = this
    }
    private var stars = 100
    private var failing = false

    private fun projects() = """[{"id":7,"path":"tool","namespace":{"full_path":"group/sub","avatar_url":"/uploads/a.png"},
        "star_count":$stars,"forks_count":3,"created_at":"2024-01-01T00:00:00.000Z"}]"""

    private val meter = GitLabTrendingMeter(
        forgejoHttpClient(
            MockEngine { request ->
                when {
                    failing -> respond("", HttpStatusCode.ServiceUnavailable)
                    request.url.encodedPath.endsWith("/languages") ->
                        respond("""{"Kotlin":90.5,"Shell":9.5}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    else -> respond(projects(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            },
        ),
        clock = clock,
        retryDelayMillis = 0,
    )

    @Test
    fun a_day_later_the_stars_gained_trend_under_gitlab_with_their_language() = runTest {
        val first = (meter.measure(null, null) as ForgeResult.Success<TrendingMeasurement>).value
        assertThat(first.lists.getValue(TrendingPeriod.DAILY)).isEmpty()

        now = now.plusSeconds(86_400)
        stars = 112
        val second = (meter.measure(null, first.state) as ForgeResult.Success<TrendingMeasurement>).value

        val repo = second.lists.getValue(TrendingPeriod.DAILY).single()
        assertThat(repo.id.forge).isEqualTo(ForgeInstance.GitLab)
        assertThat(repo.id.owner to repo.id.name).isEqualTo("group/sub" to "tool")
        assertThat(repo.periodStars).isEqualTo(12)
        assertThat(repo.language).isEqualTo("Kotlin")
        assertThat(repo.ownerAvatarUrl).isEqualTo("https://gitlab.com/uploads/a.png")
    }

    @Test
    fun gitlab_out_of_reach_is_a_network_error() = runTest {
        failing = true

        assertThat(meter.measure(null, null)).isEqualTo(ForgeResult.Failure(ForgeError.Network))
    }
}
