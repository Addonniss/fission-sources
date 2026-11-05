package dev.infusion.tracking.trakt

import dev.infusion.core.domain.tracking.TrackingActivityStamp
import dev.infusion.core.domain.tracking.TrackingAuthorizationPoll
import dev.infusion.core.domain.tracking.TrackingConnectionState
import dev.infusion.core.domain.tracking.TrackingDeliveryResult
import dev.infusion.core.domain.tracking.TrackingDeviceAuthorization
import dev.infusion.core.domain.tracking.TrackingInboundOutcome
import dev.infusion.core.domain.tracking.TrackingInboundReadPort
import dev.infusion.core.domain.tracking.TrackingMediaKind
import dev.infusion.core.domain.tracking.TrackingPlaybackPage
import dev.infusion.core.domain.tracking.TrackingPlaybackRequest
import dev.infusion.core.domain.tracking.TrackingProviderCapabilities
import dev.infusion.core.domain.tracking.TrackingProviderPort
import dev.infusion.core.domain.tracking.TrackingScrobbleEvent
import dev.infusion.core.domain.tracking.TrackingWatchedAnswer
import dev.infusion.core.domain.tracking.TrackingWatchedLookupPort
import dev.infusion.core.domain.tracking.TrackingWatchedQuery
import dev.infusion.core.domain.tracking.TrackingWatchedVerdict
import dev.infusion.core.domain.upnext.UpNextReadPort
import dev.infusion.core.domain.upnext.UpNextReadRequest
import dev.infusion.core.domain.upnext.UpNextTrackerPage

