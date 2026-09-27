package fr.arthurbrugiere.forgeline.core.forge

import fr.arthurbrugiere.forgeline.core.model.FeedEvent

/** [events] is null when nothing changed since `ifModifiedSince`. */
data class FeedPage(
    val events: List<FeedEvent>?,
    val nextPage: Int?,
    val lastModified: String? = null,
    /** Minimum seconds the forge asks clients to wait between polls. */
    val pollIntervalSeconds: Int? = null,
)

interface FeedApi {
    /** Activity of the people [login] follows and the repos they watch, newest first. */
    suspend fun receivedEvents(token: String?, login: String, page: Int = 1, ifModifiedSince: String? = null): ForgeResult<FeedPage>
}
