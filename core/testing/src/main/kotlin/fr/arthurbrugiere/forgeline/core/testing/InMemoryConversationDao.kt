package fr.arthurbrugiere.forgeline.core.testing

import fr.arthurbrugiere.forgeline.core.data.issue.ConversationDao
import fr.arthurbrugiere.forgeline.core.data.issue.ConversationEntity

/** Keeps conversations in a map, for tests that don't need a database. */
class InMemoryConversationDao : ConversationDao {
    val entities = mutableMapOf<String, ConversationEntity>()

    private fun key(host: String, owner: String, name: String, number: Int, isPullRequest: Boolean) = "$host/$owner/$name#$number:$isPullRequest"

    override suspend fun get(host: String, owner: String, name: String, number: Int, isPullRequest: Boolean) = entities[key(host, owner, name, number, isPullRequest)]

    override suspend fun upsert(entity: ConversationEntity) {
        entities[key(entity.host, entity.owner, entity.name, entity.number, entity.isPullRequest)] = entity
    }

    override suspend fun clear(host: String) {
        entities.values.removeAll { it.host == host }
    }

    override suspend fun delete(host: String, owner: String, name: String, number: Int, isPullRequest: Boolean) {
        entities.remove(key(host, owner, name, number, isPullRequest))
    }

    override suspend fun prune(keep: Int) {
        entities.values.sortedByDescending { it.viewedAtMillis }.drop(keep).forEach { entities.remove(key(it.host, it.owner, it.name, it.number, it.isPullRequest)) }
    }
}
