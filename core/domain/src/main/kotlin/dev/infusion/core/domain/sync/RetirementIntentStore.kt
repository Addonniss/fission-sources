package dev.infusion.core.domain.sync

interface RetirementIntentStore {

    fun save(sourceId: String, payload: String, createdAtMs: Long)

    fun payloadFor(sourceId: String): String?

    fun pendingSourceIds(): List<String>

    fun hasPending(): Boolean

    fun clear(sourceId: String)
}
