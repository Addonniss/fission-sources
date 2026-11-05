package dev.infusion.core.database.store

import dev.infusion.core.database.Clock
import dev.infusion.core.database.dao.CandidateHandoffDao
import dev.infusion.core.database.entity.CandidateHandoffEntity
import dev.infusion.core.database.mapper.CandidateSnapshotCodec
import dev.infusion.core.domain.candidate.CandidateHandoffStore
import dev.infusion.core.model.CandidateId
import dev.infusion.core.model.PlayableCandidate
import dev.infusion.core.model.SourceId
import dev.infusion.core.model.TransportDescriptor
import dev.infusion.core.security.SecretContext
import dev.infusion.core.security.SecretReference
import dev.infusion.core.security.SecretStore
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class RoomCandidateHandoffStore(
    private val dao: CandidateHandoffDao,
    private val secretStore: SecretStore,
    private val clock: Clock = Clock.SYSTEM,
) : CandidateHandoffStore {

    override suspend fun put(candidate: PlayableCandidate) = withContext(Dispatchers.IO) {

        dao.getById(candidate.candidateId.value.toString())?.transportSecretRef?.let { old ->
            secretStore.delete(SecretReference(old))
        }
        val transportRef = secretStore.put(
            candidate.transport.value.toByteArray(StandardCharsets.UTF_8),
            SecretContext(purpose = PURPOSE_TRANSPORT, sourceId = candidate.sourceId.value.toString()),
        )
        dao.upsert(
            CandidateHandoffEntity(
                candidateId = candidate.candidateId.value.toString(),
                snapshotJson = CandidateSnapshotCodec.encode(candidate),
                transportSecretRef = transportRef.id,
                createdAtMs = clock.nowMs(),
            ),
        )
    }

    override suspend fun byId(candidateId: CandidateId): PlayableCandidate? = withContext(Dispatchers.IO) {
        val row = dao.getById(candidateId.value.toString()) ?: return@withContext null
        val transportRef = row.transportSecretRef?.let { SecretReference(it) } ?: return@withContext null
        val transportBytes = secretStore.get(transportRef) ?: return@withContext null
        CandidateSnapshotCodec.decode(
            row.snapshotJson,
            TransportDescriptor(String(transportBytes, StandardCharsets.UTF_8)),
        )
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {

        dao.all().mapNotNull { it.transportSecretRef }.forEach { secretStore.delete(SecretReference(it)) }
        dao.deleteAll()
    }

    override fun clearForSource(sourceId: SourceId) {

        val target = sourceId.value.toString()
        val owned = dao.all().filter { row ->
            runCatching { JSONObject(row.snapshotJson).optString("sourceId") }.getOrNull() == target
        }
        owned.forEach { row ->
            row.transportSecretRef?.let { secretStore.delete(SecretReference(it)) }
            dao.deleteById(row.candidateId)
        }
    }

    private companion object {
        const val PURPOSE_TRANSPORT = "candidate.handoff.transport"
    }
}
