package dev.infusion.tracking.mdblist

import dev.infusion.core.domain.tracking.TrackingMediaKind
import dev.infusion.core.domain.tracking.TrackingMediaReference
import dev.infusion.core.domain.tracking.TrackingScrobbleAction
import dev.infusion.core.domain.tracking.TrackingScrobbleEvent
import java.math.BigDecimal
import java.math.RoundingMode
import org.json.JSONArray
import org.json.JSONObject

object MDBListScrobbleMapping {

    const val START_PATH: String = "/scrobble/start"
    const val PAUSE_PATH: String = "/scrobble/pause"
    const val STOP_PATH: String = "/scrobble/stop"

    const val WATCHED_PATH: String = "/sync/watched"

    fun pathFor(action: TrackingScrobbleAction): String = when (action) {
        TrackingScrobbleAction.START, TrackingScrobbleAction.RESUME -> START_PATH
        TrackingScrobbleAction.PAUSE -> PAUSE_PATH
        TrackingScrobbleAction.STOP, TrackingScrobbleAction.COMPLETE -> STOP_PATH
        TrackingScrobbleAction.MARK_WATCHED -> WATCHED_PATH
    }

    fun requestFor(event: TrackingScrobbleEvent): MDBListRequest = MDBListRequest(
        method = MDBListMethod.POST,
        path = pathFor(event.action),
        json = bodyFor(event),
        authenticated = true,
        retryable = true,
    )

    fun bodyFor(event: TrackingScrobbleEvent): String = when (event.action) {
        TrackingScrobbleAction.MARK_WATCHED -> watchedBody(event.media)
        else -> scrobbleBody(event)
    }

    private fun scrobbleBody(event: TrackingScrobbleEvent): String {
        val media = event.media
        val body = JSONObject().put("progress", formatProgress(event.progressPercent))
        when (media.kind) {
            TrackingMediaKind.MOVIE -> body.put("movie", idsContainer(media))
            TrackingMediaKind.SHOW -> {
                val show = idsContainer(media)
                show.put("season", media.seasonNumber)
                show.put("episode", media.episodeNumber)
                body.put("show", show)
            }
        }
        return body.toString()
    }

    private fun watchedBody(media: TrackingMediaReference): String {
        val container = idsContainer(media)
        return when (media.kind) {
            TrackingMediaKind.MOVIE -> JSONObject()
                .put("movies", JSONArray().put(container))
                .toString()
            TrackingMediaKind.SHOW -> JSONObject()
                .put(
                    "shows",
                    JSONArray().put(
                        container.put(
                            "seasons",
                            JSONArray().put(
                                JSONObject()
                                    .put("number", media.seasonNumber)
                                    .put(
                                        "episodes",
                                        JSONArray().put(JSONObject().put("number", media.episodeNumber)),
                                    ),
                            ),
                        ),
                    ),
                )
                .toString()
        }
    }

    private fun idsContainer(media: TrackingMediaReference): JSONObject =
        JSONObject().put("ids", idsObject(media))

    private fun idsObject(media: TrackingMediaReference): JSONObject {
        val ids = JSONObject()
        media.externalIds.tmdbId?.let { ids.put("tmdb", it) }
        media.externalIds.imdbId?.let { ids.put("imdb", it) }
        media.externalIds.tvdbId?.let { ids.put("tvdb", it) }
        return ids
    }

    fun formatProgress(percent: Double): BigDecimal = BigDecimal(plainProgress(percent))

    private fun plainProgress(percent: Double): String =
        BigDecimal(percent.coerceIn(0.0, 100.0))
            .setScale(2, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
}
