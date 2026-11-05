package dev.infusion.core.database.mapper

import dev.infusion.core.database.entity.CheckpointEntity
import dev.infusion.core.database.entity.IdentityMappingEntity
import dev.infusion.core.database.entity.OutboxEntity
import dev.infusion.core.database.entity.WatchEventEntity
import dev.infusion.core.database.entity.WatchStateEntity
import dev.infusion.core.database.model.EventOrigin
import dev.infusion.core.database.watch.WatchKeyCodec
import dev.infusion.core.model.CandidateFamilyId
import dev.infusion.core.model.CandidateId
import dev.infusion.core.model.EventId
import dev.infusion.core.model.OutboxEntryId
import dev.infusion.core.model.MappingId
import dev.infusion.core.model.EditionId
import dev.infusion.core.model.NativeItemId
import dev.infusion.core.model.SessionId
import dev.infusion.core.model.SourceId
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TypedExternalId
import dev.infusion.core.model.identity.IdentityMapping
import dev.infusion.core.model.identity.MappingOrigin
import dev.infusion.core.model.identity.MappingStatus
import dev.infusion.core.model.sync.ConnectorCheckpoint
import dev.infusion.core.model.sync.OutboxEntry
import dev.infusion.core.model.sync.OutboxPayload
import dev.infusion.core.model.sync.OutboxPayloadCodec
import dev.infusion.core.model.sync.OutboxState
import dev.infusion.core.model.sync.OutboxWriteKind
import dev.infusion.core.model.watch.ConnectorObservation
import dev.infusion.core.model.watch.ConflictKind
import dev.infusion.core.model.watch.ResumeAnchor
import dev.infusion.core.model.watch.WatchConflict
import dev.infusion.core.model.watch.WatchEvent
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.core.model.watch.WatchState
import dev.infusion.core.model.watch.WatchStatus
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

object WatchEventMapper {

    fun toEntity(profileId: String, event: WatchEvent, receivedAtMs: Long): WatchEventEntity {
        val anchor = (event as? WatchEvent.Local)?.anchor
        return WatchEventEntity(
            eventId = event.eventId.value.toString(),
            profileId = profileId,
            watchKey = WatchKeyCodec.encode(event.watchKey),
            kind = event.kind,
            origin = if (event is WatchEvent.Local) EventOrigin.LOCAL else EventOrigin.IMPORT,
            positionMs = event.positionMs,
            durationMs = event.durationMs,
            occurredAtMs = event.occurredAt.toEpochMilli(),
            receivedAtMs = receivedAtMs,
            observedAtMs = (event as? WatchEvent.Imported)?.observedAt?.toEpochMilli(),
            connectorId = (event as? WatchEvent.Imported)?.connectorId,
            connectorEventId = null,
            sessionId = event.sessionId?.value?.toString(),
            anchorSourceId = anchor?.nativeItemId?.sourceId?.value?.toString(),
            anchorNativeId = anchor?.nativeItemId?.nativeId,
            anchorFamilyId = anchor?.familyId?.digest,
            anchorVersionId = anchor?.versionId,
            anchorPositionMs = anchor?.positionMs,
            anchorAnchoredAtMs = anchor?.anchoredAt?.toEpochMilli(),
        )
    }

    fun toCanonical(entity: WatchEventEntity): WatchEvent {
        val key = WatchKeyCodec.decode(entity.watchKey)
        val sessionId = entity.sessionId?.let { SessionId.fromString(it) }
        return when (entity.origin) {
            EventOrigin.LOCAL -> WatchEvent.Local(
                eventId = EventId.fromString(entity.eventId),
                watchKey = key,
                kind = entity.kind,
                occurredAt = Instant.ofEpochMilli(entity.occurredAtMs),
                sessionId = sessionId,
                positionMs = entity.positionMs,
                durationMs = entity.durationMs,
                anchor = entity.toResumeAnchor(),
            )
            EventOrigin.IMPORT -> WatchEvent.Imported(
                eventId = EventId.fromString(entity.eventId),
                watchKey = key,
                kind = entity.kind,
                occurredAt = Instant.ofEpochMilli(entity.occurredAtMs),
                connectorId = requireNotNull(entity.connectorId) { "imported event without connector_id" },
                observedAt = Instant.ofEpochMilli(requireNotNull(entity.observedAtMs) { "imported event without observed_at_ms" }),
                sessionId = sessionId,
                positionMs = entity.positionMs,
                durationMs = entity.durationMs,
            )
        }
    }

