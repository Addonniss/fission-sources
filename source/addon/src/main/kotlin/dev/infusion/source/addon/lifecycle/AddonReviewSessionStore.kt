package dev.infusion.source.addon.lifecycle

import dev.infusion.core.database.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AddonReviewSessionStore(
    private val clock: Clock = Clock.SYSTEM,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxSessions: Int = DEFAULT_MAX_SESSIONS,
) {

    data class Session(
        val record: AddonReviewRecord,
        val createdAtMs: Long,
    )

    private val sessions = ConcurrentHashMap<String, Session>()

    fun create(record: AddonReviewRecord): String {
        purgeExpired()
        val token = UUID.randomUUID().toString()
        sessions[token] = Session(record = record, createdAtMs = clock.nowMs())
        if (sessions.size > maxSessions) {

            sessions.minByOrNull { it.value.createdAtMs }?.let { sessions.remove(it.key) }
        }
        return token
    }

    fun consume(token: String): AddonReviewRecord? {
        purgeExpired()
        val session = sessions.remove(token) ?: return null
        if (isExpired(session)) return null
        return session.record
    }

    fun peek(token: String): AddonReviewRecord? {
        purgeExpired()
        val session = sessions[token] ?: return null
        if (isExpired(session)) return null
        return session.record
    }

    fun size(): Int {
        purgeExpired()
        return sessions.size
    }

    fun clear() = sessions.clear()

    private fun purgeExpired() {
        val now = clock.nowMs()
        sessions.entries.removeIf { isExpired(it.value, now) }
    }

    private fun isExpired(session: Session, now: Long = clock.nowMs()): Boolean =
        now - session.createdAtMs >= ttlMs

    companion object {
        const val DEFAULT_TTL_MS = 10 * 60_000L
        const val DEFAULT_MAX_SESSIONS = 4
    }
}