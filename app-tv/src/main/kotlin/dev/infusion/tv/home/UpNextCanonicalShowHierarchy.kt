package dev.infusion.tv.home

import dev.infusion.core.domain.metadata.CanonicalEpisodeOrder
import dev.infusion.core.domain.metadata.CanonicalEpisodeOrderEvidenceStore
import dev.infusion.core.domain.metadata.CanonicalEpisodeOrderRecording
import dev.infusion.core.domain.metadata.CanonicalEpisodeRef
import dev.infusion.core.domain.metadata.CanonicalShowHierarchy
import dev.infusion.core.domain.metadata.MediaIdentityResolver
import dev.infusion.core.domain.metadata.MetadataLocale
import dev.infusion.core.domain.metadata.MetadataRepository
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.metadata.MediaIdentity
import dev.infusion.core.model.metadata.MediaType
import dev.infusion.core.model.metadata.ShowDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class UpNextCanonicalShowHierarchy(
    private val metadataRepository: MetadataRepository,
    private val identityResolver: MediaIdentityResolver,
    private val locale: () -> MetadataLocale,

    private val episodeOrderEvidence: CanonicalEpisodeOrderEvidenceStore? = null,

    private val identityLimit: Int = IDENTITY_LIMIT,

    private val trace: (String) -> Unit = {},
) : CanonicalShowHierarchy {

    private val identities: MutableMap<String, MediaIdentity> =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, MediaIdentity>(128, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, MediaIdentity>,
                ): Boolean = size > identityLimit
            },
        )

    private val orders: MutableMap<String, SeriesOrder> =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, SeriesOrder>(128, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, SeriesOrder>,
                ): Boolean = size > identityLimit
            },
        )

    private sealed interface SeriesOrder {

        class Answer(val order: CanonicalEpisodeOrder) : SeriesOrder {
            val remaining: MutableSet<Int> =
                order.episodes.mapTo(mutableSetOf()) { episode -> episode.seasonNumber }
        }

        class Building(
            val recording: CanonicalEpisodeOrderRecording,

            val statedSeasons: List<Int>,

            val expectedSeasons: Set<Int>,
        ) : SeriesOrder {
            val episodes: MutableList<CanonicalEpisodeRef> = mutableListOf()
            val readSeasons: MutableSet<Int> = mutableSetOf()
        }
    }

    override suspend fun seasonNumbers(seriesTitleId: TitleId): List<Int> =
        withContext(Dispatchers.IO) {
            val seriesAt = System.nanoTime()
            val key = seriesKey(seriesTitleId)
            val identityHeld = identities.containsKey(key)
            val identityAt = System.nanoTime()
            val identity = identityOf(seriesTitleId)
            val identityMs = (System.nanoTime() - identityAt) / 1_000_000
            if (identity == null) {
                trace(
                    "series order answer=none identity=absent id_ms=$identityMs " +
                        "seq_ms=${(System.nanoTime() - seriesAt) / 1_000_000}",
                )
                return@withContext emptyList()
            }
            (orders[key] as? SeriesOrder.Answer)?.let { answer ->
                trace(
                    "series order answer=held identity=${identityLabel(identityHeld)} id_ms=$identityMs " +
                        "seq_ms=${(System.nanoTime() - seriesAt) / 1_000_000}",
                )
                return@withContext answer.order.seasons
            }
            val startedAt = System.nanoTime()
            val recordAt = System.nanoTime()
            val durable = durableOrder(identity, key)
            val recordMs = (System.nanoTime() - recordAt) / 1_000_000
            if (durable != null) {
                orders[key] = SeriesOrder.Answer(durable)
                trace(
                    "episode order row=hit ms=${(System.nanoTime() - startedAt) / 1_000_000} " +
                        "seasons=${durable.seasons.size} episodes=${durable.episodes.size}",
                )
                trace(
                    "series order answer=record identity=${identityLabel(identityHeld)} " +
                        "id_ms=$identityMs record_ms=$recordMs " +
                        "seq_ms=${(System.nanoTime() - seriesAt) / 1_000_000} " +
                        "seasons=${durable.seasons.size} episodes=${durable.episodes.size}",
                )
                return@withContext durable.seasons
            }

            val recordingAt = System.nanoTime()
            val recording = beginRecording(identity)
            val showAt = System.nanoTime()
            val show = runCatching {
                metadataRepository.observeDetails(identity, locale()).first().details
            }.getOrNull() as? ShowDetails
            val detailsMs = (System.nanoTime() - showAt) / 1_000_000
            val seasons = show?.seasons?.map { season -> season.seasonNumber } ?: emptyList()
            val expected = seasons.filter { it > 0 }.distinct().toSortedSet()
            if (recording != null && expected.isNotEmpty()) {
                orders[key] = SeriesOrder.Building(recording, seasons, expected)
            } else {
                orders.remove(key)
            }
            trace(
                "series order answer=authoritative identity=${identityLabel(identityHeld)} " +
                    "id_ms=$identityMs record_ms=$recordMs " +
                    "record_begin_ms=${(showAt - recordingAt) / 1_000_000} details_ms=$detailsMs " +
                    "seq_ms=${(System.nanoTime() - seriesAt) / 1_000_000} " +
                    "seasons=${seasons.size} expected=${expected.size} recordable=${recording != null && expected.isNotEmpty()}",
            )
            seasons
        }

    private fun identityLabel(held: Boolean): String = if (held) "held" else "resolved"

    override suspend fun episodes(
        seriesTitleId: TitleId,
        seasonNumber: Int,
    ): List<CanonicalEpisodeRef> = withContext(Dispatchers.IO) {
        val identity = identityOf(seriesTitleId) ?: return@withContext emptyList()
        val key = seriesKey(seriesTitleId)

        if (seasonNumber > 0) {
            when (val held = orders[key]) {
                is SeriesOrder.Answer -> {
                    held.remaining.remove(seasonNumber)
                    if (held.remaining.isEmpty()) orders.remove(key)
                    return@withContext held.order.episodes.filter { it.seasonNumber == seasonNumber }
                }
                is SeriesOrder.Building ->

                    runCatching { held.recording.seasonStamp(seasonNumber) }
                null ->

                    durableOrder(identity, key)?.let { order ->
                        val episodes = order.episodes.filter { it.seasonNumber == seasonNumber }
                        if (episodes.isNotEmpty()) return@withContext episodes
                    }
            }
        }
        val season = runCatching {
            metadataRepository.seasonDetails(identity, seasonNumber, locale()).valueOrNull()
        }.getOrNull() ?: return@withContext emptyList()
        val episodes = season.episodes.map { episode ->
            CanonicalEpisodeRef(
                seasonNumber = episode.seasonNumber,
                episodeNumber = episode.episodeNumber,
                title = episode.name?.takeIf { it.isNotBlank() },
                released = episode.airDate?.toString(),

                runtimeMinutes = episode.runtimeMinutes,
            )
        }
        val building = orders[key] as? SeriesOrder.Building
        if (building != null) {
            building.episodes += episodes
            building.readSeasons += seasonNumber
            if (building.readSeasons.containsAll(building.expectedSeasons)) {
                orders.remove(key)
                commit(building)
            }
        }
        episodes
    }

    private suspend fun commit(building: SeriesOrder.Building) {
        val order = CanonicalEpisodeOrder(
            seasons = building.statedSeasons,
            episodes = building.episodes.toList(),
        )
        val startedAt = System.nanoTime()
        val written = runCatching {
            building.recording.commit(order, building.readSeasons.toSet())
        }.getOrDefault(false)
        trace(
            "episode order record written=$written ms=${(System.nanoTime() - startedAt) / 1_000_000} " +
                "seasons=${order.seasons.size} episodes=${order.episodes.size}",
        )
    }

    private suspend fun durableOrder(identity: MediaIdentity, key: String): CanonicalEpisodeOrder? {
        val store = episodeOrderEvidence ?: return null
        val order = runCatching { store.load(identity, locale()) }.getOrNull() ?: return null
        (orders[key] as? SeriesOrder.Building)?.let { orders.remove(key) }
        return order
    }

    private suspend fun beginRecording(identity: MediaIdentity): CanonicalEpisodeOrderRecording? {
        val store = episodeOrderEvidence ?: return null
        val recording = runCatching { store.beginRecording(identity, locale()) }.getOrNull() ?: return null
        runCatching { recording.detailsStamp() }
        return recording
    }

    private suspend fun identityOf(seriesTitleId: TitleId): MediaIdentity? {
        val key = seriesKey(seriesTitleId)
        identities[key]?.let { return it }
        val resolved = runCatching {
            identityResolver.resolve(seriesTitleId, MediaType.SHOW, locale())
        }.getOrNull() ?: return null
        identities[key] = resolved
        return resolved
    }

    private fun seriesKey(seriesTitleId: TitleId): String = "${locale().tag}\u0000${seriesTitleId.value}"

    private companion object {

        const val IDENTITY_LIMIT = 512
    }
}