    fun WatchEventEntity.toResumeAnchor(): ResumeAnchor? = buildResumeAnchor(
        sourceId = anchorSourceId,
        nativeId = anchorNativeId,
        familyId = anchorFamilyId,
        versionId = anchorVersionId,
        positionMs = anchorPositionMs,
        anchoredAtMs = anchorAnchoredAtMs,
    )
}

internal fun buildResumeAnchor(
    sourceId: String?,
    nativeId: String?,
    familyId: String?,
    versionId: String?,
    positionMs: Long?,
    anchoredAtMs: Long?,
): ResumeAnchor? {
    val source = sourceId ?: return null
    val family = familyId ?: return null
    val position = positionMs ?: return null
    val anchoredAt = anchoredAtMs ?: return null
    return ResumeAnchor(
        nativeItemId = NativeItemId(SourceId.fromString(source), nativeId ?: ""),
        familyId = CandidateFamilyId(family),
        versionId = versionId,
        positionMs = position,
        anchoredAt = Instant.ofEpochMilli(anchoredAt),
    )
}

object WatchStateMapper {

    fun toEntity(profileId: String, state: WatchState, updatedAtMs: Long): WatchStateEntity {
        val anchor = state.anchor
        return WatchStateEntity(
            profileId = profileId,
            watchKey = WatchKeyCodec.encode(state.watchKey),
            status = state.status,
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            completionFraction = state.completionFraction,
            lastEventId = state.lastEventId?.value?.toString(),
            lastMeaningfulAtMs = state.lastMeaningfulAt?.toEpochMilli(),
            activeSessionId = state.activeSessionId?.value?.toString(),
            anchorSourceId = anchor?.nativeItemId?.sourceId?.value?.toString(),
            anchorNativeId = anchor?.nativeItemId?.nativeId,
            anchorFamilyId = anchor?.familyId?.digest,
            anchorVersionId = anchor?.versionId,
            anchorPositionMs = anchor?.positionMs,
            anchorAnchoredAtMs = anchor?.anchoredAt?.toEpochMilli(),
            conflictsJson = ConflictJson.encode(state.conflicts),
            connectorStatesJson = ConnectorObservationJson.encode(state.connectorStates),
            updatedAtMs = updatedAtMs,
        )
    }

    fun toCanonical(entity: WatchStateEntity): WatchState {
        val anchor = buildResumeAnchor(
            sourceId = entity.anchorSourceId,
            nativeId = entity.anchorNativeId,
            familyId = entity.anchorFamilyId,
            versionId = entity.anchorVersionId,
            positionMs = entity.anchorPositionMs,
            anchoredAtMs = entity.anchorAnchoredAtMs,
        )
        return WatchState(
            watchKey = WatchKeyCodec.decode(entity.watchKey),
            status = entity.status,
            positionMs = entity.positionMs,
            durationMs = entity.durationMs,
            completionFraction = entity.completionFraction,
            lastEventId = entity.lastEventId?.let { EventId.fromString(it) },
            lastMeaningfulAt = entity.lastMeaningfulAtMs?.let { Instant.ofEpochMilli(it) },
            activeSessionId = entity.activeSessionId?.let { SessionId.fromString(it) },
            anchor = anchor,
            conflicts = ConflictJson.decode(entity.conflictsJson),
            connectorStates = ConnectorObservationJson.decode(entity.connectorStatesJson),
        )
    }
}

