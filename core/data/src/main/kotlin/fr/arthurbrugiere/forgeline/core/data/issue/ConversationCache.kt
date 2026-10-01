package fr.arthurbrugiere.forgeline.core.data.issue

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueDetails
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.PullRequestInfo
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewState
import fr.arthurbrugiere.forgeline.core.model.StateChange
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import fr.arthurbrugiere.forgeline.core.model.TimelinePage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

/** The last loaded state of a conversation: the issue or pull request and its first timeline page, as JSON. */
@Entity(tableName = "conversations", primaryKeys = ["host", "owner", "name", "number"])
data class ConversationEntity(
    val host: String,
    val owner: String,
    val name: String,
    val number: Int,
    val issue: String?,
    val firstPage: String?,
    /** When it was last loaded, by opening it or ahead of time. */
    val viewedAtMillis: Long,
)

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE host = :host AND owner = :owner AND name = :name AND number = :number")
    suspend fun get(host: String, owner: String, name: String, number: Int): ConversationEntity?

    @Upsert
    suspend fun upsert(entity: ConversationEntity)

    @Query("DELETE FROM conversations WHERE host = :host")
    suspend fun clear(host: String)

    /** Keeps the [keep] most recently viewed conversations. */
    @Query("DELETE FROM conversations WHERE viewedAtMillis < (SELECT MIN(viewedAtMillis) FROM (SELECT viewedAtMillis FROM conversations ORDER BY viewedAtMillis DESC LIMIT :keep))")
    suspend fun prune(keep: Int)
}

private val json = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "kind"
}

internal fun IssueDetails.encode(): String = json.encodeToString(StoredIssue.of(this))

internal fun TimelinePage.encode(): String = json.encodeToString(StoredPage(items.map(StoredItem::of), nextPage))

/** Null when stored by an app version this one can't read: the network answer replaces it. */
internal fun decodeIssue(text: String): IssueDetails? = runCatching { json.decodeFromString<StoredIssue>(text).toModel() }.getOrNull()

internal fun decodePage(text: String): TimelinePage? = runCatching {
    json.decodeFromString<StoredPage>(text).run { TimelinePage(items.map { it.toModel() }, nextPage) }
}.getOrNull()

@Serializable
private data class StoredUser(val login: String, val name: String?, val avatarUrl: String?) {
    fun toModel() = ForgeUser(login, name, avatarUrl)

    companion object {
        fun of(user: ForgeUser?) = user?.let { StoredUser(it.login, it.name, it.avatarUrl) }
    }
}

@Serializable
private data class StoredLabel(val name: String, val color: String?)

@Serializable
private data class StoredPull(
    val isDraft: Boolean,
    val isMerged: Boolean,
    val baseRef: String,
    val headRef: String,
    val additions: Int,
    val deletions: Int,
    val changedFiles: Int,
    val commits: Int,
)

@Serializable
private data class StoredIssue(
    val host: String = ForgeInstance.GitHub.host,
    val owner: String,
    val name: String,
    val number: Int,
    val title: String,
    val body: String?,
    val state: IssueState,
    val stateReason: String?,
    val author: StoredUser?,
    val labels: List<StoredLabel>,
    val createdAt: Long,
    val closedAt: Long?,
    val comments: Int,
    val reactions: Map<Reaction, Int>,
    val pull: StoredPull?,
) {
    fun toModel() = IssueDetails(
        ref = IssueRef(RepoId(owner, name, ForgeInstance.of(host)), number),
        title = title,
        body = body,
        state = state,
        stateReason = stateReason,
        author = author?.toModel(),
        labels = labels.map { Label(it.name, it.color) },
        createdAt = Instant.ofEpochMilli(createdAt),
        closedAt = closedAt?.let(Instant::ofEpochMilli),
        comments = comments,
        reactions = reactions,
        pullRequest = pull?.run { PullRequestInfo(isDraft, isMerged, baseRef, headRef, additions, deletions, changedFiles, commits) },
    )

    companion object {
        fun of(issue: IssueDetails) = with(issue) {
            StoredIssue(
                ref.repo.forge.host, ref.repo.owner, ref.repo.name, ref.number, title, body, state, stateReason, StoredUser.of(author),
                labels.map { StoredLabel(it.name, it.color) }, createdAt.toEpochMilli(), closedAt?.toEpochMilli(), comments, reactions,
                pullRequest?.run { StoredPull(isDraft, isMerged, baseRef, headRef, additions, deletions, changedFiles, commits) },
            )
        }
    }
}

