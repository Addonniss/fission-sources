package dev.infusion.tracking.wetrakr

import dev.infusion.core.domain.tracking.TrackingDeliveryResult
import dev.infusion.core.domain.tracking.TrackingExternalIds
import dev.infusion.core.domain.tracking.TrackingMediaKind
import dev.infusion.core.domain.tracking.TrackingMediaReference
import dev.infusion.core.domain.tracking.TrackingScrobbleAction
import dev.infusion.core.domain.tracking.TrackingScrobbleEvent
import dev.infusion.core.domain.watchlist.WatchlistKind
import dev.infusion.core.domain.watchlist.WatchlistMembershipRef
import dev.infusion.core.domain.watchlist.WatchlistRemoteKind
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

sealed interface WeTrakrWriteOutcome {

    data object Delivered : WeTrakrWriteOutcome

    data class Rejected(val reason: String) : WeTrakrWriteOutcome

    data object AlreadyHeld : WeTrakrWriteOutcome
}

object WeTrakrScrobbleMapping {

    fun scrobbleRequest(event: TrackingScrobbleEvent, appVersion: String): WeTrakrRequest? {
        val path = when (event.action) {
            TrackingScrobbleAction.START -> WeTrakrHttpClient.SCROBBLE_START_PATH

            TrackingScrobbleAction.RESUME -> WeTrakrHttpClient.SCROBBLE_START_PATH
            TrackingScrobbleAction.PAUSE -> WeTrakrHttpClient.SCROBBLE_PAUSE_PATH

            TrackingScrobbleAction.STOP,
            TrackingScrobbleAction.COMPLETE,
            -> WeTrakrHttpClient.SCROBBLE_STOP_PATH
            TrackingScrobbleAction.MARK_WATCHED -> return null
        }
        val body = JSONObject()
            .put("progress", progressOf(event.progressPercent))
            .put("app_version", appVersion)
        putMedia(body, event.media)
        return WeTrakrRequest(
            method = WeTrakrMethod.POST,
            path = path,
            json = body.toString(),
            authenticated = true,
        )
    }

    fun watchedWriteRequest(media: TrackingMediaReference, occurredAt: Instant): WeTrakrRequest {
        val timestamp = instantOf(occurredAt)
        val body = JSONObject().put("allow_rewatch", false)
        when (media.kind) {
            TrackingMediaKind.MOVIE -> {
                val item = JSONObject()
                    .put("ids", idsOf(media.externalIds))
                    .put("status", STATUS_WATCHED)
                    .put("tracked_at", timestamp)
                media.title?.takeIf { it.isNotBlank() }?.let { item.put("title", it) }
                media.year?.let { item.put("year", it) }
                body.put("movies", JSONArray().put(item))
            }
            TrackingMediaKind.SHOW -> {
                val episode = JSONObject()
                    .put("number", media.episodeNumber)
                    .put("status", STATUS_WATCHED)
                    .put("tracked_at", timestamp)
                val season = JSONObject()
                    .put("number", media.seasonNumber)
                    .put("episodes", JSONArray().put(episode))
                val show = JSONObject()
                    .put("ids", idsOf(media.externalIds))
                    .put("seasons", JSONArray().put(season))
                media.title?.takeIf { it.isNotBlank() }?.let { show.put("title", it) }
                media.year?.let { show.put("year", it) }
                body.put("shows", JSONArray().put(show))
            }
        }
        return WeTrakrRequest(
            method = WeTrakrMethod.POST,
            path = WeTrakrHttpClient.TRACKING_WRITE_PATH,
            json = body.toString(),
            authenticated = true,
        )
    }

    fun scrobbleOutcome(response: WeTrakrHttpResponse): WeTrakrWriteOutcome {
        val body = runCatching { JSONObject(response.body) }.getOrNull()
            ?: return WeTrakrWriteOutcome.Rejected(UNREADABLE_ANSWER)
        if (body.optBoolean("ignored", false)) {
            return WeTrakrWriteOutcome.Rejected(FILTERED_BY_PROVIDER)
        }
        val action = body.optString("action", "").trim()
        return when {
            action == ACTION_START || action == ACTION_PAUSE ||
                action == ACTION_SCROBBLE || action == ACTION_CHECKIN ||
                action == ACTION_CANCEL -> WeTrakrWriteOutcome.Delivered

            body.optString("error", "").isNotEmpty() ->
                WeTrakrWriteOutcome.Rejected(errorReason(body))
            else -> WeTrakrWriteOutcome.Rejected(UNREADABLE_ANSWER)
        }
    }

