package dev.infusion.tracking.wetrakr

import dev.infusion.core.domain.tracking.TrackingChangeFeedOutcome
import dev.infusion.core.domain.tracking.TrackingChangeFeedPort
import dev.infusion.core.domain.tracking.TrackingChangeFeedRequest
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryUnit
import kotlinx.coroutines.CancellationException

class WeTrakrJournal(
    private val client: WeTrakrHttpClient,
    private val auth: WeTrakrAuthRepository,
) : TrackingChangeFeedPort {

    override val providerId: String = WeTrakrAuthRepository.WETRAKR_PROVIDER_ID

    override val servesUnits: Set<TrackingWatchedHistoryUnit> =
        setOf(TrackingWatchedHistoryUnit.MOVIE)

    override val pageSize: Int get() = MAX_PAGE_SIZE

    override fun accountConnected(): Boolean = auth.isConnected()

    override suspend fun readPage(request: TrackingChangeFeedRequest): TrackingChangeFeedOutcome {
        if (!auth.isConnected()) return TrackingChangeFeedOutcome.Unauthorized(NO_AUTHORIZATION)
        val providerRequest = WeTrakrInboundMapping.journalRequest(
            fromDate = request.fromDate,
            page = request.page,
            limit = request.limit.coerceIn(1, MAX_PAGE_SIZE),
        )
        return when (val outcome = send(providerRequest)) {
            is WeTrakrOutcome.Success -> {
                val page = WeTrakrInboundMapping.journalPage(
                    body = outcome.response.body,
                    headers = outcome.response.headers,
                    page = request.page,
                    limit = request.limit,
                )
                if (page == null) {
                    TrackingChangeFeedOutcome.Rejected(SHAPE_CHANGED)
                } else {
                    TrackingChangeFeedOutcome.Ok(page)
                }
            }
            is WeTrakrOutcome.Unauthorized -> TrackingChangeFeedOutcome.Unauthorized(outcome.reason)
            is WeTrakrOutcome.Retry -> TrackingChangeFeedOutcome.Unavailable(outcome.reason)
            is WeTrakrOutcome.Rejected ->

                if (outcome.reason.contains(WeTrakrHttpClient.JOURNAL_EXPIRED_ERROR, ignoreCase = true)) {
                    TrackingChangeFeedOutcome.Expired(
                        oldest = outcome.oldest,
                        reason = EXPIRED_REASON,
                    )
                } else {
                    TrackingChangeFeedOutcome.Rejected(outcome.reason)
                }
        }
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

        const val MAX_PAGE_SIZE: Int = 1_000

        const val SHAPE_CHANGED: String = "wetrakr_journal_shape_changed"

        const val NO_AUTHORIZATION: String = "wetrakr_no_authorization"

        const val EXPIRED_REASON: String = "wetrakr_journal_expired"
    }
}
