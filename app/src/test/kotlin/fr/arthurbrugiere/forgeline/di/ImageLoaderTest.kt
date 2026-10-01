package fr.arthurbrugiere.forgeline.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.google.common.truth.Truth.assertThat
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
}