    fun watchedWriteOutcome(response: WeTrakrHttpResponse): WeTrakrWriteOutcome {
        val body = runCatching { JSONObject(response.body) }.getOrNull()
            ?: return WeTrakrWriteOutcome.Rejected(UNREADABLE_ANSWER)
        if (body.has(KEY_NOT_FOUND) && countsIn(body.optJSONObject(KEY_NOT_FOUND)) > 0) {

            return WeTrakrWriteOutcome.Rejected(NOT_FOUND_REASON)
        }
        val errored = body.optJSONObject(KEY_ERRORED)
        if (errored != null) {
            val reason = firstErrorReason(errored)
            if (reason != null) {
                return if (isAlreadyHeld(reason)) {
                    WeTrakrWriteOutcome.AlreadyHeld
                } else {
                    WeTrakrWriteOutcome.Rejected(redact(reason))
                }
            }
        }
        return when {
            countsIn(body.optJSONObject(KEY_ADDED)) > 0 -> WeTrakrWriteOutcome.Delivered

            countsIn(body.optJSONObject(KEY_EXISTING)) > 0 -> WeTrakrWriteOutcome.AlreadyHeld

            else -> WeTrakrWriteOutcome.Rejected(UNREADABLE_ANSWER)
        }
    }

    fun deliveryResult(outcome: WeTrakrWriteOutcome): TrackingDeliveryResult = when (outcome) {
        WeTrakrWriteOutcome.Delivered -> TrackingDeliveryResult.Delivered

        WeTrakrWriteOutcome.AlreadyHeld -> TrackingDeliveryResult.Delivered
        is WeTrakrWriteOutcome.Rejected -> TrackingDeliveryResult.Rejected(outcome.reason)
    }

    fun resumeClearRequest(ref: WatchlistMembershipRef): WeTrakrRequest? {
        val kind = when {
            ref.remoteKind == WatchlistRemoteKind.EPISODE -> TrackingMediaKind.SHOW
            ref.kind == WatchlistKind.SHOW -> TrackingMediaKind.SHOW
            else -> TrackingMediaKind.MOVIE
        }
        if (kind == TrackingMediaKind.SHOW && (ref.seasonNumber == null || ref.episodeNumber == null)) {
            return null
        }

        val ids = WeTrakrWatchlistMapping.provenIds(ref.externalIds)
        if (ids.length() == 0) return null
        val body = JSONObject()
        putIdentity(
            body = body,
            kind = kind,
            ids = ids,
            title = null,
            year = null,
            seasonNumber = ref.seasonNumber,
            episodeNumber = ref.episodeNumber,
        )
        return WeTrakrRequest(
            method = WeTrakrMethod.DELETE,
            path = WeTrakrHttpClient.SCROBBLE_PLAYING_PATH,
            json = body.toString(),
            authenticated = true,
        )
    }

    fun resumeClearOutcome(response: WeTrakrHttpResponse): WeTrakrWriteOutcome {
        val body = runCatching { JSONObject(response.body) }.getOrNull()
            ?: return WeTrakrWriteOutcome.Rejected(UNREADABLE_ANSWER)
        val action = body.optString("action", "").trim()
        return if (action == ACTION_CANCEL) {
            WeTrakrWriteOutcome.Delivered
        } else {

            WeTrakrWriteOutcome.Rejected(UNREADABLE_ANSWER)
        }
    }

    private fun putMedia(body: JSONObject, media: TrackingMediaReference) {
        putIdentity(
            body = body,
            kind = media.kind,
            ids = idsOf(media.externalIds),
            title = media.title,
            year = media.year,
            seasonNumber = media.seasonNumber,
            episodeNumber = media.episodeNumber,
        )
    }

