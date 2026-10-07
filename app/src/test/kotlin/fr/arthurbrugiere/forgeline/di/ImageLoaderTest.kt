package fr.arthurbrugiere.forgeline.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
class ImageLoaderTest {
    @Test
    fun pictures_are_fetched_through_the_engine_the_app_gives() = runBlocking<Unit> {
        // Regression: pictures went through an HTTP client of Coil's own, held to OkHttp's 5 requests per host, so a
        // screen of avatars loaded in waves. They now share the forge engine's settings.
        val asked = CopyOnWriteArrayList<String>()
        val engine = MockEngine { request -> asked += request.url.toString(); respond("", HttpStatusCode.NotFound) }
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Coil keeps answers on disk, failures included, across tests: this one must go out.
        val request = ImageRequest.Builder(context).data("https://avatars.example/u/1").diskCachePolicy(CachePolicy.DISABLED).build()
        forgeImageLoader(context, engine).execute(request)

        assertThat(asked).containsExactly("https://avatars.example/u/1")
    }

    @Test
    @Config(qualifiers = PHONE)
    fun svg_badges_are_drawn_at_the_screen_density() = runBlocking<Unit> {
        // Regression: nothing decoded SVG, so a README's badges (shields.io, a workflow's status) never showed. One
        // without a file extension proves the content is what counts, not the address.
        val badge = """<svg xmlns="http://www.w3.org/2000/svg" width="90" height="20"><rect width="90" height="20" fill="#4c1"/></svg>"""
        val engine = MockEngine { respond(badge, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/svg+xml")) }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val density = context.resources.displayMetrics.density
        assertThat(density).isGreaterThan(2f)

        val request = ImageRequest.Builder(context).data("https://badges.example/projects/1/status")
            .size(Size.ORIGINAL).diskCachePolicy(CachePolicy.DISABLED).build()
        val result = forgeImageLoader(context, engine).execute(request)

        // A badge is 20 CSS pixels tall: as many dp here, not 20 device pixels (a third of that on a phone).
        assertThat(result).isInstanceOf(SuccessResult::class.java)
        assertThat(result.image!!.width).isEqualTo((90 * density).toInt())
        assertThat(result.image!!.height).isEqualTo((20 * density).toInt())
    }
}
