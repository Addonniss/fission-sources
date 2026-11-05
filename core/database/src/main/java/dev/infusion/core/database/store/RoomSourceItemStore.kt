package dev.infusion.core.database.store

import dev.infusion.core.database.Clock
import dev.infusion.core.database.dao.AvailabilityDao
import dev.infusion.core.database.dao.ExternalIdDao
import dev.infusion.core.database.dao.ServerItemDao
import dev.infusion.core.database.dao.ServerMediaVersionDao
import dev.infusion.core.database.dao.TitleDao
import dev.infusion.core.database.entity.AvailabilityEntity
import dev.infusion.core.database.entity.ExternalIdEntity
import dev.infusion.core.database.entity.ServerItemEntity
import dev.infusion.core.database.entity.ServerMediaVersionEntity
import dev.infusion.core.database.entity.TitleEntity
import dev.infusion.core.database.mapper.CandidateFactsCodec
import dev.infusion.core.database.mapper.PayloadRefCodec
import dev.infusion.core.database.model.AvailabilityKind
import dev.infusion.core.database.model.TitleKind
import dev.infusion.core.domain.source.SourceItemStore
import dev.infusion.core.model.Availability
import dev.infusion.core.model.CandidateFacts
import dev.infusion.core.model.EditionId
import dev.infusion.core.model.PayloadRef
import dev.infusion.core.model.Provenance
import dev.infusion.core.model.ServerItem
import dev.infusion.core.model.ServerMediaVersion
import dev.infusion.core.model.SourceId
import dev.infusion.core.model.Title
import dev.infusion.core.model.TitleId
import java.time.Instant
import org.json.JSONObject

