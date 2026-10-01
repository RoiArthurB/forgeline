package fr.arthurbrugiere.forgeline.core.forge

import fr.arthurbrugiere.forgeline.core.model.FeedEvent
import java.time.Instant

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

    /**
     * What the repositories the signed-in person starred published since [since]: each one's latest release and, where
     * the forge has them, its announcements. The forges' own feeds leave these out (they only cover people followed and
     * repositories watched). It can take many seconds, so it is asked apart from [receivedEvents]. An announcement
     * posted with a release is left out: the release says it.
     */
    suspend fun starredActivity(token: String, since: Instant): ForgeResult<List<FeedEvent>> = ForgeResult.Success(emptyList())
}
