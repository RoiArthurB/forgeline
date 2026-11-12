package fr.arthurbrugiere.forgeline.forge.forgejo

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import java.util.concurrent.TimeUnit

/**
 * The account the live job has on Codeberg, when it has one (CODEBERG_LIVE_TOKEN). Codeberg refuses repository
 * search to anyone signed out, with a 429 saying search "is seeing a high influx of requests" while its quota
 * headers show most of it left (checked 2026-11-12); signed in, the same search answers. Null without one.
 */
internal val codebergToken: String? = System.getenv("CODEBERG_LIVE_TOKEN")?.takeIf { it.isNotBlank() }

/**
 * Forgeline's own Forgejo client, with the patience and the second tries a slow network needs. From GitHub's runners,
 * Codeberg now and then takes longer than the app's 10 seconds to answer, and the live job read that as the API
 * having changed (2026-11-09 to 2026-11-11, a different handful of checks each night). What is checked is still the
 * answer's shape; a call that only came slowly is asked again.
 */
internal fun liveForgejoClient(): HttpClient = forgejoHttpClient(
    OkHttp.create {
        config {
            connectTimeout(30, TimeUnit.SECONDS)
            readTimeout(30, TimeUnit.SECONDS)
        }
    },
).config { retrySlowAnswers() }

/** Up to three more tries, further apart each time, when a call broke off or the server failed. */
internal fun io.ktor.client.HttpClientConfig<*>.retrySlowAnswers() = install(HttpRequestRetry) {
    retryOnExceptionOrServerErrors(maxRetries = 3)
    exponentialDelay()
}
