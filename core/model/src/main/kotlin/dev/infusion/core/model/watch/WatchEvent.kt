package dev.infusion.core.model.watch

import dev.infusion.core.model.EventId
import dev.infusion.core.model.SessionId
import java.time.Instant

sealed class WatchEvent {

    abstract val eventId: EventId
    abstract val watchKey: WatchKey
    abstract val kind: Kind
    abstract val occurredAt: Instant
    abstract val sessionId: SessionId?
    abstract val positionMs: Long?
    abstract val durationMs: Long?

    data class Local(
        override val eventId: EventId,
        override val watchKey: WatchKey,
        override val kind: Kind,
        override val occurredAt: Instant,
        override val sessionId: SessionId? = null,
        override val positionMs: Long? = null,
        override val durationMs: Long? = null,
        val anchor: ResumeAnchor? = null,
    ) : WatchEvent() {
        init {
            validate(kind, positionMs, durationMs)
        }
    }

    data class Imported(
        override val eventId: EventId,
        override val watchKey: WatchKey,
        override val kind: Kind,
        override val occurredAt: Instant,
        val connectorId: String,
        val observedAt: Instant,
        override val sessionId: SessionId? = null,
        override val positionMs: Long? = null,
        override val durationMs: Long? = null,
    ) : WatchEvent() {
        init {
            require(kind == Kind.PROGRESS || kind == Kind.COMPLETED) {
                "imported observations may only carry PROGRESS or COMPLETED, was $kind"
            }
            require(connectorId.isNotBlank()) { "connectorId must not be blank" }
            validate(kind, positionMs, durationMs)
        }
    }

    enum class Kind {

        START,

        PROGRESS,

        PAUSE,

        COMPLETED,

        EXPLICIT_WATCHED,

        EXPLICIT_RESET,

        RESUME_RESET,

        CANCELLED,

        SOURCE_FAILURE,

        HANDOFF,
    }

    companion object {
        internal fun validate(kind: Kind, positionMs: Long?, durationMs: Long?) {
            require(positionMs == null || positionMs >= 0) { "positionMs must be >= 0" }
            require(durationMs == null || durationMs > 0) { "durationMs must be positive when known" }
            when (kind) {
                Kind.PROGRESS, Kind.PAUSE ->
                    require(positionMs != null) { "$kind requires a position" }
                Kind.EXPLICIT_RESET ->
                    require(positionMs == null || positionMs == 0L) { "EXPLICIT_RESET position must be 0" }
                Kind.RESUME_RESET ->
                    require(positionMs == null || positionMs == 0L) { "RESUME_RESET position must be 0" }
                else -> Unit
            }
        }
    }
}
