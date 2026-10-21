package fr.arthurbrugiere.forgeline.forge.github

import fr.arthurbrugiere.forgeline.forge.common.forgeHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine

fun gitHubHttpClient(engine: HttpClientEngine): HttpClient = forgeHttpClient(engine)
