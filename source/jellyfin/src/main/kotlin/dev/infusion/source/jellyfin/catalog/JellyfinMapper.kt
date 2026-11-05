package dev.infusion.source.jellyfin.catalog

import dev.infusion.core.model.Availability
import dev.infusion.core.model.AvailabilityId
import dev.infusion.core.model.AvailabilityKind
import dev.infusion.core.model.Edition
import dev.infusion.core.model.EditionId
import dev.infusion.core.model.NativeItemId
import dev.infusion.core.model.PayloadRef
import dev.infusion.core.model.Provenance
import dev.infusion.core.model.ServerItem
import dev.infusion.core.model.SourceId
import dev.infusion.core.model.Title
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import dev.infusion.core.model.TypedExternalId
import dev.infusion.core.model.metadata.ExternalIds
import dev.infusion.core.model.metadata.MediaIdentity
import dev.infusion.core.model.metadata.MediaType
import dev.infusion.core.network.Redactor
import dev.infusion.source.jellyfin.wire.ItemSummary
import java.time.Instant
import java.util.UUID

class JellyfinMapper(
    private val sourceId: SourceId,
    private val serverId: String,
    private val serverVersion: String,
    private val now: () -> Instant = Instant::now,
) {

    val aliasAuthority: String = "jellyfin:$serverId"

    fun titleIdFor(nativeId: String): TitleId =
        TitleId(UUID.nameUUIDFromBytes("$sourceId:$nativeId".toByteArray()))

    fun editionIdFor(nativeId: String): EditionId =
        EditionId(UUID.nameUUIDFromBytes("$sourceId:$nativeId:edition".toByteArray()))

    fun availabilityIdFor(nativeId: String): AvailabilityId =
        AvailabilityId(UUID.nameUUIDFromBytes("$sourceId:$nativeId:avail".toByteArray()))

    private fun provenance(nativeId: String, payloadType: String): Provenance = Provenance(
        sourceId = sourceId,
        protocolVersion = serverVersion,
        retrievedAt = now(),
        normalizedBy = "JellyfinMapper",
        payloadRef = PayloadRef(
            payloadType = payloadType,
            payloadHash = Redactor.hashFragment("$sourceId:$nativeId", 24),
        ),
    )

    internal companion object {
        fun canonicalProviderAliases(providerIds: Map<String, String>): Set<TypedExternalId> = buildSet {
            providerIds.forEach { (key, rawValue) ->
                val value = rawValue.trim()
                if (value.isEmpty() || value.length > 64) return@forEach
                when (key.trim().lowercase()) {
                    "tmdb" -> if (DIGITS.matches(value)) add(TypedExternalId("tmdb", value))
                    "tvdb" -> if (DIGITS.matches(value)) add(TypedExternalId("tvdb", value))
                    "imdb" -> if (IMDB.matches(value)) add(TypedExternalId("imdb", value))
                    else -> Unit
                }
            }
        }

        private val DIGITS = Regex("^\\d{1,10}$")
        private val IMDB = Regex("^tt\\d{5,10}$")
    }

    private fun kindOf(type: String?): TitleKind = when (type) {
        "Movie" -> TitleKind.MOVIE
        "Series" -> TitleKind.SERIES
        "Season" -> TitleKind.SEASON
        "Episode" -> TitleKind.EPISODE
        else -> TitleKind.CLIP
    }

    fun toTitle(item: ItemSummary): Title = Title(
        titleId = titleIdFor(requireNotNull(item.id) { "item id missing" }),
        kind = kindOf(item.type),
        displayTitle = item.name ?: "(untitled)",
        aliases = buildSet {
            add(TypedExternalId(aliasAuthority, item.id))

            addAll(canonicalProviderAliases(item.providerIds))
        },

        parentTitleId = when (kindOf(item.type)) {
            TitleKind.SEASON, TitleKind.EPISODE -> item.seriesId?.let(::titleIdFor)
            else -> null
        },
        originalTitle = item.name,
        year = item.productionYear,
        provenance = provenance(item.id, "jellyfin/ItemSummary"),
        lastRefreshedAt = now(),

        synopsis = item.overview?.takeIf { it.isNotBlank() },
        synopsisProvenance = item.overview?.takeIf { it.isNotBlank() }?.let {
            provenance(item.id, "jellyfin/ItemSummary/Overview")
        },

        seasonNumber = if (kindOf(item.type) == TitleKind.EPISODE) item.parentIndexNumber?.takeIf { it > 0 } else null,
        episodeNumber = if (kindOf(item.type) == TitleKind.EPISODE) item.indexNumber?.takeIf { it > 0 } else null,
    )

    fun toAvailability(item: ItemSummary): Availability = Availability(
        availabilityId = availabilityIdFor(requireNotNull(item.id) { "item id missing" }),
        titleId = titleIdFor(item.id),
        editionId = null,
        sourceId = sourceId,
        nativeResourceId = item.id,
        kind = AvailabilityKind.SERVER,
        observedAt = now(),
        provenance = provenance(item.id, "jellyfin/ItemSummary"),
    )

    fun toServerItem(item: ItemSummary, libraryId: String? = null): ServerItem = ServerItem(
        serverItemId = UUID.nameUUIDFromBytes("$sourceId:${item.id}:serveritem".toByteArray()).toString(),
        sourceId = sourceId,
        nativeId = requireNotNull(item.id) { "item id missing" },
        nativeParentId = item.parentId ?: item.seriesId ?: item.seasonId,
        nativeType = item.type ?: "Unknown",
        libraryId = libraryId,
        revision = item.userData?.key,
        titleId = titleIdFor(item.id),
        editionId = null,
        displayTitle = item.name,
        productionYear = item.productionYear,
        payloadRef = PayloadRef(
            payloadType = "jellyfin/ItemSummary",
            payloadHash = Redactor.hashFragment("$sourceId:${item.id}", 24),
        ),
        createdAt = now(),
    )

    fun nativeItemId(nativeId: String): NativeItemId = NativeItemId(sourceId, nativeId)

    fun ratingsEvidence(item: ItemSummary): dev.infusion.core.domain.ratings.JellyfinRatingsEvidence =
        dev.infusion.core.domain.ratings.JellyfinRatingsEvidence(
            community = item.communityRating,
            critics = item.criticRating,
        )

    fun ratingsEvidenceKey(item: ItemSummary): String? {
        val type = when (kindOf(item.type)) {
            TitleKind.MOVIE -> MediaType.MOVIE
            TitleKind.SERIES -> MediaType.SHOW
            else -> return null
        }
        val metadataAliases = canonicalProviderAliases(item.providerIds)
        if (metadataAliases.isEmpty()) return null
        return MediaIdentity(
            type = type,
            externalIds = ExternalIds.fromTyped(metadataAliases),
        ).cacheKey()
    }
}
