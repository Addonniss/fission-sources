package dev.infusion.tracking.trakt

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

class TraktPersonalRatings(
    private val client: TraktHttpClient,
    private val auth: TraktAuthRepository,
) : PersonalRatingPort {

    override val providerId: String = TraktAuthRepository.TRAKT_PROVIDER_ID

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
            TraktRequest(
                method = TraktMethod.GET,
                path = ACCOUNT_SETTINGS_PATH,
                readBudget = TraktReadBudget.LOOKUP,
            ),
        )
        val identity = when (outcome) {
            is TraktOutcome.Success -> parseAccountIdentity(outcome.response.body)
            else -> null
        }
        if (identity != null) auth.recordAccountIdentity(identity)
        return identity
    }

    override suspend fun readLibrary(
        request: PersonalRatingLibraryRequest,
    ): TrackingInboundOutcome<PersonalRatingPage> {
        if (!auth.isConnected()) return TrackingInboundOutcome.Unauthorized(NO_AUTHORIZATION)
        val page = if (request.cursor != null) request.cursor.toPageNumber() else request.page
        val limit = request.limit.coerceIn(1, TRAKT_MAX_LIMIT)
        val entries = ArrayList<PersonalRatingEntry>()
        var malformed = 0
        var hasMore = false
        for (type in READ_TYPES) {
            val outcome = send(
                TraktRequest(
                    method = TraktMethod.GET,
                    path = "/users/$AUTHENTICATED_ACCOUNT/ratings/$type",
                    query = mapOf("page" to page.toString(), "limit" to limit.toString()),
                    readBudget = TraktReadBudget.LOOKUP,
                ),
            )
            when (outcome) {
                is TraktOutcome.Success -> {
                    val array = runCatching { JSONArray(outcome.response.body) }.getOrNull()
                        ?: return TrackingInboundOutcome.Rejected(SHAPE_CHANGED)
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: run { malformed++; continue }
                        val entry = parseEntry(item)
                        if (entry == null) malformed++ else entries += entry
                    }
                    if (hasMorePage(outcome.response.headers, page, array.length(), limit)) {
                        hasMore = true
                    }
                }
                is TraktOutcome.Unauthorized -> return TrackingInboundOutcome.Unauthorized(outcome.reason)
                is TraktOutcome.Retry -> return TrackingInboundOutcome.Unavailable(outcome.reason)
                is TraktOutcome.Rejected -> return TrackingInboundOutcome.Rejected(outcome.reason)
            }
        }
        return TrackingInboundOutcome.Ok(
            PersonalRatingPage(
                entries = entries,
                next = if (hasMore) "page:${page + 1}" else null,
                malformed = malformed,
                truncating = hasMore,
            ),
        )
    }

    override suspend fun write(ref: PersonalRatingRef, score: Int): PersonalRatingWriteResult {
        if (score !in SCALE) return PersonalRatingWriteResult.Unsupported(SCORE_OUT_OF_RANGE)
        return when (ref.kind) {
            PersonalRatingKind.MOVIE -> write(
                body = movieBody(ref, score),
            )
            PersonalRatingKind.EPISODE -> {
                val season = ref.seasonNumber
                val episode = ref.episodeNumber
                if (season == null || episode == null) {
                    PersonalRatingWriteResult.Unsupported(EPISODE_NEEDS_COORDINATES)
                } else {
                    write(body = episodeBody(ref, season, episode, score))
                }
            }
        }
    }

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

    private suspend fun write(body: String): PersonalRatingWriteResult =
        outcomeToWrite(send(TraktRequest(method = TraktMethod.POST, path = WRITE_PATH, json = body)))

    private fun outcomeToWrite(outcome: TraktOutcome): PersonalRatingWriteResult = when (outcome) {
        is TraktOutcome.Success -> PersonalRatingWriteResult.Delivered
        is TraktOutcome.Retry -> PersonalRatingWriteResult.Retryable(outcome.reason)
        is TraktOutcome.Rejected -> PersonalRatingWriteResult.Rejected(outcome.reason)
        is TraktOutcome.Unauthorized -> PersonalRatingWriteResult.Unauthorized(outcome.reason)
    }

    private fun parseEntry(item: JSONObject): PersonalRatingEntry? {
        val score = item.optIntOrNull("rating") ?: return null
        if (score < 1) return null
        val movie = item.optJSONObject("movie")
        if (movie != null) {
            val ids = movie.optJSONObject("ids").toExternalIds()
            if (ids.isEmpty) return null
            return PersonalRatingEntry(
                kind = PersonalRatingKind.MOVIE,
                externalIds = ids,
                score = score,
            )
        }
        val episode = item.optJSONObject("episode") ?: return null
        val show = item.optJSONObject("show") ?: return null
        val showIds = show.optJSONObject("ids").toExternalIds()
        if (showIds.isEmpty) return null
        val season = episode.optIntOrNull("season") ?: return null
        val number = episode.optIntOrNull("number") ?: return null
        return PersonalRatingEntry(
            kind = PersonalRatingKind.EPISODE,
            externalIds = showIds,
            score = score,
            seasonNumber = season,
            episodeNumber = number,
        )
    }

    private fun movieBody(ref: PersonalRatingRef, score: Int): String {
        val item = JSONObject().put("rating", score).put("ids", idsObject(ref.externalIds))
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

        private val READ_TYPES: List<String> = listOf("movies", "episodes")

        private val SCALE: IntRange = 1..10

        private const val TRAKT_MAX_LIMIT = 100

        private const val WRITE_PATH = "/sync/ratings"

        private const val ACCOUNT_SETTINGS_PATH = "/users/settings"

        private const val AUTHENTICATED_ACCOUNT = "me"

        internal const val SHAPE_CHANGED = "trakt_ratings_response_shape_changed"
        internal const val NO_AUTHORIZATION = "no valid Trakt authorization for this profile"
        internal const val SCORE_OUT_OF_RANGE = "trakt_personal_rating_scale_is_1_to_10"
        internal const val EPISODE_NEEDS_COORDINATES = "trakt_episode_rating_needs_season_and_episode"

        internal fun hasMorePage(
            headers: Map<String, String>,
            page: Int,
            returnedCount: Int = 0,
            limit: Int = 0,
        ): Boolean {
            val count = headers.entries
                .firstOrNull { it.key.equals("X-Pagination-Page-Count", ignoreCase = true) }
                ?.value?.substringBefore(',')?.trim()?.toIntOrNull()
            if (count != null) return page < count
            return limit > 0 && returnedCount >= limit
        }

        internal fun String.toPageNumber(): Int =
            substringAfterLast(':').toIntOrNull()?.takeIf { it >= 1 } ?: 1

        internal fun parseAccountIdentity(body: String): String? = runCatching {
            val user = JSONObject(body).optJSONObject("user") ?: return@runCatching null
            val ids = user.optJSONObject("ids")
            ids?.optStringOrNull("uuid")
                ?: ids?.optStringOrNull("slug")
                ?: user.optStringOrNull("username")
        }.getOrNull()

        internal fun JSONObject?.toExternalIds(): TrackingExternalIds {
            if (this == null) return TrackingExternalIds()
            return TrackingExternalIds(
                tmdbId = optLongOrNull("tmdb"),
                imdbId = optStringOrNull("imdb"),
                tvdbId = optLongOrNull("tvdb"),
            )
        }
    }
}
