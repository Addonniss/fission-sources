package dev.infusion.tracking.simkl

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

class SimklWatchedHistory(
    private val client: SimklHttpClient,
    private val auth: SimklAuthRepository,
) : TrackingWatchedHistoryPort {

    override val providerId: String = SimklAuthRepository.SIMKL_PROVIDER_ID

    override fun accountConnected(): Boolean = auth.isConnected()

    override suspend fun readHistory(
        request: TrackingWatchedHistoryRequest,
    ): TrackingInboundOutcome<TrackingWatchedHistoryPage> {
        if (!auth.isConnected()) return TrackingInboundOutcome.Unauthorized(NO_AUTHORIZATION)

        val snapshot = when (val gate = send(SimklRequest(method = SimklMethod.GET, path = ACTIVITIES_PATH))) {
            is SimklOutcome.Success -> parseInstant(JSONObject(gate.response.body).optStringOrNull("all"))
            is SimklOutcome.Unauthorized -> return TrackingInboundOutcome.Unauthorized(gate.reason)
            is SimklOutcome.Retry -> return TrackingInboundOutcome.Unavailable(gate.reason)
            is SimklOutcome.Rejected -> return TrackingInboundOutcome.Rejected(gate.reason)
        }
        val since = request.since
        if (since != null && snapshot != null && !snapshot.isAfter(since)) {

            return TrackingInboundOutcome.Ok(
                TrackingWatchedHistoryPage(records = emptyList(), completedThrough = since),
            )
        }

        val type = when (request.unit) {
            TrackingWatchedHistoryUnit.MOVIE -> MOVIES_TYPE
            TrackingWatchedHistoryUnit.EPISODE -> SHOWS_TYPE
        }
        val buckets = when (request.unit) {

            TrackingWatchedHistoryUnit.MOVIE -> listOf("$COMPLETED_STATUS")

            TrackingWatchedHistoryUnit.EPISODE -> listOf(WATCHING_STATUS, COMPLETED_STATUS)
        }
        val baseQuery = LinkedHashMap<String, String>()
        if (since != null) baseQuery["date_from"] = since.toString()
        when (request.unit) {

            TrackingWatchedHistoryUnit.MOVIE -> Unit
            TrackingWatchedHistoryUnit.EPISODE -> {

                baseQuery["extended"] = "full"
                baseQuery["include_all_episodes"] = "yes"
                baseQuery["episode_watched_at"] = "yes"
            }
        }

        val records = ArrayList<TrackingWatchedHistoryRecord>()
        var malformed = 0
        var undated = 0
        var showLevelOnly = 0
        for (bucket in buckets) {
            val outcome = send(
                SimklRequest(
                    method = SimklMethod.GET,
                    path = "/sync/all-items/$type/$bucket",
                    query = baseQuery,
                    readBudget = SimklReadBudget.WATCHED_HISTORY,
                ),
            )
            val body = when (outcome) {
                is SimklOutcome.Success -> outcome.response.body
                is SimklOutcome.Unauthorized -> return TrackingInboundOutcome.Unauthorized(outcome.reason)
                is SimklOutcome.Retry -> return TrackingInboundOutcome.Unavailable(outcome.reason)
                is SimklOutcome.Rejected -> return TrackingInboundOutcome.Rejected(outcome.reason)
            }
            val root = runCatching { JSONObject(body) }.getOrNull()
                ?: return TrackingInboundOutcome.Rejected(SHAPE_CHANGED)
            val array = root.optJSONArray(type) ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index)
                if (item == null) {
                    malformed++
                    continue
                }
                when (request.unit) {
                    TrackingWatchedHistoryUnit.MOVIE -> {
                        val parsed = parseMovie(item)
                        when (parsed) {
                            null -> malformed++
                            MovieOutcome.Undated -> undated++
                            is MovieOutcome.Watched -> records += parsed.record
                        }
                    }
                    TrackingWatchedHistoryUnit.EPISODE -> {
                        val parsed = parseShow(item)
                        if (parsed == null) {
                            malformed++
                            continue
                        }
                        malformed += parsed.malformed
                        undated += parsed.undated
                        if (parsed.records.isEmpty() && parsed.showLevelOnly) showLevelOnly++
                        records += parsed.records
                    }
                }
            }
        }

        return TrackingInboundOutcome.Ok(
            TrackingWatchedHistoryPage(
                records = records,

                next = null,
                malformed = malformed,
                undated = undated,
                showLevelOnly = showLevelOnly,
                truncating = false,
                completedThrough = snapshot,
            ),
        )
    }

    private fun parseMovie(item: JSONObject): MovieOutcome? {
        val nested = item.optJSONObject("movie") ?: item
        val ids = nested.optJSONObject("ids") ?: item.optJSONObject("ids") ?: return null
        val external = TrackingExternalIds(
            tmdbId = ids.longId("tmdb"),
            imdbId = ids.strId("imdb"),
            tvdbId = ids.longId("tvdb"),
        )
        if (external.isEmpty) return null
        val raw = item.optStringOrNull("last_watched_at") ?: return MovieOutcome.Undated
        val watchedAt = parseInstant(raw) ?: return MovieOutcome.Undated
        return MovieOutcome.Watched(
            TrackingWatchedHistoryRecord(
                unit = TrackingWatchedHistoryUnit.MOVIE,
                externalIds = external,
                watchedAt = watchedAt,

                title = nested.optStringOrNull("title"),
                year = nested.optIntOrNull("year"),
            ),
        )
    }

    private sealed interface MovieOutcome {

        data class Watched(val record: TrackingWatchedHistoryRecord) : MovieOutcome

        data object Undated : MovieOutcome
    }

    private class ShowOutcome(
        val records: List<TrackingWatchedHistoryRecord>,
        val malformed: Int,
        val undated: Int,
        val showLevelOnly: Boolean,
    )

    private fun parseShow(item: JSONObject): ShowOutcome? {
        val nested = item.optJSONObject("show") ?: item
        val ids = nested.optJSONObject("ids") ?: item.optJSONObject("ids") ?: return null
        val external = TrackingExternalIds(
            tmdbId = ids.longId("tmdb"),
            imdbId = ids.strId("imdb"),
            tvdbId = ids.longId("tvdb"),
        )
        if (external.isEmpty) return null
        val seasons = item.optJSONArray("seasons")
        val records = ArrayList<TrackingWatchedHistoryRecord>()
        var malformed = 0
        var undated = 0
        if (seasons != null) {
            for (s in 0 until seasons.length()) {
                val season = seasons.optJSONObject(s) ?: continue
                val seasonNumber = season.optIntOrNull("number") ?: continue
                if (seasonNumber <= 0) continue
                val episodes = season.optJSONArray("episodes") ?: continue
                for (e in 0 until episodes.length()) {
                    val episode = episodes.optJSONObject(e) ?: continue
                    val episodeNumber = episode.optIntOrNull("number") ?: continue
                    if (episodeNumber <= 0) continue
                    val raw = episode.optStringOrNull("watched_at")

                    if (raw == null) continue
                    val watchedAt = parseInstant(raw)
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

                        title = nested.optStringOrNull("title"),
                        year = nested.optIntOrNull("year"),
                    )
                }

                if (season.optJSONArray("episodes") == null) malformed++
            }
        }
        val showLevelOnly = seasons == null && !item.optStringOrNull("last_watched_at").isNullOrBlank()
        return ShowOutcome(
            records = records,
            malformed = malformed,
            undated = undated,
            showLevelOnly = showLevelOnly,
        )
    }

    private fun parseInstant(value: String?): Instant? =
        value?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private suspend fun send(request: SimklRequest): SimklOutcome {
        val token = readToken { auth.accessToken() } ?: return SimklOutcome.Unauthorized(NO_AUTHORIZATION)
        val outcome = client.execute(request, token)
        if (outcome !is SimklOutcome.Unauthorized) return sanitize(outcome)
        val refreshed = readToken { auth.refresh() } ?: return sanitize(outcome)
        return sanitize(client.execute(request, refreshed))
    }

    private fun sanitize(outcome: SimklOutcome): SimklOutcome = when (outcome) {
        is SimklOutcome.Success -> {
            val error = SimklHttpClient.errorCode(outcome.response.body)
            if (error == null) outcome else SimklOutcome.Rejected(error)
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

        const val MOVIES_TYPE: String = "movies"
        const val SHOWS_TYPE: String = "tv_shows"

        const val COMPLETED_STATUS: String = "completed"

        const val WATCHING_STATUS: String = "watching"

        const val ACTIVITIES_PATH: String = "/sync/activities"

        const val NO_AUTHORIZATION: String = "simkl_no_authorization"

        const val SHAPE_CHANGED: String = "simkl_watched_history_shape_changed"
    }
}

private fun JSONObject.longId(key: String): Long? {
    val value = optStringOrNull(key) ?: return null
    return value.toLongOrNull()?.takeIf { it > 0 }
}

private fun JSONObject.strId(key: String): String? =
    optStringOrNull(key)?.takeIf { it.isNotBlank() }
