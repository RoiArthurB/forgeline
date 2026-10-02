package fr.arthurbrugiere.forgeline.core.data.feed

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import fr.arthurbrugiere.forgeline.core.model.FeedAction
import fr.arthurbrugiere.forgeline.core.model.FeedEvent
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueAction
import fr.arthurbrugiere.forgeline.core.model.PullRequestAction
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewState
import kotlinx.coroutines.flow.Flow
import androidx.room.Index
import java.time.Instant

/** Rows of starred-repository activity are kept under the account's id with this prefix, apart from its own events. */
internal const val STARRED_PREFIX = "starred:"

/** A Feed event; its action is spread over a few generic columns (see [toEntity]). */
@Entity(
    tableName = "feed_events",
    primaryKeys = ["accountId", "id"],
    indices = [
        Index(value = ["createdAtMillis", "accountId", "id"], orders = [Index.Order.DESC, Index.Order.ASC, Index.Order.DESC]),
        Index(value = ["accountId", "createdAtMillis"]),
    ],
)
data class FeedEventEntity(
    val accountId: String,
    val id: String,
    val actorLogin: String,
    val actorAvatarUrl: String?,
    val host: String,
    val owner: String,
    val name: String,
    val createdAtMillis: Long,
    val action: String,
    val number: Int?,
    val text: String?,
    val detail: String?,
    val flag: Boolean,
)

@Entity(tableName = "feed_sync")
data class FeedSyncEntity(
    @PrimaryKey val accountId: String,
    val lastModified: String?,
    val pollIntervalSeconds: Int?,
    val syncedAtMillis: Long,
    val nextPage: Int?,
)

@Dao
interface FeedDao {
    @Query("SELECT * FROM feed_events ORDER BY createdAtMillis DESC, accountId, id DESC")
    fun observeAll(): Flow<List<FeedEventEntity>>

    /** When the account's oldest loaded event happened. */
    @Query("SELECT MIN(createdAtMillis) FROM feed_events WHERE accountId = :accountId")
    suspend fun oldest(accountId: String): Long?

    @Query("DELETE FROM feed_events WHERE accountId = :accountId")
    suspend fun clear(accountId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entities: List<FeedEventEntity>)

    @Transaction
    suspend fun replace(accountId: String, entities: List<FeedEventEntity>) {
        clear(accountId)
        insert(entities)
    }

    @Query("DELETE FROM feed_events WHERE accountId NOT IN (:accountIds)")
    suspend fun keepEventsOf(accountIds: List<String>)

    @Query("DELETE FROM feed_sync WHERE accountId NOT IN (:accountIds)")
    suspend fun keepSyncsOf(accountIds: List<String>)

    /** Deletes every row kept under an id other than [accountIds]: those of accounts that signed out. */
    @Transaction
    suspend fun keepOnly(accountIds: List<String>) {
        keepEventsOf(accountIds)
        keepSyncsOf(accountIds)
    }

    @Query("SELECT * FROM feed_sync")
    fun observeSyncs(): Flow<List<FeedSyncEntity>>

    @Query("SELECT * FROM feed_sync WHERE accountId = :accountId")
    suspend fun sync(accountId: String): FeedSyncEntity?

    @Upsert
    suspend fun upsertSync(entity: FeedSyncEntity)
}

internal fun FeedEvent.toEntity(accountId: String): FeedEventEntity {
    fun entity(action: String, number: Int? = null, text: String? = null, detail: String? = null, flag: Boolean = false) = FeedEventEntity(
        accountId, id, actor.login, actor.avatarUrl, repo.forge.host, repo.owner, repo.name, createdAt.toEpochMilli(), action, number, text, detail, flag,
    )
    return when (val a = action) {
        FeedAction.Starred -> entity("starred")
        is FeedAction.Forked -> entity("forked", text = a.fork.key)
        is FeedAction.CreatedRepo -> entity("created_repo", text = a.description)
        FeedAction.MadePublic -> entity("made_public")
        is FeedAction.Released -> entity("released", text = a.tag, detail = a.name, flag = a.prerelease)
        is FeedAction.Announced -> entity("announced", a.number, a.title)
        is FeedAction.Issue -> entity("issue", a.number, a.title, a.action.name)
        is FeedAction.PullRequest -> entity("pull_request", a.number, text = a.title, detail = a.action.name)
        is FeedAction.Commented -> entity("commented", a.number, a.title, flag = a.isPullRequest)
        is FeedAction.Reviewed -> entity("reviewed", a.number, detail = a.state.name)
        is FeedAction.Pushed -> entity("pushed", text = a.branch)
        is FeedAction.Branch -> entity("branch", text = a.name, detail = if (a.deleted) "deleted" else "created", flag = a.isTag)
        is FeedAction.AddedMember -> entity("member", text = a.login)
    }
}

/** Null for rows this version can't read, e.g. written by a newer one before a downgrade. */
internal fun FeedEventEntity.toModel(): FeedEvent? {
    val action: FeedAction = when (action) {
        "starred" -> FeedAction.Starred
        "forked" -> text?.let(RepoId::fromKey)?.let { FeedAction.Forked(it) }
        "created_repo" -> FeedAction.CreatedRepo(text)
        "made_public" -> FeedAction.MadePublic
        "released" -> text?.let { FeedAction.Released(it, detail, flag) }
        "announced" -> number?.let { FeedAction.Announced(it, text.orEmpty()) }
        "issue" -> number?.let { n ->
            IssueAction.entries.firstOrNull { it.name == detail }?.let { FeedAction.Issue(it, n, text.orEmpty()) }
        }
        "pull_request" -> number?.let { n -> PullRequestAction.entries.firstOrNull { it.name == detail }?.let { FeedAction.PullRequest(it, n, text) } }
        "commented" -> number?.let { FeedAction.Commented(it, text, flag) }
        "reviewed" -> number?.let { n -> ReviewState.entries.firstOrNull { it.name == detail }?.let { FeedAction.Reviewed(n, it) } }
        "pushed" -> text?.let { FeedAction.Pushed(it) }
        "branch" -> text?.let { FeedAction.Branch(it, isTag = flag, deleted = detail == "deleted") }
        "member" -> text?.let { FeedAction.AddedMember(it) }
        else -> null
    } ?: return null
    return FeedEvent(id, ForgeUser(actorLogin, null, actorAvatarUrl), RepoId(owner, name, ForgeInstance.of(host)), action, Instant.ofEpochMilli(createdAtMillis))
}
