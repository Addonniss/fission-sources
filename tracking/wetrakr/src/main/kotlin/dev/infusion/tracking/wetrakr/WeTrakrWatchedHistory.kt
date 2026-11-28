package dev.infusion.tracking.wetrakr

import dev.infusion.core.domain.tracking.TrackingInboundOutcome
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryCoveragePort
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryPage
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryPort
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryProviderCounts
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryRequest
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryUnit
import java.time.Instant
import kotlinx.coroutines.CancellationException

class WeTrakrWatchedHistory(
    private val client: WeTrakrHttpClient,
    private val auth: WeTrakrAuthRepository,
) : TrackingWatchedHistoryPort, TrackingWatchedHistoryCoveragePort {

    override val providerId: String = WeTrakrAuthRepository.WETRAKR_PROVIDER_ID

    override val historyPageSize: Int get() = COMPACT_PAGE_CAPACITY

    override fun accountConnected(): Boolean = auth.isConnected()

    override suspend fun readHistory(
        request: TrackingWatchedHistoryRequest,
    ): TrackingInboundOutcome<TrackingWatchedHistoryPage> {
        if (!auth.isConnected()) return TrackingInboundOutcome.Unauthorized(NO_AUTHORIZATION)

        val readStartedAt = Instant.now()
        val target = when (request.unit) {
            TrackingWatchedHistoryUnit.MOVIE -> WeTrakrInboundMapping.TARGET_MOVIES
            TrackingWatchedHistoryUnit.EPISODE -> WeTrakrInboundMapping.TARGET_EPISODES
        }
        val limit = request.limit.coerceIn(1, COMPACT_PAGE_CAPACITY)
        val outcome = send(
            WeTrakrInboundMapping.watchedHistoryRequest(
                target = target,
                cursor = request.cursor,
                since = request.since,
                limit = limit,
            ),
        )
        val response = when (outcome) {
            is WeTrakrOutcome.Success -> outcome.response
            is WeTrakrOutcome.Unauthorized -> return TrackingInboundOutcome.Unauthorized(outcome.reason)
            is WeTrakrOutcome.Retry -> return TrackingInboundOutcome.Unavailable(outcome.reason)
            is WeTrakrOutcome.Rejected -> return TrackingInboundOutcome.Rejected(outcome.reason)
        }
        val page = WeTrakrInboundMapping.watchedHistoryPage(
            body = response.body,
            unit = request.unit,
            nextCursor = WeTrakrInboundMapping.nextCursor(response.headers),
            readStartedAt = readStartedAt,
        ) ?: return TrackingInboundOutcome.Rejected(SHAPE_CHANGED)
        return TrackingInboundOutcome.Ok(page)
    }

    override suspend fun readProviderCounts():
        TrackingInboundOutcome<TrackingWatchedHistoryProviderCounts> {
        if (!auth.isConnected()) return TrackingInboundOutcome.Unauthorized(NO_AUTHORIZATION)

        val readStartedAt = Instant.now()
        val outcome = send(WeTrakrInboundMapping.watchedCountersRequest())
        val response = when (outcome) {
            is WeTrakrOutcome.Success -> outcome.response
            is WeTrakrOutcome.Unauthorized -> return TrackingInboundOutcome.Unauthorized(outcome.reason)
            is WeTrakrOutcome.Retry -> return TrackingInboundOutcome.Unavailable(outcome.reason)
            is WeTrakrOutcome.Rejected -> return TrackingInboundOutcome.Rejected(outcome.reason)
        }
        val counts = WeTrakrInboundMapping.watchedCounters(response.body, readStartedAt)
            ?: return TrackingInboundOutcome.Rejected(COUNTS_SHAPE_CHANGED)
        return TrackingInboundOutcome.Ok(counts)
    }

    private suspend fun send(request: WeTrakrRequest): WeTrakrOutcome {
        val token = readToken { auth.accessToken() } ?: return WeTrakrOutcome.Unauthorized(NO_AUTHORIZATION)
        val outcome = client.execute(request, token)
        if (outcome !is WeTrakrOutcome.Unauthorized) return outcome
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

    companion object {

        const val COMPACT_PAGE_CAPACITY: Int = 5_000

        const val COUNTS_SHAPE_CHANGED: String = "wetrakr_watched_counters_shape_changed"

        const val NO_AUTHORIZATION: String = "wetrakr_no_authorization"

        const val SHAPE_CHANGED: String = "wetrakr_watched_history_shape_changed"
    }
}
