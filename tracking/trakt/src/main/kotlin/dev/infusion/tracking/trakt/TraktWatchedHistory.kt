package dev.infusion.tracking.trakt

import dev.infusion.core.domain.tracking.TrackingExternalIds
import dev.infusion.core.domain.tracking.TrackingInboundOutcome
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryPage
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryPort
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryRecord
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryRequest
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryUnit
import java.time.Instant
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

class TraktWatchedHistory(
    private val client: TraktHttpClient,
    private val auth: TraktAuthRepository,
) : TrackingWatchedHistoryPort {

    override val providerId: String = TraktAuthRepository.TRAKT_PROVIDER_ID

    override fun accountConnected(): Boolean = auth.isConnected()

    override suspend fun readHistory(
        request: TrackingWatchedHistoryRequest,
    ): TrackingInboundOutcome<TrackingWatchedHistoryPage> {
        if (!auth.isConnected()) return TrackingInboundOutcome.Unauthorized(NO_AUTHORIZATION)

        val readStartedAt = Instant.now()
        val page = request.cursor.pageNumber()
        val limit = request.limit.coerceIn(1, PAGE_LIMIT)
        val type = when (request.unit) {
            TrackingWatchedHistoryUnit.MOVIE -> "movies"
            TrackingWatchedHistoryUnit.EPISODE -> "shows"
        }
        val outcome = send(
            TraktRequest(
                method = TraktMethod.GET,
                path = "/sync/watched/$type",
                query = mapOf("page" to page.toString(), "limit" to limit.toString()),
                readBudget = TraktReadBudget.WATCHED_HISTORY,
            ),
        )
        val response = when (outcome) {
            is TraktOutcome.Success -> outcome.response
            is TraktOutcome.Unauthorized -> return TrackingInboundOutcome.Unauthorized(outcome.reason)
            is TraktOutcome.Retry -> return TrackingInboundOutcome.Unavailable(outcome.reason)
            is TraktOutcome.Rejected -> return TrackingInboundOutcome.Rejected(outcome.reason)
        }
        val array = runCatching { JSONArray(response.body) }.getOrNull()
            ?: return TrackingInboundOutcome.Rejected(SHAPE_CHANGED)

        val records = ArrayList<TrackingWatchedHistoryRecord>(array.length())
        var malformed = 0
        var undated = 0
        var showLevelOnly = 0
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
            if (item == null) {
                malformed++
                continue
            }
            when (request.unit) {
                TrackingWatchedHistoryUnit.MOVIE -> {
                    val external = item.optJSONObject("movie")?.optJSONObject("ids").watchedExternalIds()
                    if (external.isEmpty) {
                        malformed++
                        continue
                    }
                    val watchedAt = instantOf(item)
                    if (watchedAt == null) {
                        undated++
                        continue
                    }
                    records += TrackingWatchedHistoryRecord(
                        unit = TrackingWatchedHistoryUnit.MOVIE,
                        externalIds = external,
                        watchedAt = watchedAt,

                        title = item.optJSONObject("movie")?.optStringOrNull("title"),
                        year = item.optJSONObject("movie")?.optIntOrNull("year"),
                    )
                }
                TrackingWatchedHistoryUnit.EPISODE -> {
                    val external = item.optJSONObject("show")?.optJSONObject("ids").watchedExternalIds()
                    if (external.isEmpty) {
                        malformed++
                        continue
                    }
                    val seasons = item.optJSONArray("seasons")
                    if (seasons == null) {

                        if (instantOf(item) != null) showLevelOnly++
                        continue
                    }
                    for (s in 0 until seasons.length()) {
                        val season = seasons.optJSONObject(s) ?: continue
                        val seasonNumber = season.optIntOrNull("number") ?: continue
                        if (seasonNumber <= 0) continue
                        val episodes = season.optJSONArray("episodes") ?: continue
                        for (e in 0 until episodes.length()) {
                            val episode = episodes.optJSONObject(e) ?: continue
                            val episodeNumber = episode.optIntOrNull("number") ?: continue
                            if (episodeNumber <= 0) continue
                            val watchedAt = instantOf(episode)
                            if (watchedAt == null) {
                                undated++
                                continue
                            }
                            records += TrackingWatchedHistoryRecord(
                                unit = TrackingWatchedHistoryUnit.EPISODE,
                                externalIds = external,
                                seasonNumber = seasonNumber,
                                episodeNumber = episodeNumber,
                                watchedAt = watchedAt,

                                title = item.optJSONObject("show")?.optStringOrNull("title"),
                                year = item.optJSONObject("show")?.optIntOrNull("year"),
                            )
                        }
                    }
                }
            }
        }

        val pageCount = response.headers.pageCount()
        val hasNext = pageCount != null && page < pageCount

        val since = request.since
        val covered = since != null && records.isNotEmpty()
        val fresh = if (!covered) records else records.filter { record ->
            val at = record.watchedAt
            at != null && at.isAfter(since)
        }
        val alreadyCovered = records.size - fresh.size
        return TrackingInboundOutcome.Ok(
            TrackingWatchedHistoryPage(
                records = fresh,
                next = if (hasNext) "page:${page + 1}" else null,
                malformed = malformed,
                undated = undated,
                showLevelOnly = showLevelOnly,
                alreadyCovered = alreadyCovered,
                truncating = hasNext,

                completedThrough = if (hasNext) null else readStartedAt,
            ),
        )
    }

    private fun instantOf(container: JSONObject): Instant? =
        container.optStringOrNull("last_watched_at")
            ?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private suspend fun send(request: TraktRequest): TraktOutcome {
        val token = readToken { auth.accessToken() } ?: return TraktOutcome.Unauthorized(NO_AUTHORIZATION)
        val outcome = client.execute(request, token)
        if (outcome !is TraktOutcome.Unauthorized) return outcome
        val refreshed = readToken { auth.refresh() } ?: return outcome
        return client.execute(request, refreshed)
    }

    private suspend fun readToken(read: suspend () -> String?): String? = try {
        read()?.takeIf { it.isNotBlank() }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        null
    }

    companion object {

        const val PAGE_LIMIT: Int = 100

        const val NO_AUTHORIZATION: String = "trakt_no_authorization"

        const val SHAPE_CHANGED: String = "trakt_watched_history_shape_changed"

        private fun String?.pageNumber(): Int =
            this?.substringAfterLast(':')?.toIntOrNull()?.takeIf { it >= 1 } ?: 1

        private fun Map<String, String>.pageCount(): Int? = entries
            .firstOrNull { it.key.equals("X-Pagination-Page-Count", ignoreCase = true) }
            ?.value?.substringBefore(',')?.trim()?.toIntOrNull()?.takeIf { it >= 1 }
    }
}

private fun JSONObject?.watchedExternalIds(): TrackingExternalIds {
    if (this == null) return TrackingExternalIds()
    return TrackingExternalIds(
        tmdbId = optLongOrNull("tmdb"),
        imdbId = optStringOrNull("imdb"),
        tvdbId = optLongOrNull("tvdb"),
    )
}
