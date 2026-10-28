package fr.arthurbrugiere.forgeline.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeType
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
class PrivatePicturesTest {
    private val server = ForgeInstance(ForgeType.GITLAB, "git.example.org")
    private val access = listOf(
        PictureAccess(ForgeInstance.GitHub, "gh"),
        PictureAccess(ForgeInstance.Codeberg, "cb"),
        PictureAccess(ForgeInstance.GitLab, "gl"),
        PictureAccess(server, "self"),
    )

    @Test
    fun github_and_forgejo_files_are_asked_again_where_they_are_with_the_token() {
        assertThat(authenticatedPicture("https://raw.githubusercontent.com/me/secret/main/docs/shot.png", access))
            .isEqualTo(AuthenticatedPicture("https://raw.githubusercontent.com/me/secret/main/docs/shot.png", "Authorization", "token gh"))
        assertThat(authenticatedPicture("https://codeberg.org/me/secret/raw/branch/main/shot.png", access))
            .isEqualTo(AuthenticatedPicture("https://codeberg.org/me/secret/raw/branch/main/shot.png", "Authorization", "token cb"))
    }

    @Test
    fun a_gitlab_file_is_read_from_its_api_which_takes_a_token_where_its_raw_page_does_not() {
        assertThat(authenticatedPicture("https://gitlab.com/group/sub/proj/-/raw/main/docs/my%20shot.png", access)).isEqualTo(
            AuthenticatedPicture(
                "https://gitlab.com/api/v4/projects/group%2Fsub%2Fproj/repository/files/docs%2Fmy%20shot.png/raw?ref=main",
                "Authorization",
                "Bearer gl",
            ),
        )
        // A server signed in to, with its own token.
        assertThat(authenticatedPicture("https://git.example.org/team/app/-/raw/dev/logo.svg", access)?.value).isEqualTo("Bearer self")
    }

    @Test
    fun the_token_goes_to_no_other_host_nor_page() {
        listOf(
            "https://avatars.githubusercontent.com/u/1",
            "https://img.shields.io/badge/build-passing-green",
            "https://codeberg.org/me/secret/issues/1",
            "https://gitlab.com/group/proj/-/blob/main/shot.png",
            "http://raw.githubusercontent.com/me/secret/main/shot.png",
            "https://evil.example/raw.githubusercontent.com/me/secret/main/shot.png",
        ).forEach { assertThat(authenticatedPicture(it, access)).isNull() }
        // Signed in nowhere: nothing is asked again.
        assertThat(authenticatedPicture("https://raw.githubusercontent.com/me/secret/main/shot.png", emptyList())).isNull()
    }

    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52, 0x00, 0x00, 0x00, 0x01,
        0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(), 0x89.toByte(), 0x00, 0x00, 0x00, 0x0D, 0x49,
        0x44, 0x41, 0x54, 0x78, 0x9C.toByte(), 0x63, 0xF8.toByte(), 0xCF.toByte(), 0xC0.toByte(), 0xF0.toByte(), 0x1F, 0x00, 0x05, 0x00,
        0x01, 0xFF.toByte(), 0x89.toByte(), 0x99.toByte(), 0x3D, 0x1D, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42,
        0x60, 0x82.toByte(),
    )

    private fun load(url: String, signedIn: List<PictureAccess>, answer: (authorized: Boolean) -> Boolean): Pair<Boolean, List<String>> = runBlocking {
        val asked = CopyOnWriteArrayList<String>()
        val engine = MockEngine { request ->
            val authorization = request.headers[HttpHeaders.Authorization]
            asked += "${request.url}${authorization?.let { " ($it)" }.orEmpty()}"
            if (answer(authorization != null)) respond(png, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png")) else respond("", HttpStatusCode.NotFound)
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val request = ImageRequest.Builder(context).data(url).size(Size.ORIGINAL)
            .diskCachePolicy(CachePolicy.DISABLED).memoryCachePolicy(CachePolicy.DISABLED).build()
        (forgeImageLoader(context, engine) { signedIn }.execute(request) is SuccessResult) to asked
    }

    @Test
    fun a_private_repository_s_picture_comes_once_asked_again_with_the_token() {
        // Regression: README pictures and image files of private repositories never showed: no token went with them.
        val (shown, asked) = load("https://raw.githubusercontent.com/me/secret/main/shot.png", access) { authorized -> authorized }

        assertThat(shown).isTrue()
        assertThat(asked).containsExactly(
            "https://raw.githubusercontent.com/me/secret/main/shot.png",
            "https://raw.githubusercontent.com/me/secret/main/shot.png (token gh)",
        ).inOrder()
    }

    @Test
    fun a_public_picture_is_asked_once_without_any_token() {
        val (shown, asked) = load("https://raw.githubusercontent.com/me/open/main/shot.png", access) { true }

        assertThat(shown).isTrue()
        assertThat(asked).containsExactly("https://raw.githubusercontent.com/me/open/main/shot.png")
    }

    @Test
    fun signed_out_a_refused_picture_stays_refused() {
        val (shown, asked) = load("https://raw.githubusercontent.com/me/secret/main/shot.png", emptyList()) { authorized -> authorized }

        assertThat(shown).isFalse()
        assertThat(asked).hasSize(1)
    }
}
