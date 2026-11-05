package dev.infusion.core.domain.upnext

import dev.infusion.core.domain.watch.EpisodeAirStatus
import dev.infusion.core.domain.watch.EpisodeReleaseEvidence
import dev.infusion.core.domain.watch.NextUnwatchedDerivation
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.core.model.watch.WatchStatus
import java.time.Instant
import java.time.LocalDate

sealed interface UpNextSource {

    data object LocalOnly : UpNextSource

    data class Tracker(val trackerId: String) : UpNextSource {
        init {
            require(trackerId.isNotBlank()) { "an Up Next tracker source requires the provider's stable id" }
        }
    }
}

data class UpNextSelection(val source: UpNextSource? = null) {

    val isRecorded: Boolean get() = source != null

    companion object {

        val Unset: UpNextSelection = UpNextSelection()

        fun localOnly(): UpNextSelection = UpNextSelection(UpNextSource.LocalOnly)

        fun tracker(trackerId: String): UpNextSelection = UpNextSelection(UpNextSource.Tracker(trackerId))
    }
}

data class UpNextSourceResolution(

    val effective: UpNextSource,

    val adopted: UpNextSource.Tracker? = null,

    val unavailableTrackerId: String? = null,
) {

    val selectedTrackerUnavailable: Boolean get() = unavailableTrackerId != null
}

object UpNextSourceRule {

    fun resolve(
        recorded: UpNextSelection,
        availableTrackerIds: List<String>,
    ): UpNextSourceResolution {
        val available = availableTrackerIds.distinct()
        return when (val source = recorded.source) {
            null -> when (available.size) {
                0 -> UpNextSourceResolution(UpNextSource.LocalOnly)
                1 -> UpNextSource.Tracker(available.first()).let {
                    UpNextSourceResolution(effective = it, adopted = it)
                }

                else -> UpNextSourceResolution(UpNextSource.LocalOnly)
            }
            UpNextSource.LocalOnly -> UpNextSourceResolution(UpNextSource.LocalOnly)
            is UpNextSource.Tracker ->
                if (source.trackerId in available) {
                    UpNextSourceResolution(source)
                } else {
                    UpNextSourceResolution(
                        effective = UpNextSource.LocalOnly,
                        unavailableTrackerId = source.trackerId,
                    )
                }
        }
    }
}

data class UpNextEpisode(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val episodeTitleId: TitleId? = null,
    val title: String? = null,

    val airedAt: LocalDate? = null,

    val airedAtProvenAt: Instant? = null,
) {

    fun watchKey(seriesTitleId: TitleId): WatchKey =
        WatchKey.episode(seriesTitleId, seasonNumber, episodeNumber)
}

data class UpNextSeriesEvidence(
    val seriesTitleId: TitleId,
    val seriesTitle: String? = null,

    val episodes: List<UpNextEpisode>,

    val watchStates: Map<WatchKey, WatchStatus>,
    val activityAt: Instant? = null,
)

data class UpNextTrackerCandidate(
    val trackerId: String,
    val seriesTitleId: TitleId,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val episodeTitleId: TitleId? = null,
    val episodeTitle: String? = null,
    val seriesTitle: String? = null,

    val activityAt: Instant? = null,

    val airedAt: LocalDate? = null,

    val airedAtProvenAt: Instant? = null,
) {
    val watchKey: WatchKey get() = WatchKey.episode(seriesTitleId, seasonNumber, episodeNumber)
}

enum class UpNextUnresolvedReason {

    NOT_ESTABLISHED,

    UNFINISHED_LOCAL_EPISODE,

    COMPLETED_SERIES,

    AIR_DATE_UNKNOWN,

    NOT_YET_AIRED,
}

data class UpNextUnresolvedSeries(
    val seriesTitleId: TitleId,
    val reason: UpNextUnresolvedReason,
)

data class UpNextCard(

    val key: String,
    val seriesTitleId: TitleId,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val episodeTitleId: TitleId? = null,
    val episodeTitle: String? = null,
    val seriesTitle: String? = null,

    val trackerId: String? = null,

    val activityAt: Instant? = null,
) {
    val isLocal: Boolean get() = trackerId == null
}

