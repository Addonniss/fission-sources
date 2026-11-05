package dev.infusion.tracking.mdblist

import dev.infusion.core.domain.tracking.TrackingInboundOutcome
import dev.infusion.core.domain.tracking.TrackingMediaKind
import dev.infusion.core.domain.tracking.TrackingReviewQuery
import dev.infusion.core.domain.tracking.TrackingReviewReadPort
import dev.infusion.core.model.metadata.MediaReview
import dev.infusion.core.model.metadata.ReviewPage
import java.time.Instant
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

class MDBListDiscussions(
    private val client: MDBListHttpClient,
    private val auth: MDBListAuthRepository,
) : TrackingReviewReadPort {

    override val providerId: String = MDBListAuthRepository.MDBLIST_PROVIDER_ID

    override val displayName: String = "MDBList"

    override suspend fun readReviews(
        query: TrackingReviewQuery,
        page: Int,
        limit: Int,
    ): TrackingInboundOutcome<ReviewPage> {
        if (!auth.isConnected()) return TrackingInboundOutcome.Unauthorized(NO_AUTHORIZATION)

        val tmdbId = query.externalIds.tmdbId?.takeIf { it > 0L }
            ?: return TrackingInboundOutcome.Rejected(NO_TMDB_ID)
        val safePage = page.coerceAtLeast(1)
        val safeLimit = limit.coerceIn(1, TrackingReviewReadPort.MAX_LIMIT)
        val offset = (safePage - 1).toLong() * safeLimit
        val request = MDBListRequest(
            method = MDBListMethod.GET,
            path = discussionPath(query.kind, tmdbId),
            query = mapOf(

                "sort" to DEFAULT_SORT,
                "limit" to safeLimit.toString(),
                "offset" to offset.toString(),
            ),

            readBudget = MDBListReadBudget.LOOKUP,
        )
        val token = auth.accessToken() ?: return TrackingInboundOutcome.Unauthorized(NO_AUTHORIZATION)
        return when (val outcome = client.execute(request, token)) {
            is MDBListOutcome.Success -> {
                val parsed = MDBListDiscussionMapping.page(
                    body = outcome.response.body,
                    requestedPage = safePage,
                    requestedLimit = safeLimit,
                )
                parsed?.let { TrackingInboundOutcome.Ok(it) }
                    ?: TrackingInboundOutcome.Rejected(MALFORMED_RESPONSE)
            }
            is MDBListOutcome.Unauthorized -> {

                val refreshed = readToken { auth.refresh() }
                    ?: return TrackingInboundOutcome.Unauthorized(outcome.reason)
                when (val retry = client.execute(request, refreshed)) {
                    is MDBListOutcome.Success -> MDBListDiscussionMapping.page(
                        body = retry.response.body,
                        requestedPage = safePage,
                        requestedLimit = safeLimit,
                    )?.let { TrackingInboundOutcome.Ok(it) }
                        ?: TrackingInboundOutcome.Rejected(MALFORMED_RESPONSE)
                    is MDBListOutcome.Retry -> TrackingInboundOutcome.Unavailable(retry.reason)
                    is MDBListOutcome.Rejected -> TrackingInboundOutcome.Rejected(retry.reason)
                    is MDBListOutcome.Unauthorized -> TrackingInboundOutcome.Unauthorized(retry.reason)
                }
            }
            is MDBListOutcome.Retry -> TrackingInboundOutcome.Unavailable(outcome.reason)
            is MDBListOutcome.Rejected -> TrackingInboundOutcome.Rejected(outcome.reason)
        }
    }

    private suspend fun readToken(read: suspend () -> String?): String? = try {
        read()?.takeIf { it.isNotBlank() }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        null
    }

    internal fun discussionPath(kind: TrackingMediaKind, tmdbId: Long): String = when (kind) {
        TrackingMediaKind.MOVIE -> "/discussion/$PROVIDER_SEGMENT/movie/$tmdbId"
        TrackingMediaKind.SHOW -> "/discussion/$PROVIDER_SEGMENT/show/$tmdbId"
    }

    companion object {

        const val DEFAULT_SORT: String = "newest"

        const val PROVIDER_SEGMENT: String = "tmdb"

        const val NO_AUTHORIZATION: String = "mdblist_not_authorized"

        const val NO_TMDB_ID: String = "mdblist_review_needs_tmdb_id"

        const val MALFORMED_RESPONSE: String = "mdblist_comments_malformed"
    }
}

internal object MDBListDiscussionMapping {

    private const val PROVIDER_LABEL = "MDBList"

    fun page(body: String, requestedPage: Int, requestedLimit: Int): ReviewPage? = runCatching {
        val root = JSONObject(body)
        val array = root.optJSONArray("comments") ?: return null
        val items = ArrayList<MediaReview>(array.length())
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index) ?: continue
            val review = review(row) ?: continue
            items += review
        }
        val total = root.opt("comment_count")?.let { positiveInt(it) }
        val totalPages = when {

            total != null && requestedLimit > 0 -> ceil(total.toDouble() / requestedLimit).toInt()
                .coerceAtLeast(1)

            else -> requestedPage
        }
        ReviewPage(
            items = items,
            page = requestedPage,
            totalPages = totalPages.coerceAtLeast(requestedPage),
            totalResults = total,
        )
    }.getOrNull()

    private fun review(row: JSONObject): MediaReview? {
        val id = positiveLong(row.opt("id")) ?: return null
        val content = row.opt("content")?.let { scalar(it) }?.takeIf { it.isNotBlank() } ?: return null
        val user = row.optJSONObject("user") ?: return null
        val author = author(user) ?: return null
        return MediaReview(
            reviewId = id.toString(),
            author = author,
            authorRating = row.opt("user_rating")?.let { score(it) },
            content = content,
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

    private fun positiveInt(value: Any?): Int? = when (value) {
        is Number -> value.toInt().takeIf { it >= 0 }
        is String -> value.trim().toIntOrNull()?.takeIf { it >= 0 }
        else -> null
    }
}
