package fr.arthurbrugiere.forgeline.forge.tangled

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.StarApi
import fr.arthurbrugiere.forgeline.core.forge.map
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.tangled.feed.GetStarQueryParams
import org.tangled.graph.GetFollowQueryParams
import sh.christian.ozone.api.response.AtpResponse
import java.time.Clock
import java.time.temporal.ChronoUnit

// Stars and follows on Tangled are records the person writes to their own server, which Tangled's index then reads:
// `org.tangled.feed.star` (its subject the repository's DID) and `org.tangled.graph.follow` (keyed by the DID followed),
// as Tangled's own web app writes them (web/src/lib/api/graph.ts, read 2026-11-02). Whether one is there is asked of the
// index (`getStar`, `getFollow`), which answers the record's AT URI, the one deleted to take it back. A record written
// by Tangled's former app (`sh.tangled.feed.star`, `sh.tangled.graph.follow`) is found and deleted the same way.

internal const val STAR_COLLECTION = "org.tangled.feed.star"
internal const val FOLLOW_COLLECTION = "org.tangled.graph.follow"

/** Starring a Tangled repository, as the person signed in. */
class TangledStarApi(
    private val xrpc: TangledXrpc,
    private val account: TangledAccount,
    private val repos: TangledRepoApi,
    private val clock: Clock = Clock.systemUTC(),
) : StarApi {

    override suspend fun starredStatus(token: String, repos: List<RepoId>): ForgeResult<Map<RepoId, Boolean>> = forgeCall {
        val me = when (val did = account.did(token)) {
            is ForgeResult.Failure -> return@forgeCall did
            is ForgeResult.Success -> did.value
        }
        val answers = coroutineScope { repos.map { id -> async { id to star(me, id) } }.awaitAll() }
        // One that couldn't be asked is left out, as the API says; offline is offline for all.
        if (answers.isNotEmpty() && answers.all { (it.second as? ForgeResult.Failure)?.error == ForgeError.Network }) {
            return@forgeCall ForgeResult.Failure(ForgeError.Network)
        }
        ForgeResult.Success(answers.mapNotNull { (id, star) -> (star as? ForgeResult.Success)?.let { id to (it.value != null) } }.toMap())
    }

    override suspend fun setStarred(token: String, repo: RepoId, starred: Boolean): ForgeResult<Unit> = forgeCall {
        val me = when (val did = account.did(token)) {
            is ForgeResult.Failure -> return@forgeCall did
            is ForgeResult.Success -> did.value
        }
        val repoDid = when (val did = repoDid(repo)) {
            is ForgeResult.Failure -> return@forgeCall did
            is ForgeResult.Success -> did.value
        }
        val existing = when (val star = starOf(me, repoDid)) {
            is ForgeResult.Failure -> return@forgeCall star
            is ForgeResult.Success -> star.value
        }
        when {
            // Already as asked: a second star would count twice.
            starred && existing != null -> ForgeResult.Success(Unit)
            starred -> account.createRecord(
                token,
                STAR_COLLECTION,
                buildJsonObject {
                    put("\$type", STAR_COLLECTION)
                    putJsonObject("subject") {
                        put("\$type", "$STAR_COLLECTION#repo")
                        put("identity", repoDid)
                    }
                    put("createdAt", now())
                },
            ).map { }
            // The index may not have read a star given moments ago: the person's own server has it at once.
            else -> (existing ?: ownStarOf(me, repoDid))?.let { account.deleteRecord(token, it) } ?: ForgeResult.Success(Unit)
        }
    }

    /** The AT URI of [me]'s star on [repo], null when there's none. */
    private suspend fun star(me: String, repo: RepoId): ForgeResult<String?> = when (val did = repoDid(repo)) {
        is ForgeResult.Failure -> did
        is ForgeResult.Success -> starOf(me, did.value)
    }

    private suspend fun starOf(me: String, repoDid: String): ForgeResult<String?> = forgeCall {
        val actor = when (val did = asDid(me)) {
            is ForgeResult.Failure -> return@forgeCall did
            is ForgeResult.Success -> did.value
        }
        xrpc.index.getStar(GetStarQueryParams(actor, repoDid)).uriOrNone { it.uri.atUri }
    }

    /** [me]'s star on [repoDid] as their server keeps it, looked for among their newest stars. */
    private suspend fun ownStarOf(me: String, repoDid: String): String? {
        var cursor: String? = null
        repeat(MAX_OWN_PAGES) {
            val page = (account.listRecords(me, STAR_COLLECTION, cursor) as? ForgeResult.Success)?.value ?: return null
            page.records.firstOrNull { (_, record) -> record.starredRepo == repoDid }?.let { return it.first }
            cursor = page.cursor?.takeIf { it.isNotBlank() && page.records.isNotEmpty() } ?: return null
        }
        return null
    }

    private suspend fun repoDid(repo: RepoId): ForgeResult<String> = when (val found = repos.lookUp(repo)) {
        is ForgeResult.Failure -> found
        is ForgeResult.Success -> found.value.repoDid?.let { ForgeResult.Success(it) } ?: ForgeResult.Failure(ForgeError.Unsupported)
    }

    private fun now(): String = clock.instant().truncatedTo(ChronoUnit.MILLIS).toString()

    private companion object {
        const val MAX_OWN_PAGES = 5
    }
}

