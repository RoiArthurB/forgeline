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
import org.robolectric.annotation.GraphicsMode
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

    private fun decoded(svg: String): android.graphics.Bitmap = runBlocking {
        val engine = MockEngine { respond(svg, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/svg+xml")) }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val request = ImageRequest.Builder(context).data("https://badges.example/${svg.hashCode()}")
            .size(Size.ORIGINAL).diskCachePolicy(CachePolicy.DISABLED).build()
        (forgeImageLoader(context, engine).execute(request).image as coil3.BitmapImage).bitmap
    }

    @Test
    @Config(qualifiers = PHONE)
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun a_badge_fills_the_room_it_is_given() {
        // Regression: a badge with a size and no view box (shields.io's, a workflow's status) was drawn at one device
        // pixel per CSS pixel in the corner of a room three times as large: small badges, far apart (GAMA's README).
        val bitmap = decoded("""<svg xmlns="http://www.w3.org/2000/svg" width="90" height="20"><rect width="90" height="20" fill="#44cc11"/></svg>""")

        assertThat(bitmap.getPixel(bitmap.width - 2, bitmap.height - 2)).isEqualTo(0xFF44CC11.toInt())
    }

    @Test
    @Config(qualifiers = PHONE)
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun a_badge_given_only_a_height_keeps_that_height() {
        // Regression: CodeScene's badge says how tall it is and leaves its width to its view box. The view box's size
        // was taken instead, so it stood half as tall again as the badges beside it.
        val density = ApplicationProvider.getApplicationContext<Context>().resources.displayMetrics.density
        val bitmap = decoded(
            """<svg xmlns="http://www.w3.org/2000/svg" height="20" viewBox="0 0 180 30"><rect width="180" height="30" fill="#44cc11"/></svg>""",
        )

        assertThat(bitmap.height).isEqualTo((20 * density).toInt())
        assertThat(bitmap.width).isEqualTo((120 * density).toInt())
        assertThat(bitmap.getPixel(bitmap.width - 2, bitmap.height - 2)).isEqualTo(0xFF44CC11.toInt())
    }

    @Test
    @Config(qualifiers = PHONE)
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun a_drawing_with_only_a_view_box_takes_its_size() {
        val density = ApplicationProvider.getApplicationContext<Context>().resources.displayMetrics.density
        val bitmap = decoded("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 40 30"><rect width="40" height="30" fill="#44cc11"/></svg>""")

        assertThat(bitmap.width).isEqualTo((40 * density).toInt())
        assertThat(bitmap.height).isEqualTo((30 * density).toInt())
        assertThat(bitmap.getPixel(bitmap.width - 2, bitmap.height - 2)).isEqualTo(0xFF44CC11.toInt())
    }
}
