@file:OptIn(coil3.annotation.ExperimentalCoilApi::class)

package fr.arthurbrugiere.forgeline.di

import android.content.Context
import coil3.ImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.svg.Svg
import coil3.svg.SvgDecoder
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.Response
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
fun forgeEngine(cache: Cache? = null): HttpClientEngine = OkHttp.create {
    config {
        dispatcher(sharedDispatcher)
        connectionPool(sharedConnections)
        if (cache != null) {
            cache(cache)
            addInterceptor(AskAgainWithWhatIsKnown)
        }
    }
}

/** The forges' answers kept on the phone, for [AskAgainWithWhatIsKnown]; pictures have Coil's own cache. */
const val HTTP_CACHE_BYTES = 20L * 1024 * 1024

/**
 * Makes every read the cache could answer ask the forge again, with what is known of it (`If-None-Match`): when
 * nothing changed, the forge answers 304 with no body (GitHub doesn't count it against the rate limit) and the kept
 * answer is read. Never served unasked, however fresh the forge says it is: what was just changed from the app shows
 * at once.
 *
 * A request that brings its own conditions (the Inbox's "anything since?") or says how it may be cached is left as it
 * is. A part of something (a log read a part at a time) is neither kept nor answered from what was: the cache doesn't
 * tell parts apart.
 */
internal object AskAgainWithWhatIsKnown : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val own = request.method != "GET" || request.header("Cache-Control") != null ||
            request.header("If-None-Match") != null || request.header("If-Modified-Since") != null
        val asked = when {
            own -> request
            request.header("Range") != null -> request.newBuilder().cacheControl(CacheControl.Builder().noStore().build()).build()
            else -> request.newBuilder().cacheControl(CacheControl.Builder().maxAge(0, TimeUnit.SECONDS).build()).build()
        }
        return chain.proceed(asked)
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
            add(SvgDecoder.Factory(parser = SizedSvgParser, density = SvgDecoder.PLATFORM_DENSITY, useViewBoundsAsIntrinsicSize = false))
        }
        .build()
}

/**
 * Gives every SVG the size a browser would draw it at, and a view box, before Coil scales it to the screen.
 *
 * Regression: Coil gave a drawing without a view box one as large as the density-scaled room, so the drawing kept
 * one device pixel per CSS pixel in a corner of it: shields.io's badges came out small and far apart. And one given
 * a height alone (CodeScene's) took its view box's size instead, taller than the badges beside it.
 */
internal val SizedSvgParser = Svg.Parser { source ->
    Svg.Parser.DEFAULT.parse(source).apply {
        val box = viewBox
        val boxWidth = box?.let { it.right - it.left } ?: 0f
        val boxHeight = box?.let { it.bottom - it.top } ?: 0f
        when {
            width > 0f && height > 0f -> if (box == null) viewBox = Svg.ViewBox(0f, 0f, width, height)
            boxWidth > 0f && boxHeight > 0f -> {
                // No width: the view box's, which lets the height be read (none is, as long as the width is missing).
                width(boxWidth.toString())
                // A height given alone sets the width by the view box's proportions, as it does in a browser.
                if (height > 0f) width((height * boxWidth / boxHeight).toString()) else height(boxHeight.toString())
            }
        }
    }
}
