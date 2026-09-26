package fr.arthurbrugiere.forgeline.forge.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

internal val GitHubJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

fun gitHubHttpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
    expectSuccess = false
    install(ContentNegotiation) { json(GitHubJson) }
    defaultRequest {
        header(HttpHeaders.UserAgent, "Forgeline (+https://github.com/RoiArthurB/forgeline)")
    }
}
