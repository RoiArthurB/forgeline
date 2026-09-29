package fr.arthurbrugiere.forgeline.core.testing

import fr.arthurbrugiere.forgeline.core.data.issue.ConversationDao
import fr.arthurbrugiere.forgeline.core.data.issue.ConversationEntity

/** Keeps conversations in a map, for tests that don't need a database. */
class InMemoryConversationDao : ConversationDao {
    val entities = mutableMapOf<Triple<String, String, Int>, ConversationEntity>()

    override suspend fun get(owner: String, name: String, number: Int) = entities[Triple(owner, name, number)]

    override suspend fun upsert(entity: ConversationEntity) {
        entities[Triple(entity.owner, entity.name, entity.number)] = entity
    }

    override suspend fun prune(keep: Int) {
        entities.values.sortedByDescending { it.viewedAtMillis }.drop(keep).forEach { entities.remove(Triple(it.owner, it.name, it.number)) }
    }
}