object ConflictJson {
    fun encode(conflicts: List<WatchConflict>): String =
        JSONArray().apply {
            for (c in conflicts) {
                put(
                    JSONObject()
                        .put("key", c.key)
                        .put("kind", c.kind.name)
                        .putOpt("connectorId", c.connectorId)
                        .put("localPositionMs", c.localPositionMs)
                        .putOpt("remotePositionMs", c.remotePositionMs)
                        .put("observedAt", c.observedAt.toEpochMilli())
                        .put("description", c.description),
                )
            }
        }.toString()

    fun decode(raw: String): List<WatchConflict> {
        val array = JSONArray(raw)
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(
                    WatchConflict(
                        key = o.getString("key"),
                        kind = ConflictKind.valueOf(o.getString("kind")),
                        connectorId = o.optString("connectorId").takeIf { it.isNotEmpty() },
                        localPositionMs = o.getLong("localPositionMs"),
                        remotePositionMs = if (o.isNull("remotePositionMs")) null else o.getLong("remotePositionMs"),
                        observedAt = Instant.ofEpochMilli(o.getLong("observedAt")),
                        description = o.getString("description"),
                    ),
                )
            }
        }
    }
}

object ConnectorObservationJson {
    fun encode(states: Map<String, ConnectorObservation>): String =
        JSONObject().apply {
            for ((connectorId, obs) in states) {
                put(
                    connectorId,
                    JSONObject()
                        .put("status", obs.status.name)
                        .put("positionMs", obs.positionMs)
                        .putOpt("durationMs", obs.durationMs)
                        .put("observedAt", obs.observedAt.toEpochMilli()),
                )
            }
        }.toString()

    fun decode(raw: String): Map<String, ConnectorObservation> {
        val obj = JSONObject(raw)
        return buildMap {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val connectorId = keys.next()
                val o = obj.getJSONObject(connectorId)
                put(
                    connectorId,
                    ConnectorObservation(
                        connectorId = connectorId,
                        status = WatchStatus.valueOf(o.getString("status")),
                        positionMs = o.getLong("positionMs"),
                        durationMs = if (o.isNull("durationMs")) null else o.getLong("durationMs"),
                        observedAt = Instant.ofEpochMilli(o.getLong("observedAt")),
                    ),
                )
            }
        }
    }
}

object OutboxMapper {

    fun toEntity(profileId: String, entry: OutboxEntry): OutboxEntity {
        val discriminator = requireNotNull(OutboxWriteKind.discriminatorOf(entry.payload)) {
            "an outbox payload must have a discriminator"
        }
        val occurredAtMs = entry.payload.occurredAt().toEpochMilli()
        return when (val payload = entry.payload) {
            is OutboxPayload.WatchAction -> OutboxEntity(
                outboxId = entry.entryId.value.toString(),
                profileId = profileId,
                connectorId = entry.connectorId,
                idempotencyKey = entry.idempotencyKey,
                kind = discriminator,
                watchKey = WatchKeyCodec.encode(payload.watchKey),
                positionMs = payload.positionMs,
                durationMs = payload.durationMs,
                sessionId = payload.sessionId?.value?.toString(),
                occurredAtMs = occurredAtMs,
                state = entry.state,
                attemptCount = entry.attempts,
                createdAtMs = entry.createdAt.toEpochMilli(),
                nextAttemptAtMs = entry.nextAttemptAt?.toEpochMilli(),
                lastError = entry.lastError,
                ackedAtMs = null,
                payloadBlob = null,
            )
            is OutboxPayload.WatchlistWrite,
            is OutboxPayload.ResumeReset,
            is OutboxPayload.PersonalRating,
            is OutboxPayload.PositionUpdate,
            -> OutboxEntity(
                outboxId = entry.entryId.value.toString(),
                profileId = profileId,
                connectorId = entry.connectorId,
                idempotencyKey = entry.idempotencyKey,
                kind = discriminator,

                watchKey = "",
                positionMs = null,
                durationMs = null,
                sessionId = null,
                occurredAtMs = occurredAtMs,
                state = entry.state,
                attemptCount = entry.attempts,
                createdAtMs = entry.createdAt.toEpochMilli(),
                nextAttemptAtMs = entry.nextAttemptAt?.toEpochMilli(),
                lastError = entry.lastError,
                ackedAtMs = null,
                payloadBlob = OutboxPayloadCodec.encode(payload),
            )
        }
    }

