package fr.arthurbrugiere.forgeline.core.data.draft

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.arthurbrugiere.forgeline.core.data.di.BackgroundScope
import fr.arthurbrugiere.forgeline.core.data.di.DraftsDataStore
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Something being written and not sent yet: a comment ([title] empty), or an issue with its title. */
data class Draft(val title: String = "", val body: String = "") {
    val isEmpty: Boolean get() = title.isEmpty() && body.isEmpty()
}

/**
 * What the reader was writing, kept until it is sent: across screens, and across launches. Each draft has a key that
 * starts with its kind and its forge ([commentDraftKey], [issueDraftKey]).
 */
interface DraftStore {
    /** The draft under [key] if it is known without waiting: written or read since the app started. */
    fun peek(key: String): Draft?

    /** The draft under [key], from disk if need be; null when there is none. */
    suspend fun read(key: String): Draft?

    /** Keeps [draft] under [key], at once here and soon on disk; an empty or null one is forgotten. */
    fun write(key: String, draft: Draft?)

    /** Forgets every draft written for [forge]: what was written with an account goes when it signs out. */
    suspend fun forget(forge: ForgeInstance)
}

/** The comment being written in the conversation [ref]. GitLab's merge requests are told from its issues. */
fun commentDraftKey(ref: IssueRef): String {
    val mark = if (ref.repo.forge.type.numbersMergeRequestsApart && ref.isPullRequest == true) "!" else "#"
    return "comment|${ref.repo.forge.host}|${ref.repo.fullName.lowercase()}$mark${ref.number}"
}

/** The issue being written for [repo], not opened yet. */
fun issueDraftKey(repo: RepoId): String = "issue|${repo.forge.host}|${repo.fullName.lowercase()}"

private fun String.isOf(forge: ForgeInstance): Boolean = substringAfter('|').substringBefore('|') == forge.host

/** Drafts for as long as the object lives: tests, and wherever nothing needs to outlast the app. */
class InMemoryDraftStore : DraftStore {
    private val drafts = ConcurrentHashMap<String, Draft>()

    override fun peek(key: String): Draft? = drafts[key]

    override suspend fun read(key: String): Draft? = drafts[key]

    override fun write(key: String, draft: Draft?) {
        if (draft == null || draft.isEmpty) drafts.remove(key) else drafts[key] = draft
    }

    override suspend fun forget(forge: ForgeInstance) {
        drafts.keys.removeAll { it.isOf(forge) }
    }
}

/**
 * Drafts on disk, one preference each. Writing never waits: every keystroke lands here, so the latest text of each
 * draft is kept in memory and one writer saves whatever is latest when its turn comes. Text typed fast is saved a few
 * times, not once per letter, and never an older text over a newer one.
 */
@Singleton
class DataStoreDraftStore @Inject constructor(
    @param:DraftsDataStore private val dataStore: DataStore<Preferences>,
    @param:BackgroundScope private val scope: CoroutineScope,
    private val clock: Clock,
) : DraftStore {
    /** What this session wrote or read; [GONE] for a draft known to be none. */
    private val known = ConcurrentHashMap<String, Draft>()

    /** Keys whose latest text ([known]) is still to be saved. */
    private val unsaved = ConcurrentHashMap.newKeySet<String>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch { for (ignored in wake) save() }
    }

    override fun peek(key: String): Draft? = known[key]?.takeUnless { it === GONE }

    override suspend fun read(key: String): Draft? {
        known[key]?.let { return it.takeUnless { draft -> draft === GONE } }
        val stored = dataStore.data.first()[stringPreferencesKey(key)]?.let(::decode)
        // Written meanwhile: that is the newer one.
        return (known.putIfAbsent(key, stored ?: GONE) ?: stored)?.takeUnless { it === GONE }
    }

    override fun write(key: String, draft: Draft?) {
        known[key] = if (draft == null || draft.isEmpty) GONE else draft
        unsaved += key
        wake.trySend(Unit)
    }

    private suspend fun save() {
        val keys = unsaved.toList()
        if (keys.isEmpty()) return
        // Taken off the list before they are read: text written while this saves puts its key back for the next turn.
        unsaved.removeAll(keys.toSet())
        dataStore.edit { preferences ->
            for (key in keys) {
                val draft = known[key]
                if (draft == null || draft === GONE) {
                    preferences.remove(stringPreferencesKey(key))
                } else {
                    preferences[stringPreferencesKey(key)] = json.encodeToString(StoredDraft(draft.title, draft.body, clock.millis()))
                }
            }
            // Drafts never sent would pile up for good: the ones written longest ago give way.
            val all = preferences.asMap().keys.mapNotNull { key -> (preferences[key] as? String)?.let(::storedAt)?.let { key to it } }
            all.sortedByDescending { it.second }.drop(MAX_DRAFTS).forEach { (key, _) ->
                preferences.remove(key)
                known.remove(key.name)
            }
        }
    }

    override suspend fun forget(forge: ForgeInstance) {
        known.keys.removeAll { it.isOf(forge) }
        unsaved.removeAll { it.isOf(forge) }
        dataStore.edit { preferences -> preferences.asMap().keys.filter { it.name.isOf(forge) }.forEach { preferences.remove(it) } }
    }

    @Serializable
    private data class StoredDraft(val title: String = "", val body: String = "", val at: Long = 0)

    private fun decode(text: String): Draft? =
        runCatching { json.decodeFromString<StoredDraft>(text) }.getOrNull()?.let { Draft(it.title, it.body) }?.takeUnless { it.isEmpty }

    private fun storedAt(text: String): Long? = runCatching { json.decodeFromString<StoredDraft>(text).at }.getOrNull()

    companion object {
        const val MAX_DRAFTS = 50

        private val GONE = Draft()
        private val json = Json { ignoreUnknownKeys = true }
    }
}
