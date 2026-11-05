package dev.infusion.tracking.simkl

import dev.infusion.core.domain.tracking.TrackingActivityStamp
import dev.infusion.core.domain.tracking.TrackingAuthorizationPoll
import dev.infusion.core.domain.tracking.TrackingConnectionState
import dev.infusion.core.domain.tracking.TrackingDeliveryResult
import dev.infusion.core.domain.tracking.TrackingDeviceAuthorization
import dev.infusion.core.domain.tracking.TrackingInboundOutcome
import dev.infusion.core.domain.tracking.TrackingInboundReadPort
import dev.infusion.core.domain.tracking.TrackingPlaybackPage
import dev.infusion.core.domain.tracking.TrackingPlaybackRequest
import dev.infusion.core.domain.tracking.TrackingProviderCapabilities
import dev.infusion.core.domain.tracking.TrackingProviderPort
import dev.infusion.core.domain.tracking.TrackingScrobbleEvent
import dev.infusion.core.domain.tracking.TrackingWatchedAnswer
import dev.infusion.core.domain.tracking.TrackingWatchedLookupPort
import dev.infusion.core.domain.tracking.TrackingWatchedQuery
import dev.infusion.core.domain.upnext.UpNextReadPort
import dev.infusion.core.domain.upnext.UpNextReadRequest
import dev.infusion.core.domain.upnext.UpNextTrackerPage

class SimklTrackingProvider(
    private val configuration: SimklConfiguration,
    private val client: SimklHttpClient,
    private val auth: SimklAuthRepository,
) : TrackingProviderPort, TrackingInboundReadPort, TrackingWatchedLookupPort, UpNextReadPort {

    override val providerId: String = SimklAuthRepository.SIMKL_PROVIDER_ID

    override val displayName: String = "Simkl"

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

        personalRating = setOf(dev.infusion.core.model.sync.PersonalRatingKind.MOVIE),

        reviews = false,
    )

    override fun connectionState(): TrackingConnectionState = auth.connectionState()

    override suspend fun beginAuthorization(): TrackingDeviceAuthorization = auth.beginAuthorization()

    override suspend fun pollAuthorization(): TrackingAuthorizationPoll = auth.pollAuthorization()

    override fun cancelAuthorization() = auth.cancelAuthorization()

    override suspend fun disconnect() = auth.disconnect()

    override suspend fun deliver(event: TrackingScrobbleEvent): TrackingDeliveryResult =
        when (val outcome = sendForRead(SimklScrobbleMapping.requestFor(event))) {
            is SimklOutcome.Success -> TrackingDeliveryResult.Delivered
            is SimklOutcome.Retry -> TrackingDeliveryResult.Retryable(outcome.reason)
            is SimklOutcome.Rejected -> TrackingDeliveryResult.Rejected(outcome.reason)
            is SimklOutcome.Unauthorized -> TrackingDeliveryResult.Unauthorized(outcome.reason)
        }

    override suspend fun readActivityStamp(): TrackingInboundOutcome<TrackingActivityStamp> =
        when (val outcome = sendForRead(SimklInboundMapping.activitiesRequest())) {
            is SimklOutcome.Success -> {
                val stamp = SimklInboundMapping.activityStamp(outcome.response.body)
                if (stamp == null) {
                    TrackingInboundOutcome.Rejected(MALFORMED_RESPONSE)
                } else {
                    TrackingInboundOutcome.Ok(stamp)
                }
            }
            is SimklOutcome.Retry -> TrackingInboundOutcome.Unavailable(outcome.reason)
            is SimklOutcome.Rejected -> TrackingInboundOutcome.Rejected(outcome.reason)
            is SimklOutcome.Unauthorized -> TrackingInboundOutcome.Unauthorized(outcome.reason)
        }

    override suspend fun readUpNext(
        request: UpNextReadRequest,
    ): TrackingInboundOutcome<UpNextTrackerPage> =
        when (val outcome = sendForRead(SimklUpNext.request())) {
            is SimklOutcome.Success -> {
                val page = SimklUpNext.page(outcome.response.body, request.limit)
                if (page == null) {
                    TrackingInboundOutcome.Rejected(MALFORMED_RESPONSE)
                } else {
                    TrackingInboundOutcome.Ok(page)
                }
            }
            is SimklOutcome.Retry -> TrackingInboundOutcome.Unavailable(outcome.reason)
            is SimklOutcome.Rejected -> TrackingInboundOutcome.Rejected(outcome.reason)
            is SimklOutcome.Unauthorized -> TrackingInboundOutcome.Unauthorized(outcome.reason)
        }

    override suspend fun readSavedPlaybacks(request: TrackingPlaybackRequest): TrackingInboundOutcome<TrackingPlaybackPage> =
        when (val outcome = sendForRead(SimklInboundMapping.playbackRequest(request))) {
            is SimklOutcome.Success -> {
                val page = SimklInboundMapping.playbacks(outcome.response.body, request.limit)
                if (page == null) {
                    TrackingInboundOutcome.Rejected(MALFORMED_RESPONSE)
                } else {
                    TrackingInboundOutcome.Ok(page)
                }
            }
            is SimklOutcome.Retry -> TrackingInboundOutcome.Unavailable(outcome.reason)
            is SimklOutcome.Rejected -> TrackingInboundOutcome.Rejected(outcome.reason)
            is SimklOutcome.Unauthorized -> TrackingInboundOutcome.Unauthorized(outcome.reason)
        }

    override suspend fun readWatched(
        queries: List<TrackingWatchedQuery>,
    ): TrackingInboundOutcome<List<TrackingWatchedAnswer>> {
        require(queries.isNotEmpty()) { "a watched lookup is never sent empty" }
        require(queries.size <= TrackingWatchedLookupPort.MAX_BATCH) {
            "a watched lookup batch must not exceed ${TrackingWatchedLookupPort.MAX_BATCH} items"
        }
        return when (val outcome = sendForRead(SimklInboundMapping.watchedLookupRequest(queries))) {
            is SimklOutcome.Success -> {
                val answers = SimklInboundMapping.watchedLookupAnswers(outcome.response.body, queries)
                if (answers == null) {
                    TrackingInboundOutcome.Rejected(MALFORMED_RESPONSE)
                } else {
                    TrackingInboundOutcome.Ok(answers)
                }
            }
            is SimklOutcome.Retry -> TrackingInboundOutcome.Unavailable(outcome.reason)
            is SimklOutcome.Rejected -> TrackingInboundOutcome.Rejected(outcome.reason)
            is SimklOutcome.Unauthorized -> TrackingInboundOutcome.Unauthorized(outcome.reason)
        }
    }

    private suspend fun sendForRead(request: SimklRequest): SimklOutcome {
        val token = auth.accessToken() ?: return SimklOutcome.Unauthorized(NO_AUTHORIZATION)
        val outcome = client.execute(request, token)
        if (outcome !is SimklOutcome.Unauthorized) return outcome

        val refreshed = auth.refresh() ?: return outcome
        return client.execute(request, refreshed)
    }

    private companion object {
        const val NO_AUTHORIZATION = "no valid Simkl authorization for this profile"
        const val MALFORMED_RESPONSE = "simkl answered a payload this client cannot use"
    }
}
