package dev.infusion.tracking.mdblist

import dev.infusion.core.domain.ratings.PersonalRatingCapabilities
import dev.infusion.core.domain.ratings.PersonalRatingEntry
import dev.infusion.core.domain.ratings.PersonalRatingLibraryRequest
import dev.infusion.core.domain.ratings.PersonalRatingPage
import dev.infusion.core.domain.ratings.PersonalRatingPort
import dev.infusion.core.domain.ratings.PersonalRatingRef
import dev.infusion.core.domain.ratings.PersonalRatingWriteResult
import dev.infusion.core.domain.tracking.TrackingExternalIds
import dev.infusion.core.domain.tracking.TrackingInboundOutcome
import dev.infusion.core.model.sync.PersonalRatingKind
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

class MDBListPersonalRatings(
    private val client: MDBListHttpClient,
    private val auth: MDBListAuthRepository,
) : PersonalRatingPort {

    override val providerId: String = MDBListAuthRepository.MDBLIST_PROVIDER_ID

    override fun capabilities(): PersonalRatingCapabilities = PersonalRatingCapabilities(
        read = true,
        writeKinds = setOf(PersonalRatingKind.MOVIE, PersonalRatingKind.EPISODE),
        scaleMin = 1,
        scaleMax = 10,
        sideEffectNote = null,
    )

    override fun accountConnected(): Boolean = auth.isConnected()

    override suspend fun resolveAccountIdentity(): String? {
        if (!auth.isConnected()) return null
        auth.accountId()?.let { return it }
        val outcome = send(
            MDBListRequest(
                method = MDBListMethod.GET,
                path = ACCOUNT_PATH,
                readBudget = MDBListReadBudget.LOOKUP,
            ),
        )
        val identity = when (outcome) {
            is MDBListOutcome.Success -> parseAccountIdentity(outcome.response.body)
            else -> null
        }
        if (identity != null) auth.recordAccountIdentity(identity)
        return identity
    }

    override suspend fun readLibrary(
        request: PersonalRatingLibraryRequest,
    ): TrackingInboundOutcome<PersonalRatingPage> {
        val limit = request.limit.coerceIn(1, MAX_LIMIT)
        val query = buildMap {
            put("limit", limit.toString())
            request.cursor?.takeIf { it.isNotBlank() }?.let { put("cursor", it) }
        }
        val outcome = send(
            MDBListRequest(
                method = MDBListMethod.GET,
                path = RATINGS_PATH,
                query = query,
                readBudget = MDBListReadBudget.LOOKUP,
            ),
        )
        return when (outcome) {
            is MDBListOutcome.Success -> {
                val root = runCatching { JSONObject(outcome.response.body) }.getOrNull()
                    ?: return TrackingInboundOutcome.Rejected(SHAPE_CHANGED)
                val entries = ArrayList<PersonalRatingEntry>()
                var malformed = 0
                malformed += parseEntries(root.optJSONArray(MOVIES_KEY), PersonalRatingKind.MOVIE, entries)
                malformed += parseEntries(root.optJSONArray(EPISODES_KEY), PersonalRatingKind.EPISODE, entries)
                val next = root.optJSONObject(PAGINATION_KEY)?.optStringOrNull(NEXT_CURSOR_KEY)
                TrackingInboundOutcome.Ok(
                    PersonalRatingPage(
                        entries = entries,
                        next = next,
                        malformed = malformed,
                        truncating = next != null,
                    ),
                )
            }
            is MDBListOutcome.Unauthorized -> TrackingInboundOutcome.Unauthorized(outcome.reason)
            is MDBListOutcome.Retry -> TrackingInboundOutcome.Unavailable(outcome.reason)
            is MDBListOutcome.Rejected -> TrackingInboundOutcome.Rejected(outcome.reason)
        }
    }

    override suspend fun write(ref: PersonalRatingRef, score: Int): PersonalRatingWriteResult {
        if (score !in SCALE) return PersonalRatingWriteResult.Unsupported(SCORE_OUT_OF_RANGE)
        val body = when (ref.kind) {
            PersonalRatingKind.MOVIE -> movieBody(ref, score)
            PersonalRatingKind.EPISODE -> {
                val season = ref.seasonNumber
                val episode = ref.episodeNumber
                if (season == null || episode == null) {
                    return PersonalRatingWriteResult.Unsupported(EPISODE_NEEDS_COORDINATES)
                }
                episodeBody(ref, season, episode, score)
            }
        }
        return outcomeToWrite(
            send(MDBListRequest(method = MDBListMethod.POST, path = RATINGS_PATH, json = body)),
        )
    }

    private suspend fun send(request: MDBListRequest): MDBListOutcome {
        val token = readToken { auth.accessToken() } ?: return MDBListOutcome.Unauthorized(NO_AUTHORIZATION)
        val outcome = client.execute(request, token)
        if (outcome !is MDBListOutcome.Unauthorized) return outcome
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

    private fun outcomeToWrite(outcome: MDBListOutcome): PersonalRatingWriteResult = when (outcome) {
        is MDBListOutcome.Success -> PersonalRatingWriteResult.Delivered
        is MDBListOutcome.Retry -> PersonalRatingWriteResult.Retryable(outcome.reason)
        is MDBListOutcome.Rejected -> PersonalRatingWriteResult.Rejected(outcome.reason)
        is MDBListOutcome.Unauthorized -> PersonalRatingWriteResult.Unauthorized(outcome.reason)
    }

    private fun parseEntries(
        array: JSONArray?,
        kind: PersonalRatingKind,
        entries: MutableList<PersonalRatingEntry>,
    ): Int {
        if (array == null) return 0
        var malformed = 0
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
            if (item == null) {
                malformed++
                continue
            }
            val entry = parseEntry(item, kind)
            if (entry == null) malformed++ else entries += entry
        }
        return malformed
    }

    private fun parseEntry(item: JSONObject, kind: PersonalRatingKind): PersonalRatingEntry? {
        val whole = item.optIntOrNull(RATING_KEY)?.takeIf { it in SCALE }
        val precise = item.optDoubleOrNull(RATING_PRECISE_KEY)
            ?.takeIf { it.isFinite() && it in 1.0..10.0 }

        val score = whole
        val preciseOnly = if (whole == null) precise else null
        if (score == null && preciseOnly == null) return null
        val nested = when (kind) {
            PersonalRatingKind.MOVIE -> item.optJSONObject(MOVIE_KEY)
            PersonalRatingKind.EPISODE -> item.optJSONObject(SHOW_KEY)
        }
        val ids = (nested?.optJSONObject(IDS_KEY) ?: item.optJSONObject(IDS_KEY)).toExternalIds()
        if (ids.isEmpty) return null
        return when (kind) {
            PersonalRatingKind.MOVIE -> PersonalRatingEntry(
                kind = PersonalRatingKind.MOVIE,
                externalIds = ids,
                score = score,
                preciseScore = preciseOnly,
            )
            PersonalRatingKind.EPISODE -> {
                val season = item.optIntOrNull(SEASON_KEY) ?: item.optIntOrNull(SEASON_NUMBER_KEY)
                val episode = item.optIntOrNull(EPISODE_KEY) ?: item.optIntOrNull(EPISODE_NUMBER_KEY)
                if (season == null || season <= 0 || episode == null || episode <= 0) return null
                PersonalRatingEntry(
                    kind = PersonalRatingKind.EPISODE,
                    externalIds = ids,
                    score = score,
                    seasonNumber = season,
                    episodeNumber = episode,
                    preciseScore = preciseOnly,
                )
            }
        }
    }

    private fun movieBody(ref: PersonalRatingRef, score: Int): String {
        val item = JSONObject().put("ids", idsObject(ref.externalIds)).put("rating", score)
        return JSONObject().put("movies", JSONArray().put(item)).toString()
    }

    private fun episodeBody(ref: PersonalRatingRef, season: Int, episode: Int, score: Int): String {
        val episodeObject = JSONObject().put("number", episode).put("rating", score)
        val seasonObject = JSONObject()
            .put("number", season)
            .put("episodes", JSONArray().put(episodeObject))
        val showObject = JSONObject()
            .put("ids", idsObject(ref.externalIds))
            .put("seasons", JSONArray().put(seasonObject))
        return JSONObject().put("shows", JSONArray().put(showObject)).toString()
    }

    private fun idsObject(ids: TrackingExternalIds): JSONObject {
        val out = JSONObject()
        ids.tmdbId?.let { out.put("tmdb", it) }
        ids.imdbId?.let { out.put("imdb", it) }
        ids.tvdbId?.let { out.put("tvdb", it) }
        return out
    }

    private companion object {
        private val SCALE: IntRange = 1..10

        private const val MAX_LIMIT = 1000

        private const val RATINGS_PATH = "/sync/ratings"

        private const val ACCOUNT_PATH = "/user"

        private const val MOVIES_KEY = "movies"
        private const val EPISODES_KEY = "episodes"
        private const val MOVIE_KEY = "movie"
        private const val SHOW_KEY = "show"
        private const val IDS_KEY = "ids"
        private const val RATING_KEY = "rating"
        private const val RATING_PRECISE_KEY = "rating_precise"
        private const val SEASON_KEY = "season"
        private const val SEASON_NUMBER_KEY = "season_number"
        private const val EPISODE_KEY = "episode"
        private const val EPISODE_NUMBER_KEY = "episode_number"
        private const val PAGINATION_KEY = "pagination"
        private const val NEXT_CURSOR_KEY = "next_cursor"

        internal const val SHAPE_CHANGED = "mdblist_ratings_response_shape_changed"
        internal const val NO_AUTHORIZATION = "no valid MDBList authorization for this profile"
        internal const val SCORE_OUT_OF_RANGE = "mdblist_personal_rating_scale_is_1_to_10"
        internal const val EPISODE_NEEDS_COORDINATES = "mdblist_episode_rating_needs_season_and_episode"
    }
}

private fun JSONObject?.toExternalIds(): TrackingExternalIds {
    if (this == null) return TrackingExternalIds()
    return TrackingExternalIds(
        tmdbId = optLongOrNull("tmdb"),
        imdbId = optStringOrNull("imdb"),
        tvdbId = optLongOrNull("tvdb"),
    )
}

private fun JSONObject.optDoubleOrNull(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return when (val value = opt(key)) {
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull()
        else -> null
    }
}

private fun parseAccountIdentity(body: String): String? {
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
    val id = root.optLongOrNull("user_id")
    return id?.toString() ?: root.optStringOrNull("username")
}
