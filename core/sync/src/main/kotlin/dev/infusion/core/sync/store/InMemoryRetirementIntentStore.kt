package dev.infusion.core.sync.store

import dev.infusion.core.domain.sync.RetirementIntentStore

class InMemoryRetirementIntentStore : RetirementIntentStore {

    private val intents = linkedMapOf<String, String>()

    @Synchronized
    override fun save(sourceId: String, payload: String, createdAtMs: Long) {
        intents[sourceId] = payload
    }

    @Synchronized
    override fun payloadFor(sourceId: String): String? = intents[sourceId]

    @Synchronized
    override fun pendingSourceIds(): List<String> = intents.keys.toList()

    @Synchronized
    override fun hasPending(): Boolean = intents.isNotEmpty()

    @Synchronized
    override fun clear(sourceId: String) {
        intents.remove(sourceId)
    }
}
