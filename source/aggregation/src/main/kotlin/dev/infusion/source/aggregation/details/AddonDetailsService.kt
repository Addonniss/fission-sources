package dev.infusion.source.aggregation.details

import dev.infusion.core.domain.catalog.TitleDetails
import dev.infusion.core.model.AvailabilityKind
import dev.infusion.core.model.Provenance
import dev.infusion.core.model.SourceId
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import dev.infusion.source.aggregation.AddonAvailabilityStore
import dev.infusion.source.aggregation.AddonRegistryPort
import dev.infusion.source.aggregation.resource.AddonMetaRepository
import dev.infusion.source.stremio.policy.StremioContentType
import java.time.Instant

data class AddonDetailsMeta(
    val addonId: String,
    val addonDisplayName: String,
    val nativeItemId: String,
    val synopsis: String?,
    val genres: List<String>,
    val imdbRating: String?,

    val episodeTitle: String? = null,
)

class AddonDetailsService(
    private val registry: AddonRegistryPort,
    private val metaRepository: AddonMetaRepository,
    private val availabilityStore: AddonAvailabilityStore,
    private val viewer: dev.infusion.core.model.profile.Profile,
    private val primary: dev.infusion.core.model.profile.Profile,
    private val now: () -> Instant = Instant::now,
) {

    suspend fun resolve(
        titleId: TitleId,
        kind: TitleKind,
        episodeTarget: EpisodeMetaTarget? = null,

        speculative: Boolean = false,
    ): AddonDetailsMeta? {
        val addonRows = availabilityStore.byTitle(titleId)
            .filter { it.kind == AvailabilityKind.ADDON }

        val descriptors = registry.runtimesFor(viewer, primary)
        val exposedSources = descriptors.map { it.sourceId }.toSet()
        val availability = addonRows.firstOrNull { it.sourceId in exposedSources }
            ?: addonRows.firstOrNull()
        if (availability == null) {
            Stage6MetaLog.log("resolve title=$titleId kind=$kind availability=ADDON_NONE -> null")
            return null
        }
        val type = when (kind) {
            TitleKind.MOVIE -> StremioContentType.MOVIE
            TitleKind.SERIES, TitleKind.SEASON, TitleKind.EPISODE -> StremioContentType.SERIES

            TitleKind.CLIP -> StremioContentType.MOVIE
        }
        val isEpisode = kind == TitleKind.EPISODE

        val metaResourceId = if (isEpisode) {
            parentSeriesResourceId(episodeTarget) ?: availability.nativeResourceId
        } else {
            availability.nativeResourceId
        }

        val preferredAddonId = descriptors.firstOrNull { it.sourceId == availability.sourceId }?.addonId
        Stage6MetaLog.log(
            "resolve title=$titleId kind=$kind type=${type.wire} native=${availability.nativeResourceId} " +
                "queried=${metaResourceId != availability.nativeResourceId} " +
                "preferred=${preferredAddonId ?: "UNRESOLVED"}" +
                if (preferredAddonId == null) " (H2: availability source not in registry)" else "",
        )
        val result = metaRepository.primary(type, metaResourceId, preferredAddonId, speculative = speculative)
        val response = result.response
        val addonId = result.addonId
        val stateSummary = result.states.map { (id, state) ->
            when (state) {
                is dev.infusion.source.aggregation.resource.ProviderResourceState.Loaded ->
                    "loaded:$id:${state.status}"
                is dev.infusion.source.aggregation.resource.ProviderResourceState.Failed ->
                    "failed:$id:${state.failure.reason.name}:retryable=${state.failure.retryable}"
                is dev.infusion.source.aggregation.resource.ProviderResourceState.Skipped -> "skipped:$id"
                dev.infusion.source.aggregation.resource.ProviderResourceState.Pending -> "pending:$id"
            }
        }
        if (response == null || addonId == null) {
            Stage6MetaLog.log(
                "resolve title=$titleId kind=$kind winner=${addonId ?: "NONE"} resolved=false " +
                    "states=[${stateSummary.joinToString(",")}]",
            )
            return null
        }
        val descriptor = descriptors.firstOrNull { it.addonId == addonId }

        val episodeEntry = if (isEpisode) {
            episodeVideoEntry(response.meta.videos, availability.nativeResourceId, episodeTarget)
        } else {
            null
        }
        val resolvedSynopsis = if (isEpisode) episodeEntry?.overview else response.meta.description
        Stage6MetaLog.log(
            "resolve title=$titleId kind=$kind winner=$addonId resolved=true " +
                "synopsis=${resolvedSynopsis?.isNullOrBlank() == false} " +
                "genres=${response.meta.genres.size} rating=${response.meta.imdbRating != null} " +
                "states=[${stateSummary.joinToString(",")}]",
        )
        return AddonDetailsMeta(
            addonId = addonId,
            addonDisplayName = descriptor?.displayName ?: addonId,
            nativeItemId = availability.nativeResourceId,

            synopsis = resolvedSynopsis,
            genres = response.meta.genres,
            imdbRating = response.meta.imdbRating,
            episodeTitle = episodeEntry?.label,
        )
    }

    private fun parentSeriesResourceId(episodeTarget: EpisodeMetaTarget?): String? =
        episodeTarget?.parentSeriesTitleId
            ?.let { seriesTitleId ->
                availabilityStore.byTitle(seriesTitleId)
                    .firstOrNull { it.kind == AvailabilityKind.ADDON }
                    ?.nativeResourceId
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
}

fun TitleDetails.withAddonMeta(meta: AddonDetailsMeta, now: Instant = Instant.now()): TitleDetails {
    val addonEpisodeTitle = meta.episodeTitle?.trim()?.takeIf { it.isNotEmpty() }
    val title = if (
        addonEpisodeTitle != null &&
        isEpisodeCoordinateLabel(title.displayTitle, title.seasonNumber, title.episodeNumber)
    ) {
        title.copy(displayTitle = addonEpisodeTitle)
    } else {
        title
    }
    val existingSynopsis = title.synopsis
    val existingGenres = title.genres
    val existingRating = title.rating
    val addonProvenance = Provenance(
        sourceId = SourceId.fromString(meta.addonId),
        protocolVersion = "stremio/1",
        retrievedAt = now,
        normalizedBy = "AddonDetailsService",
    )
    val enrichedTitle = if (
        (existingSynopsis.isNullOrBlank() && !meta.synopsis.isNullOrBlank()) ||
        (existingGenres.isEmpty() && meta.genres.isNotEmpty()) ||
        (existingRating.isNullOrBlank() && !meta.imdbRating.isNullOrBlank())
    ) {
        title.copy(
            synopsis = if (existingSynopsis.isNullOrBlank() && !meta.synopsis.isNullOrBlank()) {
                meta.synopsis
            } else {
                existingSynopsis
            },
            synopsisProvenance = if (existingSynopsis.isNullOrBlank() && !meta.synopsis.isNullOrBlank()) {
                addonProvenance
            } else {
                title.synopsisProvenance
            },
            genres = if (existingGenres.isEmpty() && meta.genres.isNotEmpty()) meta.genres else existingGenres,
            genresProvenance = if (existingGenres.isEmpty() && meta.genres.isNotEmpty()) {
                addonProvenance
            } else {
                title.genresProvenance
            },
            rating = if (existingRating.isNullOrBlank() && !meta.imdbRating.isNullOrBlank()) {
                meta.imdbRating
            } else {
                existingRating
            },
            ratingProvenance = if (existingRating.isNullOrBlank() && !meta.imdbRating.isNullOrBlank()) {
                addonProvenance
            } else {
                title.ratingProvenance
            },
        )
    } else {
        title
    }
    return copy(title = enrichedTitle)
}
