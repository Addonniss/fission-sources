package dev.infusion.feature.vertical.player

import dev.infusion.core.domain.catalog.CatalogPort
import dev.infusion.core.domain.watch.EpisodeAirStatus
import dev.infusion.core.domain.watch.EpisodeCatalogEntry
import dev.infusion.core.domain.watch.EpisodeCoordinates
import dev.infusion.core.domain.watch.EpisodeReleaseEvidence
import dev.infusion.core.domain.watch.NextEpisodeDerivation
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.feature.vertical.model.EpisodeIdentityUi
import dev.infusion.feature.vertical.model.UpNextAvailability
import dev.infusion.feature.vertical.model.UpNextUi
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class UpNextStateHolder(
    private val completedKey: WatchKey,
    private val catalog: CatalogPort,
    private val scope: CoroutineScope,
    private val seriesTitleId: TitleId? = completedKey.seriesId,

    private val canonicalHierarchy: dev.infusion.core.domain.metadata.CanonicalShowHierarchy? = null,

    private val today: () -> LocalDate = LocalDate::now,
    private val now: () -> Instant = Instant::now,
) {

    private val _state = MutableStateFlow<UpNextUi?>(null)
    val state: StateFlow<UpNextUi?> = _state

    init {

        val seriesId = completedKey.seriesId
        val season = completedKey.seasonNumber
        val episode = completedKey.episodeNumber
        if (completedKey.isEpisode && seriesId != null && season != null && episode != null) {
            scope.launch {
                val first = derive(seriesId, season, episode)

                _state.value = first ?: noCandidate()
                if (first == null) {

                    delay(UpNextRetryDelayMs)
                    derive(seriesId, season, episode)?.let { retried -> _state.value = retried }
                }
            }
        } else {
            _state.value = null
        }
    }

    private suspend fun derive(seriesId: TitleId, season: Int, episode: Int): UpNextUi? {
        val completed = WatchKey.episode(seriesId, season, episode)

        val children = mutableListOf<EpisodeCatalogEntry>()
        val seriesNativeId = catalog.nativeItemIdFor(seriesId)
        if (seriesNativeId != null) {
            val seasons = catalog.children(seriesNativeId, 0, 200)?.entries ?: emptyList()
            for (seasonRow in seasons) {
                val seasonNumber = seasonRow.seasonNumber ?: continue
                val seasonNative = seasonRow.nativeItemId.takeIf { it.isNotBlank() } ?: continue
                val episodes = catalog.children(seasonNative, 0, 200)?.entries ?: emptyList()
                for (episodeRow in episodes) {
                    val episodeNumber = episodeRow.episodeNumber ?: continue
                    val episodeTitleId = episodeRow.titleId ?: continue
                    children += EpisodeCatalogEntry(

                        seriesTitleId = seriesId,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                        episodeTitleId = episodeTitleId,
                        displayTitle = episodeRow.displayTitle,
                    )
                }
            }
        }
        NextEpisodeDerivation.deriveNextEpisode(completed, children)?.let { next ->

            return nextUi(
                seriesId = seriesId,
                seasonNumber = next.seasonNumber,
                episodeNumber = next.episodeNumber,
                episodeTitleId = next.episodeTitleId,
                displayTitle = next.displayTitle,
                air = airStatusFor(seriesId, next.seasonNumber, next.episodeNumber),
            )
        }

        return deriveFromCanonical(seriesId, season, episode)
    }

    private suspend fun deriveFromCanonical(seriesId: TitleId, season: Int, episode: Int): UpNextUi? {
        val hierarchy = canonicalHierarchy ?: return null
        val present = mutableListOf<EpisodeCoordinates>()
        for (seasonNumber in runCatching { hierarchy.seasonNumbers(seriesId) }.getOrNull().orEmpty()) {
            runCatching { hierarchy.episodes(seriesId, seasonNumber) }.getOrNull().orEmpty().forEach { ref ->
                present += EpisodeCoordinates(ref.seasonNumber, ref.episodeNumber)
            }
        }
        val next = NextEpisodeDerivation.deriveNextCoordinates(season, episode, present) ?: return null
        val nextRef = runCatching { hierarchy.episodes(seriesId, next.seasonNumber) }.getOrNull()
            .orEmpty()
            .firstOrNull { it.episodeNumber == next.episodeNumber }
        val nextTitle = nextRef?.title?.takeIf { it.isNotBlank() }
        return nextUi(
            seriesId = seriesId,
            seasonNumber = next.seasonNumber,
            episodeNumber = next.episodeNumber,

            episodeTitleId = null,
            displayTitle = nextTitle,
            air = EpisodeReleaseEvidence.airStatusOf(nextRef?.released, today(), now()),
        )
    }

    private suspend fun airStatusFor(
        seriesId: TitleId,
        seasonNumber: Int,
        episodeNumber: Int,
    ): EpisodeAirStatus {
        val hierarchy = canonicalHierarchy ?: return EpisodeAirStatus.UNKNOWN
        val released = runCatching { hierarchy.episodes(seriesId, seasonNumber) }.getOrNull()
            .orEmpty()
            .firstOrNull { it.episodeNumber == episodeNumber }
            ?.released
        return EpisodeReleaseEvidence.airStatusOf(released, today(), now())
    }

    private fun nextUi(
        seriesId: TitleId,
        seasonNumber: Int,
        episodeNumber: Int,
        episodeTitleId: TitleId?,
        displayTitle: String?,
        air: EpisodeAirStatus,
    ): UpNextUi = UpNextUi(
        nextEpisode = EpisodeIdentityUi(
            seriesTitleId = seriesId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeTitleId = episodeTitleId,
        ),
        identityLabel = displayTitle?.let { title -> "S${seasonNumber}E${episodeNumber} — $title" }
            ?: "S${seasonNumber}E${episodeNumber} — UNKNOWN",

        availability = if (air == EpisodeAirStatus.NOT_YET_AIRED) {
            UpNextAvailability.NOT_YET_AIRED
        } else {
            UpNextAvailability.AVAILABLE
        },
    )

    private fun noCandidate(): UpNextUi = UpNextUi(
        nextEpisode = null,
        identityLabel = "UNKNOWN",
        isEpisode = true,
    )

    private companion object {

        const val UpNextRetryDelayMs = 1_200L
    }
}