class RoomSourceItemStore(
    private val serverItemDao: ServerItemDao,
    private val serverMediaVersionDao: ServerMediaVersionDao,
    private val titleDao: TitleDao,
    private val externalIdDao: ExternalIdDao,
    private val availabilityDao: AvailabilityDao,
    private val clock: Clock = Clock.SYSTEM,

    private val transaction: (block: () -> Unit) -> Unit = { block -> block() },
) : SourceItemStore {

    private val titleSupersession = TitleSupersession(titleDao)

    override fun upsertServerItem(item: ServerItem) {
        serverItemDao.upsert(item.toEntity())
    }

    override fun upsertServerMediaVersion(version: ServerMediaVersion) {
        serverMediaVersionDao.upsert(version.toEntity())
    }

    override fun upsertAll(
        items: List<ServerItem>,
        versions: List<ServerMediaVersion>,
        titles: List<Title>,
        availability: List<Availability>,
    ) {
        transaction {
            val canonicalId: (TitleId) -> TitleId = { titleSupersession.survivorOf(it) }

            val seenTitles = LinkedHashMap<TitleId, Title>()
            titles.forEach { seenTitles[it.titleId] = it }
            seenTitles.values.forEach { title ->

                if (title.titleId == canonicalId(title.titleId)) {
                    titleDao.upsert(title.toEntity())
                }
                val ownerId = canonicalId(title.titleId).value.toString()
                title.aliases.forEach { alias ->

                    val existing = externalIdDao.get(alias.authority, alias.value)
                    if (existing != null && existing.titleId != null && existing.titleId != ownerId) {
                        return@forEach
                    }
                    externalIdDao.upsert(
                        ExternalIdEntity(
                            authority = alias.authority,
                            nativeId = alias.value,
                            titleId = ownerId,
                            editionId = null,
                            createdAtMs = clock.nowMs(),
                        ),
                    )
                }
            }

            availability.forEach { availabilityDao.upsert(it.copy(titleId = canonicalId(it.titleId)).toEntity()) }

            items.forEach { item ->
                val canonical = item.titleId?.let(canonicalId)
                serverItemDao.upsert(item.copy(titleId = canonical).toEntity())
            }
            versions.forEach { serverMediaVersionDao.upsert(it.toEntity()) }
        }
    }

    override fun deleteMissingByLibrary(
        sourceId: SourceId,
        libraryId: String,
        retainedNativeIds: Set<String>,
    ): Int {
        val source = sourceId.value.toString()
        if (retainedNativeIds.isEmpty()) {

            val stale = serverItemDao.listNativeIdsByLibrary(source, libraryId)
            if (stale.isEmpty()) return 0
            var removed = 0
            transaction {
                availabilityDao.deleteServerByNativeResourceIds(source, stale)
                removed = serverItemDao.deleteByLibrary(source, libraryId)
            }
            return removed
        }

        val stale = serverItemDao.listNativeIdsMissing(source, libraryId, retainedNativeIds.toList())
        if (stale.isEmpty()) return 0
        var removed = 0
        transaction {
            availabilityDao.deleteServerByNativeResourceIds(source, stale)
            removed = serverItemDao.deleteByNativeIds(source, stale)
        }
        return removed
    }

    override fun deleteNativeItems(sourceId: SourceId, nativeIds: Set<String>): Int {
        if (nativeIds.isEmpty()) return 0
        val source = sourceId.value.toString()
        val ids = nativeIds.toList()
        var removed = 0
        transaction {
            availabilityDao.deleteServerByNativeResourceIds(source, ids)
            removed = serverItemDao.deleteByNativeIds(source, ids)
        }
        return removed
    }

    override fun serverItemByNativeId(sourceId: SourceId, nativeId: String): ServerItem? =
        serverItemDao.getByNativeId(sourceId.value.toString(), nativeId)?.toCanonical()

    override fun serverItemsBySource(sourceId: SourceId): List<ServerItem> =
        serverItemDao.listBySource(sourceId.value.toString()).map { it.toCanonical() }

    override fun serverItemsByTitle(titleId: TitleId): List<ServerItem> =
        serverItemDao.listByTitle(titleSupersession.survivorOf(titleId).value.toString()).map { it.toCanonical() }

    override fun mediaVersionsForItem(serverItemId: String): List<ServerMediaVersion> =
        serverMediaVersionDao.listByServerItem(serverItemId).map { it.toCanonical() }

    override fun deleteBySource(sourceId: SourceId): Int =
        serverItemDao.deleteBySource(sourceId.value.toString())

    private fun Title.toEntity(): TitleEntity = TitleEntity(
        titleId = titleId.value.toString(),
        kind = TitleKind.valueOf(kind.name),
        primaryTitle = displayTitle,
        originalTitle = originalTitle,
        year = year,
        createdAtMs = lastRefreshedAt?.toEpochMilli() ?: clock.nowMs(),

        synopsis = synopsis,

        genres = genres.takeIf { it.isNotEmpty() }?.let(GenreCodec::encode),
        rating = rating,
        synopsisProvenanceJson = synopsisProvenance?.let(ProvenanceCodec::encode),
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
    )

    private fun Availability.toEntity(): AvailabilityEntity = AvailabilityEntity(
        availabilityId = availabilityId.value.toString(),
        titleId = titleId.value.toString(),
        editionId = editionId?.value?.toString(),
        sourceId = sourceId.value.toString(),
        nativeResourceId = nativeResourceId,
        kind = AvailabilityKind.valueOf(kind.name),
        region = regionHint,
        expiresAtMs = expiresAt?.toEpochMilli(),
        entitlementHintsJson = null,

        provenance = provenance?.let(ProvenanceCodec::encode),
        refreshedAtMs = observedAt.toEpochMilli(),
        createdAtMs = observedAt.toEpochMilli(),
    )

    private fun ServerItem.toEntity(): ServerItemEntity = ServerItemEntity(
        serverItemId = serverItemId,
        sourceId = sourceId.value.toString(),
        nativeId = nativeId,
        nativeParentId = nativeParentId,
        nativeType = nativeType,
        libraryId = libraryId,
        revision = revision,
        titleId = titleId?.value?.toString(),
        editionId = editionId?.value?.toString(),
        displayTitle = displayTitle,
        productionYear = productionYear,

        providerPayloadJson = PayloadRefCodec.encode(payloadRef),
        createdAtMs = createdAt.toEpochMilli(),
        updatedAtMs = clock.nowMs(),
    )

    private fun ServerItemEntity.toCanonical(): ServerItem = ServerItem(
        serverItemId = serverItemId,
        sourceId = SourceId.fromString(sourceId),
        nativeId = nativeId,
        nativeParentId = nativeParentId,
        nativeType = nativeType,
        libraryId = libraryId,
        revision = revision,
        titleId = titleId?.let { TitleId.fromString(it) },
        editionId = editionId?.let { EditionId.fromString(it) },
        displayTitle = displayTitle,
        productionYear = productionYear,
        payloadRef = PayloadRefCodec.decode(providerPayloadJson),
        createdAt = Instant.ofEpochMilli(createdAtMs),
    )

    private fun ServerMediaVersion.toEntity(): ServerMediaVersionEntity = ServerMediaVersionEntity(
        versionId = versionId,
        serverItemId = serverItemId,
        nativeVersionId = nativeVersionId,
        container = container,
        streamFactsJson = CandidateFactsCodec.encode(facts),
        pathClass = pathClass,
        decisionInputsJson = decisionInputs,
        createdAtMs = createdAt.toEpochMilli(),
    )

    private fun ServerMediaVersionEntity.toCanonical(): ServerMediaVersion = ServerMediaVersion(
        versionId = versionId,
        serverItemId = serverItemId,
        nativeVersionId = nativeVersionId,
        container = container,
        facts = streamFactsJson?.let { CandidateFactsCodec.decode(it) } ?: CandidateFacts(),
        pathClass = pathClass,
        decisionInputs = decisionInputsJson,
        createdAt = Instant.ofEpochMilli(createdAtMs),
    )

    private fun TitleEntity.toCanonicalTitle(): Title = Title(
        titleId = TitleId.fromString(titleId),
        kind = dev.infusion.core.model.TitleKind.valueOf(kind.name),
        displayTitle = primaryTitle,
        originalTitle = originalTitle,
        year = year,

        synopsis = synopsis,
        synopsisProvenance = synopsis?.let { ProvenanceCodec.decode(synopsisProvenanceJson) },

        genres = GenreCodec.decode(genres),
        genresProvenance = genres?.let { ProvenanceCodec.decode(genresProvenanceJson) },
        rating = rating,
        ratingProvenance = rating?.let { ProvenanceCodec.decode(ratingProvenanceJson) },
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
    )
}

