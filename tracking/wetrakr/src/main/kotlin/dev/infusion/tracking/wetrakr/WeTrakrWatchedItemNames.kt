package dev.infusion.tracking.wetrakr

import dev.infusion.core.domain.tracking.TrackingInboundOutcome
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryUnit
import dev.infusion.core.domain.tracking.TrackingWatchedItemNamePort
import dev.infusion.core.domain.tracking.TrackingWatchedItemNamesPage
import kotlinx.coroutines.CancellationException

class WeTrakrWatchedItemNames(
    private val client: WeTrakrHttpClient,
    private val auth: WeTrakrAuthRepository,
) : TrackingWatchedItemNamePort {

    override val providerId: String = WeTrakrAuthRepository.WETRAKR_PROVIDER_ID

    override suspend fun readStatedNames(
        unit: TrackingWatchedHistoryUnit,
        page: Int,
        limit: Int,
    ): TrackingInboundOutcome<TrackingWatchedItemNamesPage> {
        if (!auth.isConnected()) return TrackingInboundOutcome.Unauthorized(NO_AUTHORIZATION)
        val target = when (unit) {
            TrackingWatchedHistoryUnit.MOVIE -> WeTrakrInboundMapping.TARGET_MOVIES
            TrackingWatchedHistoryUnit.EPISODE -> WeTrakrInboundMapping.TARGET_SHOWS
        }
        val boundedPage = page.coerceAtLeast(1)
        val boundedLimit = limit.coerceIn(1, NAME_PAGE_CAPACITY)
        val outcome = send(WeTrakrInboundMapping.watchedNamesRequest(target, boundedPage, boundedLimit))
        val response = when (outcome) {
            is WeTrakrOutcome.Success -> outcome.response
            is WeTrakrOutcome.Unauthorized -> return TrackingInboundOutcome.Unauthorized(outcome.reason)
            is WeTrakrOutcome.Retry -> return TrackingInboundOutcome.Unavailable(outcome.reason)
            is WeTrakrOutcome.Rejected -> return TrackingInboundOutcome.Rejected(outcome.reason)
        }
        val names = WeTrakrInboundMapping.watchedNamesPage(
            body = response.body,
            page = boundedPage,
            limit = boundedLimit,
            headers = response.headers,
        ) ?: return TrackingInboundOutcome.Rejected(SHAPE_CHANGED)
        return TrackingInboundOutcome.Ok(names)
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
    } catch (_: Throwable) {
        null
    }

    companion object {

        const val NAME_PAGE_CAPACITY: Int = 100

        const val SHAPE_CHANGED: String = "wetrakr_watched_names_shape_changed"

        const val NO_AUTHORIZATION: String = "wetrakr_no_authorization"
    }
}