data class UpNextProjectionResult(
    val cards: List<UpNextCard> = emptyList(),
    val unresolved: List<UpNextUnresolvedSeries> = emptyList(),
) {
    val isEmpty: Boolean get() = cards.isEmpty()
}

object UpNextProjection {

    fun cardKey(seriesTitleId: TitleId, seasonNumber: Int, episodeNumber: Int): String =
        "upnext:${seriesTitleId.value}:S${seasonNumber}E${episodeNumber}"

    fun project(
        local: List<UpNextSeriesEvidence>,
        tracker: List<UpNextTrackerCandidate>,
        today: LocalDate,
        now: Instant? = null,
    ): UpNextProjectionResult {
        val localBySeries = local.associateBy { it.seriesTitleId }

        val trackerBySeries = tracker.groupBy { it.seriesTitleId }.mapValues { (_, entries) -> entries.first() }

        val seriesIds = LinkedHashSet<TitleId>()
        seriesIds += local.map { it.seriesTitleId }
        seriesIds += tracker.map { it.seriesTitleId }

        val cards = ArrayList<UpNextCard>()
        val unresolved = ArrayList<UpNextUnresolvedSeries>()
        for (seriesId in seriesIds) {
            val evidence = localBySeries[seriesId]
            val trackerCandidate = trackerBySeries[seriesId]
            val localOutcome = evidence?.let { deriveLocal(it, today, now) }
            val card = resolveSeries(evidence, trackerCandidate, localOutcome, today, now)
            if (card != null) {
                cards += card
            } else {
                unresolved += UpNextUnresolvedSeries(
                    seriesTitleId = seriesId,
                    reason = (localOutcome as? LocalOutcome.Unresolved)?.reason

                        ?: trackerCandidate?.takeIf { !it.isAiredPermitted(today, now) }
                            ?.let { UpNextUnresolvedReason.NOT_YET_AIRED }
                        ?: UpNextUnresolvedReason.NOT_ESTABLISHED,
                )
            }
        }

        val ordered = cards.sortedWith(
            compareByDescending<UpNextCard> { it.activityAt ?: Instant.EPOCH }
                .thenBy { it.key },
        )
        return UpNextProjectionResult(cards = ordered, unresolved = unresolved)
    }

    private fun deriveLocal(evidence: UpNextSeriesEvidence, today: LocalDate, now: Instant?): LocalOutcome {
        if (evidence.episodes.isEmpty()) return LocalOutcome.Unresolved(UpNextUnresolvedReason.NOT_ESTABLISHED)
        val states = evidence.watchStates

        val established = evidence.episodes.any { episode ->
            when (states[episode.watchKey(evidence.seriesTitleId)]) {
                WatchStatus.COMPLETED, WatchStatus.IN_PROGRESS -> true
                else -> false
            }
        }
        if (!established) return LocalOutcome.Unresolved(UpNextUnresolvedReason.NOT_ESTABLISHED)

        val catalog = evidence.episodes.map { episode ->
            dev.infusion.core.domain.watch.EpisodeCatalogEntry(
                seriesTitleId = evidence.seriesTitleId,
                seasonNumber = episode.seasonNumber,
                episodeNumber = episode.episodeNumber,

                episodeTitleId = episode.episodeTitleId ?: evidence.seriesTitleId,
                displayTitle = episode.title,
            )
        }

        val watchStateMap = evidence.episodes.mapNotNull { episode ->
            val key = episode.watchKey(evidence.seriesTitleId)
            states[key]?.let { status ->
                key to dev.infusion.core.model.watch.WatchState(watchKey = key, status = status)
            }
        }.toMap()
        val derived = NextUnwatchedDerivation.deriveNextUnwatched(catalog, watchStateMap)
            ?: return LocalOutcome.Unresolved(UpNextUnresolvedReason.NOT_ESTABLISHED)

        val episode = evidence.episodes.firstOrNull {
            it.seasonNumber == derived.seasonNumber && it.episodeNumber == derived.episodeNumber
        } ?: return LocalOutcome.Unresolved(UpNextUnresolvedReason.NOT_ESTABLISHED)

        val status = states[episode.watchKey(evidence.seriesTitleId)]
        return when (status) {

            WatchStatus.IN_PROGRESS -> LocalOutcome.Unresolved(UpNextUnresolvedReason.UNFINISHED_LOCAL_EPISODE)
            WatchStatus.COMPLETED -> LocalOutcome.Unresolved(UpNextUnresolvedReason.COMPLETED_SERIES)

            WatchStatus.UNSEEN, WatchStatus.EXPLICITLY_RESET, null -> when (
                airStatus(episode.airedAt, episode.airedAtProvenAt, today, now)
            ) {
                EpisodeAirStatus.UNKNOWN -> LocalOutcome.Unresolved(UpNextUnresolvedReason.AIR_DATE_UNKNOWN)
                EpisodeAirStatus.NOT_YET_AIRED -> LocalOutcome.Unresolved(UpNextUnresolvedReason.NOT_YET_AIRED)
                EpisodeAirStatus.AIRED -> LocalOutcome.Candidate(
                    UpNextCard(
                        key = cardKey(evidence.seriesTitleId, episode.seasonNumber, episode.episodeNumber),
                        seriesTitleId = evidence.seriesTitleId,
                        seasonNumber = episode.seasonNumber,
                        episodeNumber = episode.episodeNumber,
                        episodeTitleId = episode.episodeTitleId,
                        episodeTitle = episode.title,
                        seriesTitle = evidence.seriesTitle,
                        trackerId = null,
                        activityAt = evidence.activityAt,
                    ),
                )
            }
        }
    }

