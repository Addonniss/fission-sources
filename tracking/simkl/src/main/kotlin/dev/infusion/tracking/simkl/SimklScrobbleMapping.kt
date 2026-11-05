package dev.infusion.tracking.simkl

import dev.infusion.core.domain.tracking.TrackingMediaKind
import dev.infusion.core.domain.tracking.TrackingMediaReference
import dev.infusion.core.domain.tracking.TrackingScrobbleAction
import dev.infusion.core.domain.tracking.TrackingScrobbleEvent
import java.math.BigDecimal
import java.math.RoundingMode
import org.json.JSONArray
import org.json.JSONObject

object SimklScrobbleMapping {

    const val START_PATH: String = "/scrobble/start"
    const val PAUSE_PATH: String = "/scrobble/pause"
    const val STOP_PATH: String = "/scrobble/stop"

    const val HISTORY_PATH: String = "/sync/history"

    fun pathFor(action: TrackingScrobbleAction): String = when (action) {
        TrackingScrobbleAction.START, TrackingScrobbleAction.RESUME -> START_PATH
        TrackingScrobbleAction.PAUSE -> PAUSE_PATH
        TrackingScrobbleAction.STOP, TrackingScrobbleAction.COMPLETE -> STOP_PATH
        TrackingScrobbleAction.MARK_WATCHED -> HISTORY_PATH
    }

    fun requestFor(event: TrackingScrobbleEvent): SimklRequest = SimklRequest(
        method = SimklMethod.POST,
        path = pathFor(event.action),
        json = bodyFor(event),
        authenticated = true,
        retryable = true,

        conflictIsSuccess = event.action == TrackingScrobbleAction.STOP ||
            event.action == TrackingScrobbleAction.COMPLETE,
    )

    fun bodyFor(event: TrackingScrobbleEvent): String = when (event.action) {
        TrackingScrobbleAction.MARK_WATCHED -> historyBody(event.media)
        else -> scrobbleBody(event)
    }

    private fun scrobbleBody(event: TrackingScrobbleEvent): String {
        val media = event.media
        val body = JSONObject()
            .put("progress", formatProgress(event.progressPercent))
        val mediaObject = mediaObject(media)
        when (media.kind) {
            TrackingMediaKind.MOVIE -> body.put("movie", mediaObject)
            TrackingMediaKind.SHOW -> {
                body.put("show", mediaObject)
                body.put(
                    "episode",
                    JSONObject()
                        .put("season", media.seasonNumber)
                        .put("number", media.episodeNumber),
                )
            }
        }
        return body.toString()
    }

    private fun historyBody(media: TrackingMediaReference): String {
        val mediaObject = mediaObject(media)
        return when (media.kind) {
            TrackingMediaKind.MOVIE -> JSONObject()
                .put("movies", JSONArray().put(mediaObject))
                .toString()
            TrackingMediaKind.SHOW -> JSONObject()
                .put(
                    "shows",
                    JSONArray().put(
                        mediaObject.put(
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

    private fun mediaObject(media: TrackingMediaReference): JSONObject {
        val mediaObject = JSONObject().put("ids", idsObject(media))
        media.title?.takeIf { it.isNotBlank() }?.let { mediaObject.put("title", it) }
        media.year?.let { mediaObject.put("year", it) }
        return mediaObject
    }

    private fun idsObject(media: TrackingMediaReference): JSONObject {
        val ids = JSONObject()
        media.externalIds.tmdbId?.let { ids.put("tmdb", it) }
        media.externalIds.imdbId?.let { ids.put("imdb", it) }
        media.externalIds.tvdbId?.let { ids.put("tvdb", it) }
        return ids
    }

    fun formatProgress(percent: Double): BigDecimal =
        BigDecimal(percent.coerceIn(0.0, 100.0)).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros()
}
