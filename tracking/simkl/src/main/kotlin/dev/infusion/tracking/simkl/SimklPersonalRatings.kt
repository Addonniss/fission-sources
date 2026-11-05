package dev.infusion.tracking.simkl

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

class SimklPersonalRatings(
    private val client: SimklHttpClient,
    private val auth: SimklAuthRepository,
) : PersonalRatingPort {

    override val providerId: String = SimklAuthRepository.SIMKL_PROVIDER_ID

    override fun capabilities(): PersonalRatingCapabilities = PersonalRatingCapabilities(
        read = true,
        writeKinds = setOf(PersonalRatingKind.MOVIE),
        scaleMin = 1,
        scaleMax = 10,
        sideEffectNote = AUTO_MOVE_NOTE,
    )

    override fun accountConnected(): Boolean = auth.isConnected()

    override suspend fun resolveAccountIdentity(): String? {
        if (!auth.isConnected()) return null
        auth.accountId()?.let { return it }
        val outcome = send(
            SimklRequest(
                method = SimklMethod.GET,
                path = ACCOUNT_SETTINGS_PATH,
                readBudget = SimklReadBudget.LOOKUP,
            ),
        )
        val identity = when (outcome) {
            is SimklOutcome.Success -> parseAccountIdentity(outcome.response.body)
            else -> null
        }
        if (identity != null) auth.recordAccountIdentity(identity)
        return identity
    }

    override suspend fun readLibrary(
        request: PersonalRatingLibraryRequest,
    ): TrackingInboundOutcome<PersonalRatingPage> {
        val outcome = send(
            SimklRequest(
                method = SimklMethod.GET,
                path = RATED_MOVIES_PATH,
                readBudget = SimklReadBudget.LOOKUP,
            ),
        )
        return when (outcome) {
            is SimklOutcome.Success -> {
                val root = runCatching { JSONObject(outcome.response.body) }.getOrNull()
                    ?: return TrackingInboundOutcome.Rejected(SHAPE_CHANGED)
                val entries = ArrayList<PersonalRatingEntry>()
                var malformed = 0
                val array = root.optJSONArray(MOVIES_KEY)
                if (array != null) {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index)
                        if (item == null) {
                            malformed++
                            continue
                        }
                        val entry = parseRatedMovie(item)
                        if (entry == null) malformed++ else entries += entry
                    }
                }
                TrackingInboundOutcome.Ok(
                    PersonalRatingPage(
                        entries = entries,

                        next = null,
                        malformed = malformed,
                        truncating = false,
                    ),
                )
            }
            is SimklOutcome.Unauthorized -> TrackingInboundOutcome.Unauthorized(outcome.reason)
            is SimklOutcome.Retry -> TrackingInboundOutcome.Unavailable(outcome.reason)
            is SimklOutcome.Rejected -> TrackingInboundOutcome.Rejected(outcome.reason)
        }
    }

    override suspend fun write(ref: PersonalRatingRef, score: Int): PersonalRatingWriteResult {
        if (ref.kind != PersonalRatingKind.MOVIE) {
            return PersonalRatingWriteResult.Unsupported(EPISODE_UNSUPPORTED)
        }
        if (score !in SCALE) return PersonalRatingWriteResult.Unsupported(SCORE_OUT_OF_RANGE)
        return outcomeToWrite(
            send(
                SimklRequest(
                    method = SimklMethod.POST,
                    path = WRITE_PATH,
                    json = movieBody(ref, score),
                ),
            ),
        )
    }

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

    private fun outcomeToWrite(outcome: SimklOutcome): PersonalRatingWriteResult = when (outcome) {
        is SimklOutcome.Success -> PersonalRatingWriteResult.Delivered
        is SimklOutcome.Retry -> PersonalRatingWriteResult.Retryable(outcome.reason)
        is SimklOutcome.Rejected -> PersonalRatingWriteResult.Rejected(outcome.reason)
        is SimklOutcome.Unauthorized -> PersonalRatingWriteResult.Unauthorized(outcome.reason)
    }

    private fun parseRatedMovie(item: JSONObject): PersonalRatingEntry? {
        val score = item.optIntOrNull(USER_RATING_KEY) ?: return null
        if (score < 1) return null
        val ids = item.optJSONObject(MOVIE_KEY)?.optJSONObject(IDS_KEY).toExternalIds()
        if (ids.isEmpty) return null
        return PersonalRatingEntry(
            kind = PersonalRatingKind.MOVIE,
            externalIds = ids,
            score = score,
        )
    }

    private fun movieBody(ref: PersonalRatingRef, score: Int): String {
        val item = JSONObject().put("rating", score).put("ids", idsObject(ref.externalIds))
        return JSONObject().put("movies", JSONArray().put(item)).toString()
    }

    private fun idsObject(ids: TrackingExternalIds): JSONObject {
        val out = JSONObject()
        ids.tmdbId?.let { out.put("tmdb", it) }
        ids.imdbId?.let { out.put("imdb", it) }
        ids.tvdbId?.let { out.put("tvdb", it) }
        return out
    }

    internal companion object {
        private val SCALE: IntRange = 1..10

        private const val WRITE_PATH = "/sync/ratings"

        private const val RATED_MOVIES_PATH = "/sync/ratings/movies/1,2,3,4,5,6,7,8,9,10"

        private const val ACCOUNT_SETTINGS_PATH = "/users/settings"

        private const val MOVIES_KEY = "movies"
        private const val MOVIE_KEY = "movie"
        private const val IDS_KEY = "ids"
        private const val USER_RATING_KEY = "user_rating"

        internal const val AUTO_MOVE_NOTE =
            "Simkl will add this title to your list as a side effect of rating it."

        internal const val SHAPE_CHANGED = "simkl_ratings_response_shape_changed"
        internal const val NO_AUTHORIZATION = "no valid Simkl authorization for this profile"
        internal const val EPISODE_UNSUPPORTED = "simkl_documents_no_exact_episode_personal_rating"
        internal const val SCORE_OUT_OF_RANGE = "simkl_personal_rating_scale_is_1_to_10"
    }
}

private fun JSONObject?.toExternalIds(): TrackingExternalIds {
    if (this == null) return TrackingExternalIds()
    return TrackingExternalIds(
        tmdbId = numericId("tmdb"),
        imdbId = optStringOrNull("imdb"),
        tvdbId = numericId("tvdb"),
    )
}

private fun parseAccountIdentity(body: String): String? {
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
    val id = root.optJSONObject("account")?.numericId("id")
        ?: root.optJSONObject("user")?.numericId("id")
    return id?.toString()
}

private fun JSONObject.numericId(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return when (val value = opt(key)) {
        is Number -> value.toLong().takeIf { it != 0L }
        is String -> value.trim().toLongOrNull()?.takeIf { it != 0L }
        else -> null
    }
}
