package fr.arthurbrugiere.forgeline.di

import android.content.Context
import coil3.ImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.svg.SvgDecoder
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import java.util.concurrent.TimeUnit

/**
 * How many requests one forge serves at once. OkHttp's default, 5, capped every fan-out (an Inbox sync, the Feed's
 * followed people, conversations loaded ahead) whatever the code asked for: far from the forge, each wave of 5 costs a
 * full round trip. Over HTTP/2 (GitHub, Codeberg), more requests share the one connection instead of opening others.
 */
const val REQUESTS_PER_FORGE = 16

/** Every request in flight across forges. */
const val REQUESTS_IN_FLIGHT = 64

fun forgeDispatcher(): Dispatcher = Dispatcher().apply {
    maxRequestsPerHost = REQUESTS_PER_FORGE
    maxRequests = REQUESTS_IN_FLIGHT
}

/**
 * The engine every forge client runs on. Connections are kept 5 minutes (OkHttp's default), so a TLS handshake to a
 * far forge (two round trips) is paid once per session, not per request.
 *
 * Every engine (each forge's client and the pictures') shares one dispatcher and one pool. Regression: each had its
 * own, so pictures served by the forge's own host (Codeberg's and GitLab's avatars) opened a second connection beside
 * the API's, a second handshake, and each engine kept threads of its own.
 */
fun forgeEngine(): HttpClientEngine = OkHttp.create {
    config {
        dispatcher(sharedDispatcher)
        connectionPool(sharedConnections)
    }
}

private val sharedDispatcher by lazy { forgeDispatcher() }

private val sharedConnections by lazy { ConnectionPool(IDLE_CONNECTIONS, 5, TimeUnit.MINUTES) }

/** Idle connections kept across every forge and picture host. */
private const val IDLE_CONNECTIONS = 16

/**
 * Loads every picture (avatars, README images) through [engine]. Coil's own client was held to OkHttp's 5 requests
 * per host, so a screen of avatars, all from one host, loaded in waves.
 *
 * SVG is decoded too: a README's badges are all SVG, and Android reads none on its own. Their size is in CSS pixels,
 * drawn as as many dp, so a 20-pixel badge is as tall on a phone as on the forge's site.
 */
fun forgeImageLoader(context: Context, engine: HttpClientEngine = forgeEngine()): ImageLoader {
    val client = lazy { HttpClient(engine) }
    return ImageLoader.Builder(context)
        .components {
            add(KtorNetworkFetcherFactory(httpClient = { client.value }))
            add(SvgDecoder.Factory(scaleToDensity = true))
        }
        .build()
}