object ProvenanceCodec {

    fun encode(provenance: Provenance): String = JSONObject()
        .put("sourceId", provenance.sourceId.value.toString())
        .put("protocolVersion", provenance.protocolVersion)
        .put("retrievedAt", provenance.retrievedAt.toEpochMilli())
        .put("normalizedBy", provenance.normalizedBy)
        .putOpt("payloadRef", provenance.payloadRef?.let { ref ->
            JSONObject()
                .put("payloadType", ref.payloadType)
                .put("payloadHash", ref.payloadHash)
                .put("payloadVersion", ref.payloadVersion)
        })
        .toString()

    fun decode(json: String?): Provenance? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(json)
            Provenance(
                sourceId = SourceId.fromString(o.getString("sourceId")),
                protocolVersion = o.optString("protocolVersion", "stremio/1"),
                retrievedAt = Instant.ofEpochMilli(o.optLong("retrievedAt", 0L)),
                normalizedBy = o.optString("normalizedBy", "RoomSourceItemStore"),
                payloadRef = o.optJSONObject("payloadRef")?.let { ref ->
                    PayloadRef(
                        payloadType = ref.getString("payloadType"),
                        payloadHash = ref.getString("payloadHash"),
                        payloadVersion = ref.optInt("payloadVersion", 1),
                    )
                },
            )
        }.getOrNull()
    }
}

object GenreCodec {

    fun encode(genres: List<String>): String = genres.joinToString(",")

    fun decode(joined: String?): List<String> {
        if (joined.isNullOrBlank()) return emptyList()
        return joined.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
}
