package dev.infusion.tracking.mdblist

import dev.infusion.core.domain.tracking.TrackingExternalIds
import dev.infusion.core.domain.tracking.TrackingInboundOutcome
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryPage
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryPort
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryRecord
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryRequest
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryUnit
import java.time.Instant
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

class MDBListWatchedHistory(
    private val client: MDBListHttpClient,
    private val auth: MDBListAuthRepository,
) : TrackingWatchedHistoryPort {

    override val providerId: String = MDBListAuthRepository.MDBLIST_PROVIDER_ID

    override fun accountConnected(): Boolean = auth.isConnected()

    override suspend fun readHistory(
        request: TrackingWatchedHistoryRequest,
    ): TrackingInboundOutcome<TrackingWatchedHistoryPage> {
        if (!auth.isConnected()) return TrackingInboundOutcome.Unauthorized(NO_AUTHORIZATION)

        val readStartedAt = Instant.now()
        val mediaType = when (request.unit) {
            TrackingWatchedHistoryUnit.MOVIE -> MEDIA_MOVIE
            TrackingWatchedHistoryUnit.EPISODE -> MEDIA_EPISODE
        }
        val limit = request.limit.coerceIn(1, PAGE_LIMIT)
        val query = LinkedHashMap<String, String>()
        query["mediatype"] = mediaType
        query["limit"] = limit.toString()
        request.cursor?.let { query["cursor"] = it }
        request.since?.let { query["since"] = it.toString() }

        val outcome = send(
            MDBListRequest(
                method = MDBListMethod.GET,
                path = WATCHED_PATH,
                query = query,
                readBudget = MDBListReadBudget.WATCHED_HISTORY,
            ),
        )
        val body = when (outcome) {
            is MDBListOutcome.Success -> outcome.response.body
            is MDBListOutcome.Unauthorized -> return TrackingInboundOutcome.Unauthorized(outcome.reason)
            is MDBListOutcome.Retry -> return TrackingInboundOutcome.Unavailable(outcome.reason)
            is MDBListOutcome.Rejected -> return TrackingInboundOutcome.Rejected(outcome.reason)
        }
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return TrackingInboundOutcome.Rejected(SHAPE_CHANGED)

        val records = ArrayList<TrackingWatchedHistoryRecord>()
        var malformed = 0
        var undated = 0
        when (request.unit) {
            TrackingWatchedHistoryUnit.MOVIE -> {
                val rows = root.optJSONArray("movies") ?: EMPTY
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: run { malformed++; continue }
                    val movie = row.optJSONObject("movie") ?: run { malformed++; continue }
                    val ids = externalIdsOf(movie.optJSONObject("ids"))
                    if (ids.isEmpty) {
                        malformed++
                        continue
                    }
                    val watchedAt = instantOf(row.optStringOrNull("watched_at"))
                    if (watchedAt == null) {
                        undated++
                        continue
                    }
                    records += TrackingWatchedHistoryRecord(
                        unit = TrackingWatchedHistoryUnit.MOVIE,
                        externalIds = ids,
                        watchedAt = watchedAt,

                        title = movie.optStringOrNull("title"),
                        year = movie.optIntOrNull("year"),
                    )
                }
            }
            TrackingWatchedHistoryUnit.EPISODE -> {
                val rows = root.optJSONArray("episodes") ?: EMPTY
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: run { malformed++; continue }
                    val episode = row.optJSONObject("episode") ?: run { malformed++; continue }
                    val show = episode.optJSONObject("show")
                    val ids = externalIdsOf(show?.optJSONObject("ids"))
                    if (ids.isEmpty) {
                        malformed++
                        continue
                    }
                    val seasonNumber = episode.optIntOrNull("season")
                    val episodeNumber = episode.optIntOrNull("number")
                    if (seasonNumber == null || episodeNumber == null ||
                        seasonNumber <= 0 || episodeNumber <= 0
                    ) {
                        malformed++
                        continue
                    }
                    val watchedAt = instantOf(row.optStringOrNull("last_watched_at"))
                    if (watchedAt == null) {
                        undated++
                        continue
                    }
                    records += TrackingWatchedHistoryRecord(
                        unit = TrackingWatchedHistoryUnit.EPISODE,
                        externalIds = ids,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                        watchedAt = watchedAt,
                        title = show.optStringOrNull("title"),
                        year = show.optIntOrNull("year"),
                    )
                }
            }
        }

        val next = root.optJSONObject("pagination")?.optStringOrNull("next_cursor")
        val hasNext = !next.isNullOrBlank()
        return TrackingInboundOutcome.Ok(
            TrackingWatchedHistoryPage(
                records = records,
                next = if (hasNext) next else null,
                malformed = malformed,
                undated = undated,
                truncating = hasNext,

                completedThrough = if (hasNext) null else readStartedAt,
            ),
        )
    }

    private fun externalIdsOf(ids: JSONObject?): TrackingExternalIds {
        if (ids == null) return TrackingExternalIds()
        return TrackingExternalIds(
            tmdbId = ids.optLongOrNull("tmdb"),
            imdbId = ids.optStringOrNull("imdb"),
            tvdbId = ids.optLongOrNull("tvdb"),
        )
    }

    private fun instantOf(value: String?): Instant? =
        value?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private suspend fun send(request: MDBListRequest): MDBListOutcome {
        val token = readToken { auth.accessToken() } ?: return MDBListOutcome.Unauthorized(NO_AUTHORIZATION)
        val outcome = client.execute(request, token)
        if (outcome !is MDBListOutcome.Unauthorized) return sanitize(outcome)
        val refreshed = readToken { auth.refresh() } ?: return sanitize(outcome)
        return sanitize(client.execute(request, refreshed))
    }

    private fun sanitize(outcome: MDBListOutcome): MDBListOutcome = when (outcome) {
        is MDBListOutcome.Success -> {
            val error = MDBListHttpClient.errorCode(outcome.response.body)
            if (error == null) outcome else MDBListOutcome.Rejected(error)
        }
        else -> outcome
    }

    private suspend fun readToken(read: suspend () -> String?): String? = try {
        read()?.takeIf { it.isNotBlank() }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        null
    }

    companion object {

        const val WATCHED_PATH: String = "/sync/watched/"

        const val MEDIA_MOVIE: String = "movie"
        const val MEDIA_EPISODE: String = "episode"

        const val PAGE_LIMIT: Int = 1000

        const val NO_AUTHORIZATION: String = "mdblist_no_authorization"

        const val SHAPE_CHANGED: String = "mdblist_watched_history_shape_changed"

        private val EMPTY = org.json.JSONArray()
    }
}
