package dev.infusion.tracking.trakt

import dev.infusion.core.domain.tracking.TrackingInboundOutcome
import dev.infusion.core.domain.tracking.TrackingMediaKind
import dev.infusion.core.domain.tracking.TrackingReviewQuery
import dev.infusion.core.domain.tracking.TrackingReviewReadPort
import dev.infusion.core.model.metadata.MediaReview
import dev.infusion.core.model.metadata.ReviewPage
import java.time.Instant
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

class TraktReviews(
    private val client: TraktHttpClient,
    private val auth: TraktAuthRepository,
) : TrackingReviewReadPort {

    override val providerId: String = TraktAuthRepository.TRAKT_PROVIDER_ID

    override val displayName: String = "Trakt"

    override suspend fun readReviews(
        query: TrackingReviewQuery,
        page: Int,
        limit: Int,
    ): TrackingInboundOutcome<ReviewPage> {
        if (!auth.isConnected()) return TrackingInboundOutcome.Unauthorized(NO_AUTHORIZATION)
        val safePage = page.coerceAtLeast(1)
        val safeLimit = limit.coerceIn(1, TrackingReviewReadPort.MAX_LIMIT)
        val pathId = when (val resolved = resolvePathId(query)) {
            is PathId.Value -> resolved
            PathId.Unknown -> return TrackingInboundOutcome.Rejected(NOT_FOUND_REASON)

            is PathId.Failed -> return resolved.outcome?.toOutcome()
                ?: TrackingInboundOutcome.Rejected(MALFORMED_RESPONSE)
        }
        val request = TraktRequest(
            method = TraktMethod.GET,
            path = commentsPath(query.kind, pathId.id),
            query = mapOf(

                "sort" to DEFAULT_SORT,
                "page" to safePage.toString(),
                "limit" to safeLimit.toString(),
            ),

            readBudget = TraktReadBudget.LOOKUP,
        )
        return when (val outcome = send(request)) {
            is TraktOutcome.Success -> {
                val parsed = TraktCommentMapping.page(
                    body = outcome.response.body,
                    headers = outcome.response.headers,
                    requestedPage = safePage,
                )
                parsed?.let { TrackingInboundOutcome.Ok(it) }
                    ?: TrackingInboundOutcome.Rejected(MALFORMED_RESPONSE)
            }
            is TraktOutcome.Retry -> TrackingInboundOutcome.Unavailable(outcome.reason)
            is TraktOutcome.Rejected -> TrackingInboundOutcome.Rejected(outcome.reason)
            is TraktOutcome.Unauthorized -> TrackingInboundOutcome.Unauthorized(outcome.reason)
        }
    }

    private suspend fun resolvePathId(query: TrackingReviewQuery): PathId {
        TraktIdLookup.directPathId(query.externalIds)?.let { return PathId.Value(it) }
        for (authority in TraktIdLookup.ladderFor(query.kind)) {
            val value = TraktIdLookup.aliasFor(query.externalIds, authority) ?: continue
            return when (val outcome = send(TraktIdLookup.request(authority, value, query.kind))) {
                is TraktOutcome.Success -> when (
                    val resolved = TraktIdLookup.resolve(outcome.response.body, authority, value, query.kind)
                ) {
                    is TraktPathId.Resolved -> PathId.Value(resolved.value)
                    TraktPathId.Unknown -> PathId.Unknown
                    TraktPathId.Undecidable -> PathId.Failed(outcome = null)
                }
                is TraktOutcome.Rejected ->
                    if (outcome.reason == TraktHttpClient.NOT_FOUND_REASON) {
                        PathId.Unknown
                    } else {
                        PathId.Failed(outcome)
                    }
                is TraktOutcome.Retry -> PathId.Failed(outcome)
                is TraktOutcome.Unauthorized -> PathId.Failed(outcome)
            }
        }
        return PathId.Unknown
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

    private fun TraktOutcome.toOutcome(): TrackingInboundOutcome<ReviewPage> = when (this) {
        is TraktOutcome.Retry -> TrackingInboundOutcome.Unavailable(reason)
        is TraktOutcome.Rejected -> TrackingInboundOutcome.Rejected(reason)
        is TraktOutcome.Unauthorized -> TrackingInboundOutcome.Unauthorized(reason)
        is TraktOutcome.Success -> TrackingInboundOutcome.Rejected(MALFORMED_RESPONSE)
    }

    internal fun commentsPath(kind: TrackingMediaKind, pathId: String): String = when (kind) {
        TrackingMediaKind.MOVIE -> "/movies/${encode(pathId)}/comments/$DEFAULT_SORT"
        TrackingMediaKind.SHOW -> "/shows/${encode(pathId)}/comments/$DEFAULT_SORT"
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value.trim(), "UTF-8")

    private sealed interface PathId {
        data class Value(val id: String) : PathId
        data object Unknown : PathId
        data class Failed(val outcome: TraktOutcome?) : PathId
    }

    companion object {

        const val DEFAULT_SORT: String = "likes"

        const val NO_AUTHORIZATION: String = "trakt_not_authorized"

        const val MALFORMED_RESPONSE: String = "trakt_comments_malformed"

        const val NOT_FOUND_REASON: String = "trakt_item_not_found"
    }
}

internal object TraktCommentMapping {

    private const val PROVIDER_LABEL = "Trakt"

    fun page(body: String, headers: Map<String, String>, requestedPage: Int): ReviewPage? = runCatching {
        val array = JSONArray(body)
        val items = ArrayList<MediaReview>(array.length())
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index) ?: continue
            val review = review(row) ?: continue
            items += review
        }
        val pageCount = header(headers, PAGE_COUNT_HEADER)?.toIntOrNull()?.takeIf { it > 0 }
        val itemCount = header(headers, ITEM_COUNT_HEADER)?.toIntOrNull()?.takeIf { it >= 0 }
        ReviewPage(
            items = items,
            page = requestedPage,

            totalPages = pageCount ?: requestedPage,
            totalResults = itemCount,
        )
    }.getOrNull()

    private fun review(row: JSONObject): MediaReview? {
        val id = positiveLong(row.opt("id")) ?: return null
        val text = row.opt("comment")?.let { scalar(it) }?.takeIf { it.isNotBlank() } ?: return null
        val user = row.optJSONObject("user")
        val username = user?.let { author(it) } ?: return null
        val stats = row.optJSONObject("user_stats")
        val rating = stats?.opt("rating")?.let { score(it) }
        return MediaReview(
            reviewId = id.toString(),
            author = username,
            authorRating = rating,
            content = text,
            createdAt = row.opt("created_at")?.let { scalar(it) }?.let { instant(it) },
            providerLabel = PROVIDER_LABEL,
        )
    }

    private fun author(user: JSONObject): String? {
        user.opt("username")?.let { scalar(it) }?.takeIf { it.isNotBlank() }?.let { return it }
        user.opt("name")?.let { scalar(it) }?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    private fun score(value: Any?): Double? = when (value) {
        null, JSONObject.NULL -> null
        is Number -> value.toDouble().takeIf { it > 0.0 }
        is String -> value.trim().toDoubleOrNull()?.takeIf { it > 0.0 }
        else -> null
    }

    private fun instant(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()

    private fun header(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun scalar(value: Any?): String? = when (value) {
        null, JSONObject.NULL -> null
        is String -> value.trim()
        is Number -> value.toString()
        is Boolean -> value.toString()
        else -> null
    }

    private fun positiveLong(value: Any?): Long? = when (value) {
        is Number -> value.toLong().takeIf { it > 0L }
        is String -> value.trim().toLongOrNull()?.takeIf { it > 0L }
        else -> null
    }

    private const val PAGE_COUNT_HEADER = "X-Pagination-Page-Count"
    private const val ITEM_COUNT_HEADER = "X-Pagination-Item-Count"
}
