package dev.infusion.tracking.mdblist

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

class MDBListTrackingProvider(
    private val configuration: MDBListConfiguration,
    private val client: MDBListHttpClient,
    private val auth: MDBListAuthRepository,
) : TrackingProviderPort, TrackingInboundReadPort, TrackingWatchedLookupPort, UpNextReadPort {

    override val providerId: String = MDBListAuthRepository.MDBLIST_PROVIDER_ID

    override val displayName: String = "MDBList"

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
        when (val outcome = sendForRead(MDBListScrobbleMapping.requestFor(event))) {
            is MDBListOutcome.Success -> TrackingDeliveryResult.Delivered
            is MDBListOutcome.Retry -> TrackingDeliveryResult.Retryable(outcome.reason)
            is MDBListOutcome.Rejected -> TrackingDeliveryResult.Rejected(outcome.reason)
            is MDBListOutcome.Unauthorized -> TrackingDeliveryResult.Unauthorized(outcome.reason)
        }

    override suspend fun readUpNext(
        request: UpNextReadRequest,
    ): TrackingInboundOutcome<UpNextTrackerPage> =
        when (val outcome = sendForRead(MDBListUpNext.request(request.page, request.limit))) {
            is MDBListOutcome.Success -> {
                val page = MDBListUpNext.page(outcome.response.body, request.limit)
                if (page == null) {
                    TrackingInboundOutcome.Rejected(MALFORMED_RESPONSE)
                } else {
                    TrackingInboundOutcome.Ok(page)
                }
            }
            is MDBListOutcome.Retry -> TrackingInboundOutcome.Unavailable(outcome.reason)
            is MDBListOutcome.Rejected -> TrackingInboundOutcome.Rejected(outcome.reason)
            is MDBListOutcome.Unauthorized -> TrackingInboundOutcome.Unauthorized(outcome.reason)
        }

    override suspend fun readActivityStamp(): TrackingInboundOutcome<TrackingActivityStamp> =
        when (val outcome = sendForRead(MDBListInboundMapping.activitiesRequest())) {
            is MDBListOutcome.Success -> {
                val stamp = MDBListInboundMapping.activityStamp(outcome.response.body)
                if (stamp == null) {
                    TrackingInboundOutcome.Rejected(MALFORMED_RESPONSE)
                } else {
                    TrackingInboundOutcome.Ok(stamp)
                }
            }
            is MDBListOutcome.Retry -> TrackingInboundOutcome.Unavailable(outcome.reason)
            is MDBListOutcome.Rejected -> TrackingInboundOutcome.Rejected(outcome.reason)
            is MDBListOutcome.Unauthorized -> TrackingInboundOutcome.Unauthorized(outcome.reason)
        }

    override suspend fun readSavedPlaybacks(
        request: TrackingPlaybackRequest,
    ): TrackingInboundOutcome<TrackingPlaybackPage> =
        when (val outcome = sendForRead(MDBListInboundMapping.playbackRequest(request))) {
            is MDBListOutcome.Success -> {
                val parsed = MDBListInboundMapping.playbacks(outcome.response.body)
                if (parsed == null) {
                    TrackingInboundOutcome.Rejected(MALFORMED_RESPONSE)
                } else {
                    TrackingInboundOutcome.Ok(parsed)
                }
            }
            is MDBListOutcome.Retry -> TrackingInboundOutcome.Unavailable(outcome.reason)
            is MDBListOutcome.Rejected -> TrackingInboundOutcome.Rejected(outcome.reason)
            is MDBListOutcome.Unauthorized -> TrackingInboundOutcome.Unauthorized(outcome.reason)
        }

    override suspend fun readWatched(
        queries: List<TrackingWatchedQuery>,
    ): TrackingInboundOutcome<List<TrackingWatchedAnswer>> {
        require(queries.isNotEmpty()) { "a watched lookup is never sent empty" }
        require(queries.size <= TrackingWatchedLookupPort.MAX_BATCH) {
            "a watched lookup batch must not exceed ${TrackingWatchedLookupPort.MAX_BATCH} items"
        }
        val answers = arrayOfNulls<TrackingWatchedAnswer>(queries.size)
        val movies = LinkedHashMap<Long, MutableList<Int>>()
        val shows = LinkedHashMap<Long, MutableList<Int>>()
        for ((index, query) in queries.withIndex()) {

            val tmdbId = query.externalIds.tmdbId ?: run {
                answers[index] = MALFORMED_ANSWER
                continue
            }
            when (query.kind) {
                TrackingMediaKind.MOVIE -> movies.getOrPut(tmdbId) { mutableListOf() } += index
                TrackingMediaKind.SHOW -> shows.getOrPut(tmdbId) { mutableListOf() } += index
            }
        }
        val budget = LookupBudget(limit = MAX_LOOKUP_REQUESTS)
        for (chunk in movies.keys.chunked(MOVIE_BATCH_PER_REQUEST)) {
            if (!budget.charge()) {
                chunk.forEach { id -> movies[id]?.forEach { answers[it] = MALFORMED_ANSWER } }
                continue
            }
            when (val outcome = sendForRead(MDBListInboundMapping.movieStateRequest(chunk))) {
                is MDBListOutcome.Success -> {
                    val body = outcome.response.body
                    chunk.forEach { id ->
                        val answer = MDBListInboundMapping.movieWatched(body, id) ?: MALFORMED_ANSWER
                        movies[id]?.forEach { answers[it] = answer }
                    }
                }
                is MDBListOutcome.Retry -> return TrackingInboundOutcome.Unavailable(outcome.reason)
                is MDBListOutcome.Rejected -> return TrackingInboundOutcome.Rejected(outcome.reason)
                is MDBListOutcome.Unauthorized -> return TrackingInboundOutcome.Unauthorized(outcome.reason)
            }
        }
        for ((showId, indices) in shows) {
            if (!budget.charge()) {
                indices.forEach { answers[it] = MALFORMED_ANSWER }
                continue
            }
            when (val outcome = sendForRead(MDBListInboundMapping.showHistoryRequest(showId))) {
                is MDBListOutcome.Success -> {
                    val body = outcome.response.body
                    for (index in indices) {
                        val query = queries[index]
                        answers[index] = MDBListInboundMapping.episodeWatched(
                            body = body,
                            seasonNumber = query.seasonNumber!!,
                            episodeNumber = query.episodeNumber!!,
                        ) ?: MALFORMED_ANSWER
                    }
                }
                is MDBListOutcome.Retry -> return TrackingInboundOutcome.Unavailable(outcome.reason)
                is MDBListOutcome.Rejected -> return TrackingInboundOutcome.Rejected(outcome.reason)
                is MDBListOutcome.Unauthorized -> return TrackingInboundOutcome.Unauthorized(outcome.reason)
            }
        }
        @Suppress("UNCHECKED_CAST")
        return TrackingInboundOutcome.Ok(answers.toList() as List<TrackingWatchedAnswer>)
    }

    private suspend fun sendForRead(request: MDBListRequest): MDBListOutcome {
        val token = auth.accessToken() ?: return MDBListOutcome.Unauthorized(NO_AUTHORIZATION)
        val outcome = client.execute(request, token)
        if (outcome !is MDBListOutcome.Unauthorized) return outcome

        val refreshed = auth.refresh() ?: return outcome
        return client.execute(request, refreshed)
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
        const val NO_AUTHORIZATION = "no valid MDBList authorization for this profile"
        const val MALFORMED_RESPONSE = "mdblist answered a payload this client cannot use"

        const val MAX_LOOKUP_REQUESTS = TrackingWatchedLookupPort.MAX_BATCH

        const val MOVIE_BATCH_PER_REQUEST = 100

        val MALFORMED_ANSWER = TrackingWatchedAnswer(TrackingWatchedVerdict.MALFORMED)
    }
}
