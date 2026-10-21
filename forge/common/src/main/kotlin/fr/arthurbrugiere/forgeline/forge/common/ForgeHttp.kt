package fr.arthurbrugiere.forgeline.forge.common

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import io.ktor.client.HttpClient
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.serialization.ContentConvertException
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.OutputStream
import java.time.DateTimeException

/** How every forge's answers are read: fields Forgeline doesn't use are skipped, and nulls are left out of what it sends. */
val ForgeJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

const val USER_AGENT = "Forgeline (+https://github.com/RoiArthurB/forgeline)"

/**
 * A client for a forge's API on [engine]. A failure is an answer like any other (each forge reads its own), and a
 * single slow call may ask for more time with `timeout {}`; the others keep the engine's limits.
 */
fun forgeHttpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
    expectSuccess = false
    install(ContentNegotiation) { json(ForgeJson) }
    install(HttpTimeout)
    defaultRequest {
        header(HttpHeaders.UserAgent, USER_AGENT)
    }
}

/** Runs one call to a forge: no network is [ForgeError.Network], and an answer that can't be read is [ForgeError.Unreadable]. */
suspend inline fun <T> forgeCall(block: () -> ForgeResult<T>): ForgeResult<T> = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: IOException) {
    ForgeResult.Failure(ForgeError.Network)
} catch (e: Exception) {
    // Regression: an answer the app couldn't read (a web page, a field missing, a bad date) used to crash it.
    if (e.isUnreadableAnswer()) ForgeResult.Failure(ForgeError.Unreadable) else throw e
}

/** Whether [this] comes from reading an answer that isn't what the API promises, rather than from a bug elsewhere. */
fun Exception.isUnreadableAnswer(): Boolean =
    this is ContentConvertException || this is NoTransformationFoundException ||
        // Covers kotlinx.serialization's errors, bad numbers and bad Base64.
        this is IllegalArgumentException || this is DateTimeException || this is NoSuchElementException

private val nextLink = Regex("""<[^>]*[?&]page=(\d+)[^>]*>;\s*rel="next"""")

private val lastLink = Regex("""<[^>]*[?&]page=(\d+)[^>]*>;\s*rel="last"""")

/** The next page a forge announces with `Link: <...page=2>; rel="next"` (GitHub, Forgejo, GitLab alike). */
fun HttpResponse.nextLinkedPage(): Int? = headers[HttpHeaders.Link]?.let { nextLink.find(it)?.groupValues?.get(1)?.toIntOrNull() }

/** The last page a forge announces (`rel="last"`), so the pages in between can be asked all at once. */
fun HttpResponse.lastLinkedPage(): Int? = headers[HttpHeaders.Link]?.let { lastLink.find(it)?.groupValues?.get(1)?.toIntOrNull() }

/** Writes what's left of [this] to [into] as it comes, a piece at a time. */
suspend fun ByteReadChannel.writeTo(into: OutputStream) {
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = readAvailable(buffer, 0, buffer.size)
        if (read == -1) break
        into.write(buffer, 0, read)
    }
}