    private fun resolveSeries(
        evidence: UpNextSeriesEvidence?,
        trackerCandidate: UpNextTrackerCandidate?,
        localOutcome: LocalOutcome?,
        today: LocalDate,
        now: Instant?,
    ): UpNextCard? = when {
        evidence == null ->
            trackerCandidate?.takeIf { it.isLocallyUsable(null) && it.isAiredPermitted(today, now) }?.toCard()
        localOutcome is LocalOutcome.Candidate -> localOutcome.card
        localOutcome is LocalOutcome.Unresolved &&
            localOutcome.reason == UpNextUnresolvedReason.UNFINISHED_LOCAL_EPISODE -> null
        else -> trackerCandidate
            ?.takeIf { it.isLocallyUsable(evidence) && it.isAiredPermitted(today, now) }
            ?.toCard()
    }

    private fun UpNextTrackerCandidate.isAiredPermitted(today: LocalDate, now: Instant?): Boolean =
        when (airStatus(airedAt, airedAtProvenAt, today, now)) {
            EpisodeAirStatus.UNKNOWN -> true
            EpisodeAirStatus.AIRED -> true
            EpisodeAirStatus.NOT_YET_AIRED -> false
        }

    private fun airStatus(date: LocalDate?, instant: Instant?, today: LocalDate, now: Instant?): EpisodeAirStatus =
        EpisodeReleaseEvidence.airStatus(date, instant, today, now)

    private fun UpNextTrackerCandidate.isLocallyUsable(evidence: UpNextSeriesEvidence?): Boolean {
        if (evidence == null) return true
        val states = evidence.watchStates
        when (states[watchKey]) {
            WatchStatus.COMPLETED -> return false
            WatchStatus.IN_PROGRESS -> return false
            else -> Unit
        }
        val blocked = evidence.episodes.any { episode ->
            if (episode.seasonNumber > seasonNumber) return@any false
            if (episode.seasonNumber == seasonNumber && episode.episodeNumber >= episodeNumber) return@any false
            states[episode.watchKey(evidence.seriesTitleId)] == WatchStatus.IN_PROGRESS
        }
        return !blocked
    }

    private fun UpNextTrackerCandidate.toCard(): UpNextCard = UpNextCard(
        key = cardKey(seriesTitleId, seasonNumber, episodeNumber),
        seriesTitleId = seriesTitleId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        episodeTitleId = episodeTitleId,
        episodeTitle = episodeTitle,
        seriesTitle = seriesTitle,
        trackerId = trackerId,
        activityAt = activityAt,
    )

    private sealed interface LocalOutcome {
        data class Candidate(val card: UpNextCard) : LocalOutcome
        data class Unresolved(val reason: UpNextUnresolvedReason) : LocalOutcome
    }
}