    fun toCanonical(entity: OutboxEntity): OutboxEntry? {
        val payload: OutboxPayload = if (entity.payloadBlob != null) {
            OutboxPayloadCodec.decode(entity.kind, entity.payloadBlob) ?: return null
        } else {
            val kind = enumOrNull<WatchEvent.Kind>(entity.kind) ?: return null
            val watchKey = runCatching { WatchKeyCodec.decode(entity.watchKey) }.getOrNull() ?: return null
            OutboxPayload.WatchAction(
                watchKey = watchKey,
                kind = kind,
                positionMs = entity.positionMs,
                durationMs = entity.durationMs,
                sessionId = entity.sessionId?.let { SessionId.fromString(it) },
                occurredAt = Instant.ofEpochMilli(entity.occurredAtMs),
            )
        }
        return OutboxEntry(
            entryId = OutboxEntryId.fromString(entity.outboxId),
            connectorId = entity.connectorId,
            idempotencyKey = entity.idempotencyKey,
            payload = payload,
            state = entity.state,
            attempts = entity.attemptCount,
            createdAt = Instant.ofEpochMilli(entity.createdAtMs),
            nextAttemptAt = entity.nextAttemptAtMs?.let { Instant.ofEpochMilli(it) },
            lastError = entity.lastError,
        )
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
        enumValues<T>().firstOrNull { it.name == name }
}

private fun OutboxPayload.occurredAt(): Instant = when (this) {
    is OutboxPayload.WatchAction -> occurredAt
    is OutboxPayload.WatchlistWrite -> occurredAt
    is OutboxPayload.ResumeReset -> occurredAt
    is OutboxPayload.PersonalRating -> occurredAt
    is OutboxPayload.PositionUpdate -> occurredAt
}

object CheckpointMapper {
    fun toEntity(profileId: String, checkpoint: ConnectorCheckpoint): CheckpointEntity = CheckpointEntity(
        profileId = profileId,
        connectorId = checkpoint.connectorId,
        lastImportCursor = checkpoint.lastImportCursor,
        lastImportedAtMs = checkpoint.lastImportedAt?.toEpochMilli(),
        updatedAtMs = checkpoint.updatedAt.toEpochMilli(),
    )

    fun toCanonical(entity: CheckpointEntity): ConnectorCheckpoint = ConnectorCheckpoint(
        connectorId = entity.connectorId,
        lastImportCursor = entity.lastImportCursor,
        lastImportedAt = entity.lastImportedAtMs?.let { Instant.ofEpochMilli(it) },
        updatedAt = Instant.ofEpochMilli(entity.updatedAtMs),
    )
}

object IdentityMappingMapper {
    fun toEntity(mapping: IdentityMapping): IdentityMappingEntity = IdentityMappingEntity(
        mappingId = mapping.mappingId.value.toString(),
        authority = mapping.external.authority,
        externalValue = mapping.external.value,
        canonicalTitleId = mapping.canonicalTitleId.value.toString(),
        canonicalEditionId = mapping.canonicalEditionId?.value?.toString(),
        status = mapping.status,
        confidence = mapping.confidence,
        origin = mapping.origin,
        supersedesMappingId = mapping.supersedesMappingId?.value?.toString(),
        createdAtMs = mapping.createdAt.toEpochMilli(),
    )

    fun toCanonical(entity: IdentityMappingEntity): IdentityMapping = IdentityMapping(
        mappingId = MappingId.fromString(entity.mappingId),
        external = TypedExternalId(entity.authority, entity.externalValue),
        canonicalTitleId = TitleId.fromString(entity.canonicalTitleId),
        canonicalEditionId = entity.canonicalEditionId?.let { EditionId.fromString(it) },
        status = entity.status,
        confidence = entity.confidence,
        origin = entity.origin,
        supersedesMappingId = entity.supersedesMappingId?.let { MappingId.fromString(it) },
        createdAt = Instant.ofEpochMilli(entity.createdAtMs),
    )
}
