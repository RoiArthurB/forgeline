package fr.arthurbrugiere.forgeline.forge.github

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.io.IOException

internal suspend inline fun <T> gitHubCall(block: () -> ForgeResult<T>): ForgeResult<T> = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: IOException) {
    ForgeResult.Failure(ForgeError.Network)
}

internal suspend inline fun <T> HttpResponse.toResult(parse: HttpResponse.() -> T): ForgeResult<T> =
    if (status.isSuccess()) ForgeResult.Success(parse()) else failure()

internal suspend fun HttpResponse.failure(): ForgeResult.Failure {
    val error = when {
        status == HttpStatusCode.Unauthorized -> ForgeError.Unauthorized
        (status == HttpStatusCode.Forbidden || status == HttpStatusCode.TooManyRequests) &&
            headers["x-ratelimit-remaining"] == "0" ->
            ForgeError.RateLimited(headers["x-ratelimit-reset"]?.toLongOrNull())
        status == HttpStatusCode.TooManyRequests -> ForgeError.RateLimited(headers["retry-after"]?.toLongOrNull())
        else -> ForgeError.Http(status.value, errorMessage())
    }
    return ForgeResult.Failure(error)
}

private suspend fun HttpResponse.errorMessage(): String? =
    runCatching { GitHubJson.decodeFromString<ErrorResponse>(bodyAsText()).message }.getOrNull()

@Serializable
private data class ErrorResponse(val message: String? = null)