class TraktTrackingProvider(
    private val configuration: TraktConfiguration,
    private val client: TraktHttpClient,
    private val auth: TraktAuthRepository,
) : TrackingProviderPort, TrackingInboundReadPort, TrackingWatchedLookupPort, UpNextReadPort {

    override val providerId: String = TraktAuthRepository.TRAKT_PROVIDER_ID

    override val displayName: String = "Trakt"

    private var playbackPage: Int = 1

    private val resolutionMemo = LinkedHashMap<String, String>()

    override fun capabilities(): TrackingProviderCapabilities = TrackingProviderCapabilities(
        providerId = providerId,
        deviceAuthorization = true,
        scrobbling = true,

        historyWrite = true,

        playbackImport = true,

        watchedImport = true,

        watchedHistory = true,

        upNext = true,

        watchlist = true,

        personalRating = setOf(
            dev.infusion.core.model.sync.PersonalRatingKind.MOVIE,
            dev.infusion.core.model.sync.PersonalRatingKind.EPISODE,
        ),

        reviews = true,
    )

    override fun connectionState(): TrackingConnectionState = auth.connectionState()

    override suspend fun beginAuthorization(): TrackingDeviceAuthorization = auth.beginAuthorization()

    override suspend fun pollAuthorization(): TrackingAuthorizationPoll = auth.pollAuthorization()

    override fun cancelAuthorization() = auth.cancelAuthorization()

    override suspend fun disconnect() = auth.disconnect()

    override suspend fun deliver(event: TrackingScrobbleEvent): TrackingDeliveryResult =
        when (val outcome = sendForRead(TraktScrobbleMapping.requestFor(event))) {
            is TraktOutcome.Success -> TrackingDeliveryResult.Delivered
            is TraktOutcome.Retry -> TrackingDeliveryResult.Retryable(outcome.reason)
            is TraktOutcome.Rejected -> TrackingDeliveryResult.Rejected(outcome.reason)
            is TraktOutcome.Unauthorized -> TrackingDeliveryResult.Unauthorized(outcome.reason)
        }

    override suspend fun readActivityStamp(): TrackingInboundOutcome<TrackingActivityStamp> =
        when (val outcome = sendForRead(TraktInboundMapping.activitiesRequest())) {
            is TraktOutcome.Success -> {
                val stamp = TraktInboundMapping.activityStamp(outcome.response.body)
                if (stamp == null) {
                    TrackingInboundOutcome.Rejected(MALFORMED_RESPONSE)
                } else {
                    TrackingInboundOutcome.Ok(stamp)
                }
            }
            is TraktOutcome.Retry -> TrackingInboundOutcome.Unavailable(outcome.reason)
            is TraktOutcome.Rejected -> TrackingInboundOutcome.Rejected(outcome.reason)
            is TraktOutcome.Unauthorized -> TrackingInboundOutcome.Unauthorized(outcome.reason)
        }

    override suspend fun readSavedPlaybacks(
        request: TrackingPlaybackRequest,
    ): TrackingInboundOutcome<TrackingPlaybackPage> {
        val page = if (request.dateFrom == null) 1 else playbackPage + 1
        playbackPage = page
        return when (val outcome = sendForRead(TraktInboundMapping.playbackRequest(request, page))) {
            is TraktOutcome.Success -> {
                val parsed = TraktInboundMapping.playbacks(
                    body = outcome.response.body,
                    limit = request.limit,
                    page = page,
                    pageCount = paginationPageCount(outcome.response.headers),
                )
                if (parsed == null) {
                    TrackingInboundOutcome.Rejected(MALFORMED_RESPONSE)
                } else {
                    TrackingInboundOutcome.Ok(parsed)
                }
            }
            is TraktOutcome.Retry -> TrackingInboundOutcome.Unavailable(outcome.reason)
            is TraktOutcome.Rejected -> TrackingInboundOutcome.Rejected(outcome.reason)
            is TraktOutcome.Unauthorized -> TrackingInboundOutcome.Unauthorized(outcome.reason)
        }
    }

    override suspend fun readWatched(
        queries: List<TrackingWatchedQuery>,
    ): TrackingInboundOutcome<List<TrackingWatchedAnswer>> {
        require(queries.isNotEmpty()) { "a watched lookup is never sent empty" }
        require(queries.size <= TrackingWatchedLookupPort.MAX_BATCH) {
            "a watched lookup batch must not exceed ${TrackingWatchedLookupPort.MAX_BATCH} items"
        }
        val answers = arrayOfNulls<TrackingWatchedAnswer>(queries.size)
        val shows = LinkedHashMap<String, MutableList<Int>>()
        val movies = LinkedHashMap<String, MutableList<Int>>()
        val budget = LookupBudget(limit = MAX_LOOKUP_REQUESTS)
        for ((index, query) in queries.withIndex()) {
            when (val pathId = resolvePathId(query, budget)) {
                is PathId.Failed -> return when (val outcome = pathId.outcome) {
                    is TraktOutcome.Retry -> TrackingInboundOutcome.Unavailable(outcome.reason)
                    is TraktOutcome.Rejected -> TrackingInboundOutcome.Rejected(outcome.reason)
                    is TraktOutcome.Unauthorized -> TrackingInboundOutcome.Unauthorized(outcome.reason)
                    is TraktOutcome.Success -> TrackingInboundOutcome.Rejected(MALFORMED_RESPONSE)
                }

                PathId.Unknown -> answers[index] = NOT_FOUND_ANSWER

                PathId.Undecidable -> answers[index] = MALFORMED_ANSWER
                is PathId.Value -> when (query.kind) {
                    TrackingMediaKind.MOVIE -> movies.getOrPut(pathId.id) { mutableListOf() } += index
                    TrackingMediaKind.SHOW -> shows.getOrPut(pathId.id) { mutableListOf() } += index
                }
            }
        }
        for ((showId, indices) in shows) {
            if (!budget.charge()) {
                indices.forEach { answers[it] = MALFORMED_ANSWER }
                continue
            }
            when (val outcome = sendForRead(TraktInboundMapping.showProgressRequest(showId))) {
                is TraktOutcome.Success -> {
                    val body = outcome.response.body
                    for (index in indices) {
                        val query = queries[index]
                        answers[index] = TraktInboundMapping.episodeWatched(
                            body = body,
                            seasonNumber = query.seasonNumber!!,
                            episodeNumber = query.episodeNumber!!,
                        ) ?: MALFORMED_ANSWER
                    }
                }

                is TraktOutcome.Rejected ->
                    if (outcome.reason == TraktHttpClient.NOT_FOUND_REASON) {
                        indices.forEach { answers[it] = NOT_FOUND_ANSWER }
                    } else {
                        return TrackingInboundOutcome.Rejected(outcome.reason)
                    }
                is TraktOutcome.Retry -> return TrackingInboundOutcome.Unavailable(outcome.reason)
                is TraktOutcome.Unauthorized -> return TrackingInboundOutcome.Unauthorized(outcome.reason)
            }
        }
        for ((movieId, indices) in movies) {
            if (!budget.charge()) {
                indices.forEach { answers[it] = MALFORMED_ANSWER }
                continue
            }
            when (val outcome = sendForRead(TraktInboundMapping.movieHistoryRequest(movieId))) {
                is TraktOutcome.Success -> {
                    val answer = TraktInboundMapping.movieWatched(outcome.response.body) ?: MALFORMED_ANSWER
                    indices.forEach { answers[it] = answer }
                }
                is TraktOutcome.Rejected ->

                    if (outcome.reason == TraktHttpClient.NOT_FOUND_REASON) {
                        indices.forEach { answers[it] = NOT_FOUND_ANSWER }
                    } else {
                        return TrackingInboundOutcome.Rejected(outcome.reason)
                    }
                is TraktOutcome.Retry -> return TrackingInboundOutcome.Unavailable(outcome.reason)
                is TraktOutcome.Unauthorized -> return TrackingInboundOutcome.Unauthorized(outcome.reason)
            }
        }
        @Suppress("UNCHECKED_CAST")
        return TrackingInboundOutcome.Ok(answers.toList() as List<TrackingWatchedAnswer>)
    }

    override suspend fun readUpNext(
        request: UpNextReadRequest,
    ): TrackingInboundOutcome<UpNextTrackerPage> =
        when (val outcome = sendForRead(TraktUpNext.request(request.page, request.limit))) {
            is TraktOutcome.Success -> {
                val parsed = TraktUpNext.page(
                    body = outcome.response.body,
                    limit = request.limit,
                    page = request.page,
                    pageCount = TraktUpNext.pageCount(outcome.response.headers),
                )
                if (parsed == null) {
                    TrackingInboundOutcome.Rejected(MALFORMED_RESPONSE)
                } else {
                    TrackingInboundOutcome.Ok(parsed)
                }
            }
            is TraktOutcome.Retry -> TrackingInboundOutcome.Unavailable(outcome.reason)
            is TraktOutcome.Rejected -> TrackingInboundOutcome.Rejected(outcome.reason)
            is TraktOutcome.Unauthorized -> TrackingInboundOutcome.Unauthorized(outcome.reason)
        }

    private suspend fun resolvePathId(query: TrackingWatchedQuery, budget: LookupBudget): PathId {
        TraktIdLookup.directPathId(query.externalIds)?.let { return PathId.Value(it) }
        for (authority in TraktIdLookup.ladderFor(query.kind)) {
            val value = TraktIdLookup.aliasFor(query.externalIds, authority) ?: continue
            val key = memoKey(query.kind, authority, value)
            memoGet(key)?.let { return PathId.Value(it) }
            if (!budget.charge()) return PathId.Undecidable
            when (val outcome = sendForRead(TraktIdLookup.request(authority, value, query.kind))) {
                is TraktOutcome.Success -> {
                    val resolved = TraktIdLookup.resolve(outcome.response.body, authority, value, query.kind)
                    if (resolved is TraktPathId.Resolved) memoPut(key, resolved.value)
                    return when (resolved) {
                        is TraktPathId.Resolved -> PathId.Value(resolved.value)
                        TraktPathId.Unknown -> PathId.Unknown
                        TraktPathId.Undecidable -> PathId.Undecidable
                    }
                }

                is TraktOutcome.Rejected ->
                    if (outcome.reason == TraktHttpClient.NOT_FOUND_REASON) {
                        return PathId.Unknown
                    } else {
                        return PathId.Failed(outcome)
                    }
                is TraktOutcome.Retry -> return PathId.Failed(outcome)
                is TraktOutcome.Unauthorized -> return PathId.Failed(outcome)
            }
        }

        return PathId.Undecidable
    }

    private fun memoKey(kind: TrackingMediaKind, authority: TraktIdLookup.Authority, value: String): String =
        "${kind.name}|${authority.wire}|$value"

    private fun memoGet(key: String): String? = synchronized(resolutionMemo) { resolutionMemo[key] }

    private fun memoPut(key: String, value: String) = synchronized(resolutionMemo) {
        if (resolutionMemo.size >= RESOLUTION_MEMO_MAX) {
            resolutionMemo.keys.firstOrNull()?.let { resolutionMemo.remove(it) }
        }
        resolutionMemo[key] = value
    }

    private suspend fun sendForRead(request: TraktRequest): TraktOutcome {
        val token = auth.accessToken() ?: return TraktOutcome.Unauthorized(NO_AUTHORIZATION)
        val outcome = client.execute(request, token)
        if (outcome !is TraktOutcome.Unauthorized) return outcome

        val refreshed = auth.refresh() ?: return outcome
        return client.execute(request, refreshed)
    }

    private fun paginationPageCount(headers: Map<String, String>): Int? =
        headers.entries.firstOrNull { it.key.equals("X-Pagination-Page-Count", ignoreCase = true) }
            ?.value?.substringBefore(',')?.trim()?.toIntOrNull()?.takeIf { it >= 0 }

    private sealed interface PathId {

        data class Value(val id: String) : PathId

        data object Unknown : PathId

        data object Undecidable : PathId

        data class Failed(val outcome: TraktOutcome) : PathId
    }

    private class LookupBudget(private val limit: Int) {
        private var issued: Int = 0

        fun charge(): Boolean {
            if (issued >= limit) return false
            issued++
            return true
        }
    }

    private companion object {
        const val NO_AUTHORIZATION = "no valid Trakt authorization for this profile"
        const val MALFORMED_RESPONSE = "trakt answered a payload this client cannot use"

        const val MAX_LOOKUP_REQUESTS = TrackingWatchedLookupPort.MAX_BATCH

        const val RESOLUTION_MEMO_MAX = 512

        val MALFORMED_ANSWER = TrackingWatchedAnswer(TrackingWatchedVerdict.MALFORMED)

        val NOT_FOUND_ANSWER = TrackingWatchedAnswer(TrackingWatchedVerdict.NOT_FOUND)
    }
}