    private fun putIdentity(
        body: JSONObject,
        kind: TrackingMediaKind,
        ids: JSONObject,
        title: String?,
        year: Int?,
        seasonNumber: Int?,
        episodeNumber: Int?,
    ) {
        when (kind) {
            TrackingMediaKind.MOVIE -> {
                val movie = JSONObject().put("ids", ids)
                title?.takeIf { it.isNotBlank() }?.let { movie.put("title", it) }
                year?.let { movie.put("year", it) }
                body.put("movie", movie)
            }
            TrackingMediaKind.SHOW -> {
                val show = JSONObject().put("ids", ids)
                title?.takeIf { it.isNotBlank() }?.let { show.put("title", it) }
                year?.let { show.put("year", it) }
                body.put("show", show)
                body.put(
                    "episode",
                    JSONObject()
                        .put("season", seasonNumber)
                        .put("number", episodeNumber),
                )
            }
        }
    }

    private fun idsOf(ids: TrackingExternalIds): JSONObject = JSONObject().apply {
        ids.tmdbId?.let { put("tmdb", it) }
        ids.imdbId?.let { put("imdb", it) }
        ids.tvdbId?.let { put("tvdb", it) }
    }

    private fun progressOf(percent: Double): Double {
        val clamped = percent.coerceIn(0.0, 100.0)
        return BigDecimal.valueOf(clamped).setScale(1, java.math.RoundingMode.HALF_UP).toDouble()
    }

    private fun instantOf(instant: Instant): String =
        ISO_MILLIS.format(instant.atOffset(ZoneOffset.UTC))

    private fun countsIn(bucket: JSONObject?): Int {
        if (bucket == null) return 0
        var total = 0
        for (key in bucket.keys()) {
            val value = bucket.opt(key)
            total += when (value) {
                is JSONArray -> value.length()
                is JSONObject -> if (value.has("error")) 1 else countsIn(value)
                is Number -> value.toInt()
                else -> 0
            }
        }
        return total
    }

    private fun firstErrorReason(errored: JSONObject): String? {
        for (key in errored.keys()) {
            val value = errored.opt(key) ?: continue
            when (value) {
                is JSONArray -> for (index in 0 until value.length()) {
                    val reason = value.optJSONObject(index)?.optString("error", "")?.trim()
                    if (!reason.isNullOrEmpty()) return reason
                }
                is JSONObject -> value.optString("error", "").trim().takeIf { it.isNotEmpty() }?.let { return it }
                else -> Unit
            }
        }
        return null
    }

    private fun isAlreadyHeld(reason: String): Boolean {
        val normalized = reason.removePrefix("Already").trim().lowercase()
        return reason.startsWith("Already") &&
            (normalized.startsWith("watched") || normalized.startsWith("added"))
    }

    private fun redact(reason: String): String {
        val cleaned = reason.replace('\n', ' ').replace('"', '\'').trim()
        return if (cleaned.length <= MAX_REASON_LENGTH) {
            cleaned
        } else {
            cleaned.substring(0, MAX_REASON_LENGTH) + "…"
        }
    }

    private fun errorReason(body: JSONObject): String {
        val error = body.optString("error", "").trim()
        val message = body.optString("message", "").trim()
        val reason = if (message.isEmpty()) error else "$error: $message"
        return redact(reason.ifEmpty { UNREADABLE_ANSWER })
    }

    private const val STATUS_WATCHED = "watched"

    private const val ACTION_START = "start"
    private const val ACTION_PAUSE = "pause"
    private const val ACTION_SCROBBLE = "scrobble"
    private const val ACTION_CHECKIN = "checkin"
    private const val ACTION_CANCEL = "cancel"

    private const val KEY_ADDED = "added"
    private const val KEY_EXISTING = "existing"
    private const val KEY_ERRORED = "errored"
    private const val KEY_NOT_FOUND = "notFound"

    private const val MAX_REASON_LENGTH = 96

    internal const val FILTERED_BY_PROVIDER = "wetrakr_filtered_event"
    internal const val NOT_FOUND_REASON = "wetrakr_identity_not_found"
    internal const val UNREADABLE_ANSWER = "wetrakr_write_answer_unreadable"

    private val ISO_MILLIS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
}