/** The repository a star record is given to: `subject.identity` now, `subject.did` in Tangled's former records. */
internal val JsonObject.starredRepo: String?
    get() = (this["subject"] as? JsonObject)?.let { subject ->
        ((subject["identity"] ?: subject["did"]) as? JsonPrimitive)?.contentOrNull
    }

/** Following people on Tangled, as the person signed in. */
internal class TangledFollows(
    private val xrpc: TangledXrpc,
    private val account: TangledAccount,
    private val identity: TangledIdentity,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun isFollowing(token: String, login: String): ForgeResult<Boolean> = forgeCall {
        when (val follow = follow(token, login)) {
            is ForgeResult.Failure -> follow
            is ForgeResult.Success -> ForgeResult.Success(follow.value.third != null)
        }
    }

    suspend fun setFollowing(token: String, login: String, follow: Boolean): ForgeResult<Unit> = forgeCall {
        val (me, subject, existing) = when (val found = follow(token, login)) {
            is ForgeResult.Failure -> return@forgeCall found
            is ForgeResult.Success -> found.value
        }
        when {
            follow && existing != null -> ForgeResult.Success(Unit)
            // Keyed by whom it follows, so that following twice writes the same record again.
            follow -> account.putRecord(
                token,
                FOLLOW_COLLECTION,
                subject,
                buildJsonObject {
                    put("\$type", FOLLOW_COLLECTION)
                    put("createdAt", clock.instant().truncatedTo(ChronoUnit.MILLIS).toString())
                },
            ).map { }
            // The index may not have read a follow made moments ago: its key is known all the same.
            else -> account.deleteRecord(token, existing ?: "at://$me/$FOLLOW_COLLECTION/$subject")
        }
    }

    /** The signed-in person's DID, [login]'s, and the AT URI of the one's follow of the other (null when there's none). */
    private suspend fun follow(token: String, login: String): ForgeResult<Triple<String, String, String?>> {
        val me = when (val did = account.did(token)) {
            is ForgeResult.Failure -> return did
            is ForgeResult.Success -> did.value
        }
        val subject = when (val did = identity.did(login)) {
            is ForgeResult.Failure -> return did
            is ForgeResult.Success -> did.value
        }
        val (actor, followed) = when (val dids = asDid(me).zip(asDid(subject))) {
            is ForgeResult.Failure -> return dids
            is ForgeResult.Success -> dids.value
        }
        return when (val uri = xrpc.index.getFollow(GetFollowQueryParams(actor, followed)).uriOrNone { it.uri.atUri }) {
            is ForgeResult.Failure -> uri
            is ForgeResult.Success -> ForgeResult.Success(Triple(me, subject, uri.value))
        }
    }
}

/** What `getStar` and `getFollow` answer: the record's URI, or `…NotFound` when there's none, which is no failure. */
private inline fun <T : Any> AtpResponse<T>.uriOrNone(uri: (T) -> String): ForgeResult<String?> = when (val result = toResult(uri)) {
    is ForgeResult.Success -> result
    is ForgeResult.Failure -> if ((result.error as? ForgeError.Http)?.status == 404) ForgeResult.Success(null) else result
}

private fun <A, B> ForgeResult<A>.zip(other: ForgeResult<B>): ForgeResult<Pair<A, B>> = when (this) {
    is ForgeResult.Failure -> this
    is ForgeResult.Success -> when (other) {
        is ForgeResult.Failure -> other
        is ForgeResult.Success -> ForgeResult.Success(value to other.value)
    }
}
