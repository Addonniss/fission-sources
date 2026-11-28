package dev.infusion.tracking.wetrakr

import dev.infusion.core.domain.tracking.TrackingInboundOutcome
import dev.infusion.core.domain.watchlist.MembershipCapabilities
import dev.infusion.core.domain.watchlist.MembershipWriteResult
import dev.infusion.core.domain.watchlist.WatchlistKind
import dev.infusion.core.domain.watchlist.WatchlistMembershipPort
import dev.infusion.core.domain.watchlist.WatchlistMembershipRef
import dev.infusion.core.domain.watchlist.WatchlistReadRequest
import dev.infusion.core.domain.watchlist.WatchlistRemoteKind
import dev.infusion.core.domain.watchlist.WatchlistRemotePage
import dev.infusion.core.domain.watchlist.WatchlistRemoteRecord
import kotlinx.coroutines.CancellationException

class WeTrakrWatchlistMembership(
    private val client: WeTrakrHttpClient,
    private val auth: WeTrakrAuthRepository,

    private val trace: ((String) -> Unit)? = null,
) : WatchlistMembershipPort {

    override val providerId: String = WeTrakrAuthRepository.WETRAKR_PROVIDER_ID

    override fun capabilities(): MembershipCapabilities = MembershipCapabilities(
        add = true,
        remove = true,
        progressClear = true,
        writeKinds = setOf(WatchlistRemoteKind.MOVIE, WatchlistRemoteKind.SHOW),
    )

    override fun accountConnected(): Boolean = auth.isConnected()

    override suspend fun read(
        request: WatchlistReadRequest,
    ): TrackingInboundOutcome<WatchlistRemotePage> {
        val page = pageOf(request)
        val limit = request.limit.coerceIn(1, MAX_PAGE_LIMIT)
        val records = ArrayList<WatchlistRemoteRecord>()
        var malformed = 0
        var hasMore = false
        var answered = 0
        var firstFailure: WeTrakrOutcome? = null
        val incomplete = LinkedHashSet<WatchlistRemoteKind>()
        for (target in WeTrakrWatchlistMapping.READ_TARGETS) {
            val kind = if (target == WeTrakrWatchlistMapping.TARGET_SHOWS) {
                WatchlistRemoteKind.SHOW
            } else {
                WatchlistRemoteKind.MOVIE
            }
            when (
                val outcome = send(WeTrakrWatchlistMapping.planningListRequest(target, page, limit))
            ) {
                is WeTrakrOutcome.Success -> {
                    val parsed = WeTrakrWatchlistMapping.records(outcome.response.body, target)
                        ?: return TrackingInboundOutcome.Rejected(SHAPE_CHANGED)
                    answered++
                    records += parsed.records
                    malformed += parsed.malformed
                    if (
                        WeTrakrWatchlistMapping.hasMorePage(
                            pageCount = WeTrakrInboundMapping.pageCount(outcome.response.headers),
                            page = page,
                            returnedCount = parsed.records.size + parsed.malformed,
                            limit = limit,
                        )
                    ) {
                        hasMore = true
                    }
                }
                is WeTrakrOutcome.Unauthorized -> return TrackingInboundOutcome.Unauthorized(outcome.reason)

                is WeTrakrOutcome.Retry, is WeTrakrOutcome.Rejected -> {
                    if (firstFailure == null) firstFailure = outcome
                    incomplete += kind
                }
            }
        }
        if (answered == 0) {

            return when (val failure = firstFailure) {
                is WeTrakrOutcome.Retry -> TrackingInboundOutcome.Unavailable(failure.reason)
                is WeTrakrOutcome.Rejected -> TrackingInboundOutcome.Rejected(failure.reason)
                else -> TrackingInboundOutcome.Rejected(SHAPE_CHANGED)
            }
        }
        return TrackingInboundOutcome.Ok(
            WatchlistRemotePage(
                records = records,
                malformed = malformed,
                next = if (hasMore) "$CURSOR_PREFIX${page + 1}" else null,
                truncating = hasMore,
                incompleteRemoteKinds = incomplete,
            ),
        )
    }

    override suspend fun add(ref: WatchlistMembershipRef): MembershipWriteResult =
        write(ref, Direction.ADD) { WeTrakrWatchlistMapping.addRequest(ref) }

    override suspend fun remove(ref: WatchlistMembershipRef): MembershipWriteResult =
        write(ref, Direction.REMOVE) { WeTrakrWatchlistMapping.removeRequest(ref) }

    override suspend fun clearResumeProgress(ref: WatchlistMembershipRef): MembershipWriteResult {
        val built = WeTrakrScrobbleMapping.resumeClearRequest(ref)
            ?: return MembershipWriteResult.Rejected(NO_RESUME_CLEAR_IDENTITY)
        return when (val outcome = send(built)) {
            is WeTrakrOutcome.Success -> {
                val result = when (val written = WeTrakrScrobbleMapping.resumeClearOutcome(outcome.response)) {
                    WeTrakrWriteOutcome.Delivered -> MembershipWriteResult.Delivered
                    is WeTrakrWriteOutcome.Rejected -> MembershipWriteResult.Rejected(written.reason)

                    WeTrakrWriteOutcome.AlreadyHeld -> MembershipWriteResult.Rejected(SHAPE_CHANGED)
                }
                traceResumeClear(result)
                result
            }
            is WeTrakrOutcome.Rejected -> {

                val result = if (isNoSession(outcome.reason)) {
                    MembershipWriteResult.Delivered
                } else {
                    MembershipWriteResult.Rejected(outcome.reason)
                }
                traceResumeClear(result)
                result
            }
            is WeTrakrOutcome.Retry -> {
                traceResumeClear(MembershipWriteResult.Retryable(outcome.reason))
                MembershipWriteResult.Retryable(outcome.reason)
            }
            is WeTrakrOutcome.Unauthorized -> {
                traceResumeClear(MembershipWriteResult.Unauthorized(outcome.reason))
                MembershipWriteResult.Unauthorized(outcome.reason)
            }
        }
    }

    private fun isNoSession(reason: String): Boolean =
        reason == "$HTTP_404:$NO_SESSION_CODE"

    private fun traceResumeClear(result: MembershipWriteResult) {
        val label = when (result) {
            MembershipWriteResult.Delivered -> "delivered"
            is MembershipWriteResult.Retryable -> "retryable"
            is MembershipWriteResult.Rejected -> "rejected"
            is MembershipWriteResult.Unauthorized -> "unauthorized"
            is MembershipWriteResult.Unsupported -> "unsupported"
        }
        val detail = when (result) {
            is MembershipWriteResult.Retryable -> result.reason
            is MembershipWriteResult.Rejected -> result.reason
            is MembershipWriteResult.Unauthorized -> result.reason
            else -> ""
        }
        trace?.invoke(
            "wetrakr resume_clear path=${WeTrakrHttpClient.SCROBBLE_PLAYING_PATH} " +
                "result=$label${if (detail.isEmpty()) "" else " reason=$detail"}",
        )
    }

    private suspend fun write(
        ref: WatchlistMembershipRef,
        direction: Direction,
        request: () -> WeTrakrRequest?,
    ): MembershipWriteResult {
        val titleLevel = (ref.kind == WatchlistKind.MOVIE || ref.kind == WatchlistKind.SHOW) &&
            (ref.remoteKind == WatchlistRemoteKind.MOVIE || ref.remoteKind == WatchlistRemoteKind.SHOW)
        if (!titleLevel) return MembershipWriteResult.Unsupported(TITLE_LEVEL_ONLY)
        val built = request() ?: return MembershipWriteResult.Rejected(NO_WRITE_ALIAS)
        return when (val outcome = send(built)) {
            is WeTrakrOutcome.Success -> classify(outcome.response.body, direction)
            is WeTrakrOutcome.Retry -> MembershipWriteResult.Retryable(outcome.reason)
            is WeTrakrOutcome.Rejected -> MembershipWriteResult.Rejected(outcome.reason)
            is WeTrakrOutcome.Unauthorized -> MembershipWriteResult.Unauthorized(outcome.reason)
        }
    }

    private fun classify(body: String, direction: Direction): MembershipWriteResult {
        val summary = WeTrakrWatchlistMapping.writeSummary(body)
            ?: return MembershipWriteResult.Rejected(SHAPE_CHANGED)
        trace?.invoke(
            "wetrakr watchlist_write direction=${direction.name.lowercase()} " +
                "added=${summary.added} existing=${summary.existing} " +
                "removed=${summary.removed} not_found=${summary.notFound} " +
                "errored=${summary.errored} " +
                "codes=${summary.errorCodes.joinToString(",").ifEmpty { "NONE" }}",
        )
        return when (direction) {
            Direction.ADD -> when {
                summary.added > 0 || summary.existing > 0 -> MembershipWriteResult.Delivered
                summary.notFound > 0 -> MembershipWriteResult.Rejected(NOT_FOUND)
                summary.errored > 0 -> MembershipWriteResult.Rejected(refused(summary.errorCodes))

                else -> MembershipWriteResult.Rejected(SHAPE_CHANGED)
            }
            Direction.REMOVE -> when {
                summary.removed > 0 -> MembershipWriteResult.Delivered
                summary.notFound > 0 && summary.errored == 0 -> MembershipWriteResult.Delivered
                summary.errored > 0 && summary.errorCodes.isNotEmpty() &&
                    summary.errorCodes.all { it.contains(NOTHING_TO_REMOVE, ignoreCase = true) } ->
                    MembershipWriteResult.Delivered
                summary.errored > 0 -> MembershipWriteResult.Rejected(refused(summary.errorCodes))
                else -> MembershipWriteResult.Rejected(SHAPE_CHANGED)
            }
        }
    }

    private fun refused(codes: List<String>): String =
        "$REFUSED:${codes.joinToString(",").ifEmpty { "unspecified" }}"

    private suspend fun send(request: WeTrakrRequest): WeTrakrOutcome {
        val token = readToken { auth.accessToken() }
            ?: return WeTrakrOutcome.Unauthorized(NO_AUTHORIZATION)
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

    private fun pageOf(request: WatchlistReadRequest): Int {
        val cursor = request.cursor ?: return request.page
        return cursor.removePrefix(CURSOR_PREFIX).toIntOrNull()?.takeIf { it >= 1 } ?: request.page
    }

    private enum class Direction { ADD, REMOVE }

    private companion object {

        const val MAX_PAGE_LIMIT = 100

        const val CURSOR_PREFIX = "page:"

        const val NOTHING_TO_REMOVE = "Nothing to remove"

        internal const val SHAPE_CHANGED = "wetrakr_watchlist_response_shape_changed"
        internal const val NO_AUTHORIZATION = "no valid WeTrakr authorization for this profile"
        internal const val TITLE_LEVEL_ONLY = "wetrakr_watchlist_write_is_title_level_only"
        internal const val NO_WRITE_ALIAS = "wetrakr_watchlist_write_requires_a_proven_alias"
        internal const val NOT_FOUND = "wetrakr_catalog_holds_no_such_title"
        internal const val REFUSED = "wetrakr_refused"

        internal const val NO_RESUME_CLEAR_IDENTITY = "wetrakr_resume_clear_requires_an_addressable_title"

        private const val HTTP_404 = "http_404"
        private const val NO_SESSION_CODE = "NO_SESSION"
    }
}
