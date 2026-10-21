package fr.arthurbrugiere.forgeline.forge.gitlab

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** What every GitLab call shares: how its failures read. */
class GitLabHttpTest {
    private suspend fun failureOf(body: String, status: HttpStatusCode, type: String = "application/json"): ForgeResult.Failure =
        gitlabHttpClient(MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, type)) })
            .gitlabApi(ForgeInstance.GitLab, "t", "projects", "1")
            .failure()

    // Callers used to encode a project's path and a file's by hand, each in its own way; one left out was a broken call.
    @Test
    fun each_segment_is_encoded_slashes_and_all() = runTest {
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        gitlabHttpClient(MockEngine { requests += it; respond("{}") })
            .gitlabApi(ForgeInstance.GitLab, "t", "projects", "group/sub group", "repository", "files", "docs/a #1?.md", "raw")

        assertThat(requests.single().url.encodedPath).isEqualTo("/api/v4/projects/group%2Fsub%20group/repository/files/docs%2Fa%20%231%3F.md/raw")
    }

    // Regression: the whole answer was the message, so a failed merge said {"message":"405 Method Not Allowed"}.
    @Test
    fun an_error_keeps_gitlabs_message_not_its_whole_answer() = runTest {
        assertThat(failureOf("""{"message":"405 Method Not Allowed"}""", HttpStatusCode.MethodNotAllowed))
            .isEqualTo(ForgeResult.Failure(ForgeError.Http(405, "405 Method Not Allowed")))
    }

    @Test
    fun a_message_given_field_by_field_reads_as_one_line() = runTest {
        val failure = failureOf("""{"message":{"title":["can't be blank"],"base":["is locked"]}}""", HttpStatusCode.BadRequest)

        assertThat(failure).isEqualTo(ForgeResult.Failure(ForgeError.Http(400, "title can't be blank, is locked")))
    }

    @Test
    fun a_message_given_as_a_list_reads_as_one_line() = runTest {
        assertThat(failureOf("""{"message":["Branch cannot be merged","Pipeline must succeed"]}""", HttpStatusCode.NotAcceptable))
            .isEqualTo(ForgeResult.Failure(ForgeError.Http(406, "Branch cannot be merged, Pipeline must succeed")))
    }

    @Test
    fun an_error_named_error_is_read_too() = runTest {
        assertThat(failureOf("""{"error":"title is missing"}""", HttpStatusCode.BadRequest))
            .isEqualTo(ForgeResult.Failure(ForgeError.Http(400, "title is missing")))
    }

    @Test
    fun an_answer_that_isnt_json_has_no_message() = runTest {
        assertThat(failureOf("<html>502 Bad Gateway</html>", HttpStatusCode.BadGateway, type = "text/html"))
            .isEqualTo(ForgeResult.Failure(ForgeError.Http(502, null)))
    }
}
