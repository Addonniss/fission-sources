package dev.infusion.core.model

import java.util.UUID

@JvmInline
value class TitleId(val value: UUID) {
    override fun toString(): String = "TitleId($value)"
    companion object {
        fun random(): TitleId = TitleId(UUID.randomUUID())
        fun fromString(raw: String): TitleId = TitleId(UUID.fromString(raw))
    }
}

@JvmInline
value class EditionId(val value: UUID) {
    override fun toString(): String = "EditionId($value)"
    companion object {
        fun random(): EditionId = EditionId(UUID.randomUUID())
        fun fromString(raw: String): EditionId = EditionId(UUID.fromString(raw))
    }
}

@JvmInline
value class SourceId(val value: UUID) {
    override fun toString(): String = "SourceId($value)"
    companion object {
        fun random(): SourceId = SourceId(UUID.randomUUID())
        fun fromString(raw: String): SourceId = SourceId(UUID.fromString(raw))
    }
}

@JvmInline
value class EventId(val value: UUID) {
    override fun toString(): String = "EventId($value)"
    companion object {
        fun random(): EventId = EventId(UUID.randomUUID())
        fun fromString(raw: String): EventId = EventId(UUID.fromString(raw))
    }
}

@JvmInline
value class SessionId(val value: UUID) {
    override fun toString(): String = "SessionId($value)"
    companion object {
        fun random(): SessionId = SessionId(UUID.randomUUID())
        fun fromString(raw: String): SessionId = SessionId(UUID.fromString(raw))
    }
}

@JvmInline
value class CandidateId(val value: UUID) {
    override fun toString(): String = "CandidateId($value)"
    companion object {
        fun random(): CandidateId = CandidateId(UUID.randomUUID())
        fun fromString(raw: String): CandidateId = CandidateId(UUID.fromString(raw))
    }
}

@JvmInline
value class AvailabilityId(val value: UUID) {
    override fun toString(): String = "AvailabilityId($value)"
    companion object {
        fun random(): AvailabilityId = AvailabilityId(UUID.randomUUID())
        fun fromString(raw: String): AvailabilityId = AvailabilityId(UUID.fromString(raw))
    }
}

@JvmInline
value class MappingId(val value: UUID) {
    override fun toString(): String = "MappingId($value)"
    companion object {
        fun random(): MappingId = MappingId(UUID.randomUUID())
        fun fromString(raw: String): MappingId = MappingId(UUID.fromString(raw))
    }
}

@JvmInline
value class OutboxEntryId(val value: UUID) {
    override fun toString(): String = "OutboxEntryId($value)"
    companion object {
        fun random(): OutboxEntryId = OutboxEntryId(UUID.randomUUID())
        fun fromString(raw: String): OutboxEntryId = OutboxEntryId(UUID.fromString(raw))
    }
}

@JvmInline
value class PlaybackAttemptId(val value: UUID) {
    override fun toString(): String = "PlaybackAttemptId($value)"
    companion object {
        fun random(): PlaybackAttemptId = PlaybackAttemptId(UUID.randomUUID())
        fun fromString(raw: String): PlaybackAttemptId = PlaybackAttemptId(UUID.fromString(raw))
    }
}

@JvmInline
value class CandidateFamilyId(val digest: String) {
    init {
        require(digest.matches(HEX_64)) { "CandidateFamilyId must be a 64-char lowercase hex SHA-256 digest" }
    }

    override fun toString(): String = "CandidateFamilyId($digest)"

    private companion object {
        val HEX_64 = Regex("[0-9a-f]{64}")
    }
}