@Serializable
private data class StoredPage(val items: List<StoredItem>, val nextPage: Int?)

@Serializable
private sealed interface StoredItem {
    fun toModel(): TimelineItem

    @Serializable
    @SerialName("comment")
    data class Comment(val id: Long, val author: StoredUser?, val body: String, val at: Long, val reactions: Map<Reaction, Int>) : StoredItem {
        override fun toModel() = TimelineItem.Comment(id, author?.toModel(), body, Instant.ofEpochMilli(at), reactions)
    }

    @Serializable
    @SerialName("review")
    data class Review(val id: Long, val author: StoredUser?, val state: ReviewState, val body: String?, val at: Long?) : StoredItem {
        override fun toModel() = TimelineItem.Review(id, author?.toModel(), state, body, at?.let(Instant::ofEpochMilli))
    }

    @Serializable
    @SerialName("state")
    data class StateChanged(val change: StateChange, val actor: StoredUser?, val stateReason: String?, val at: Long) : StoredItem {
        override fun toModel() = TimelineItem.StateChanged(change, actor?.toModel(), stateReason, Instant.ofEpochMilli(at))
    }

    @Serializable
    @SerialName("label")
    data class Labeled(val added: Boolean, val label: StoredLabel, val actor: StoredUser?, val at: Long) : StoredItem {
        override fun toModel() = TimelineItem.Labeled(added, Label(label.name, label.color), actor?.toModel(), Instant.ofEpochMilli(at))
    }

    @Serializable
    @SerialName("rename")
    data class Renamed(val from: String, val to: String, val actor: StoredUser?, val at: Long) : StoredItem {
        override fun toModel() = TimelineItem.Renamed(from, to, actor?.toModel(), Instant.ofEpochMilli(at))
    }

    @Serializable
    @SerialName("reference")
    data class CrossReferenced(
        val host: String = ForgeInstance.GitHub.host,
        val owner: String,
        val name: String,
        val number: Int,
        val title: String,
        val isPullRequest: Boolean,
        val actor: StoredUser?,
        val at: Long,
    ) : StoredItem {
        override fun toModel() =
            TimelineItem.CrossReferenced(IssueRef(RepoId(owner, name, ForgeInstance.of(host)), number), title, isPullRequest, actor?.toModel(), Instant.ofEpochMilli(at))
    }

    @Serializable
    @SerialName("commit")
    data class Committed(val sha: String, val message: String, val authorName: String?, val at: Long?) : StoredItem {
        override fun toModel() = TimelineItem.Committed(sha, message, authorName, at?.let(Instant::ofEpochMilli))
    }

    companion object {
        fun of(item: TimelineItem): StoredItem = when (item) {
            is TimelineItem.Comment -> Comment(item.id, StoredUser.of(item.author), item.body, item.createdAt.toEpochMilli(), item.reactions)
            is TimelineItem.Review -> Review(item.id, StoredUser.of(item.author), item.state, item.body, item.createdAt?.toEpochMilli())
            is TimelineItem.StateChanged -> StateChanged(item.change, StoredUser.of(item.actor), item.stateReason, item.createdAt.toEpochMilli())
            is TimelineItem.Labeled -> Labeled(item.added, StoredLabel(item.label.name, item.label.color), StoredUser.of(item.actor), item.createdAt.toEpochMilli())
            is TimelineItem.Renamed -> Renamed(item.from, item.to, StoredUser.of(item.actor), item.createdAt.toEpochMilli())
            is TimelineItem.CrossReferenced -> CrossReferenced(
                item.source.repo.forge.host, item.source.repo.owner, item.source.repo.name, item.source.number, item.sourceTitle, item.sourceIsPullRequest,
                StoredUser.of(item.actor), item.createdAt.toEpochMilli(),
            )
            is TimelineItem.Committed -> Committed(item.sha, item.message, item.authorName, item.createdAt?.toEpochMilli())
        }
    }
}
