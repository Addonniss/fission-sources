package dev.infusion.tracking.wetrakr

import dev.infusion.core.domain.tracking.TrackingExternalIds
import dev.infusion.core.domain.tracking.TrackingInboundOutcome
import dev.infusion.core.domain.tracking.TrackingMediaKind
import dev.infusion.core.domain.tracking.TrackingReviewQuery
import dev.infusion.core.domain.tracking.TrackingReviewReadPort
import dev.infusion.core.model.metadata.ImageRef
import dev.infusion.core.model.metadata.MediaReview
import dev.infusion.core.model.metadata.ReviewPage
import java.time.Instant
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

class WeTrakrComments(
    private val client: WeTrakrHttpClient,
    private val auth: WeTrakrAuthRepository,
) : TrackingReviewReadPort {

    override val providerId: String = WeTrakrAuthRepository.WETRAKR_PROVIDER_ID

    override val displayName: String = PROVIDER_LABEL

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
            PathId.Unknown -> return TrackingInboundOutcome.Rejected(ITEM_NOT_FOUND)

            is PathId.Failed -> return resolved.outcome?.toReviewOutcome()
            ?: TrackingInboundOutcome.Rejected(MALFORMED_RESPONSE)
        }
        return when (val outcome = send(commentsRequest(query.kind, pathId.id, safePage, safeLimit))) {
            is WeTrakrOutcome.Success -> {
                val parsed = WeTrakrCommentMapping.page(
                    body = outcome.response.body,
                    headers = outcome.response.headers,
                    requestedPage = safePage,
                )
                parsed?.let { TrackingInboundOutcome.Ok(it) }
                    ?: TrackingInboundOutcome.Rejected(MALFORMED_RESPONSE)
            }
            is WeTrakrOutcome.Retry -> TrackingInboundOutcome.Unavailable(outcome.reason)
            is WeTrakrOutcome.Rejected -> TrackingInboundOutcome.Rejected(outcome.reason)
            is WeTrakrOutcome.Unauthorized -> TrackingInboundOutcome.Unauthorized(outcome.reason)
        }
    }

    internal fun commentsRequest(
        kind: TrackingMediaKind,
        weTrakrId: Long,
        page: Int,
        limit: Int,
    ): WeTrakrRequest = WeTrakrRequest(
        method = WeTrakrMethod.GET,
        path = commentsPath(kind, weTrakrId),
        query = linkedMapOf(
            "page" to page.toString(),
            "limit" to limit.toString(),
            "sort" to DEFAULT_SORT,
        ),
        optionalAuth = true,

        limits = WeTrakrHttpClient.READ_LIMITS,
    )

    internal fun commentsPath(kind: TrackingMediaKind, weTrakrId: Long): String = when (kind) {
        TrackingMediaKind.MOVIE -> "/${TYPE_MOVIES}/$weTrakrId$COMMENTS_SEGMENT"
        TrackingMediaKind.SHOW -> "/${TYPE_SHOWS}/$weTrakrId$COMMENTS_SEGMENT"
    }

    private suspend fun resolvePathId(query: TrackingReviewQuery): PathId {
        var sawNotFound = false
        for (authority in LOOKUP_LADDER) {
            val value = aliasFor(query.externalIds, authority) ?: continue

            if (!PATH_SAFE.matches(value)) return PathId.Failed(outcome = null)
            when (
                val outcome = send(
                    WeTrakrInboundMapping.externalIdRequest(authority, value, query.kind),
                )
            ) {
                is WeTrakrOutcome.Success -> {
                    val id = WeTrakrInboundMapping.externalIdOf(outcome.response.body)
                        ?: return PathId.Failed(outcome = null)
                    return PathId.Value(id)
                }
                is WeTrakrOutcome.Rejected -> when {
                    isNotFoundReason(outcome.reason) -> sawNotFound = true

                    outcome.reason.contains(AMBIGUOUS_STATUS) -> return PathId.Failed(outcome)

                    outcome.reason.contains(BAD_REQUEST_STATUS) -> Unit
                    else -> return PathId.Failed(outcome)
                }
                is WeTrakrOutcome.Retry -> return PathId.Failed(outcome)
                is WeTrakrOutcome.Unauthorized -> return PathId.Failed(outcome)
            }
        }
        return if (sawNotFound) PathId.Unknown else PathId.Failed(outcome = null)
    }

    private fun aliasFor(ids: TrackingExternalIds, authority: String): String? = when (authority) {
        TrackingExternalIds.AUTHORITY_IMDB -> ids.imdbId?.trim()?.takeIf { it.isNotEmpty() }
        TrackingExternalIds.AUTHORITY_TMDB ->
            ids.tmdbId?.takeIf { it > 0L }?.toString()
        TrackingExternalIds.AUTHORITY_TVDB ->
            ids.tvdbId?.takeIf { it > 0L }?.toString()
        else -> null
    }

    private suspend fun send(request: WeTrakrRequest): WeTrakrOutcome {
        val token = readToken { auth.accessToken() }
        val outcome = client.execute(request, token)
        if (outcome !is WeTrakrOutcome.Unauthorized) return outcome
        if (token == null) return outcome
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

    private fun WeTrakrOutcome.toReviewOutcome(): TrackingInboundOutcome<ReviewPage> = when (this) {
        is WeTrakrOutcome.Retry -> TrackingInboundOutcome.Unavailable(reason)
        is WeTrakrOutcome.Rejected -> TrackingInboundOutcome.Rejected(reason)
        is WeTrakrOutcome.Unauthorized -> TrackingInboundOutcome.Unauthorized(reason)
        is WeTrakrOutcome.Success -> TrackingInboundOutcome.Rejected(MALFORMED_RESPONSE)
    }

    private sealed interface PathId {
        data class Value(val id: Long) : PathId
        data object Unknown : PathId
        data class Failed(val outcome: WeTrakrOutcome?) : PathId
    }

    internal companion object {

        const val PROVIDER_LABEL: String = "WeTrakr"

        const val DEFAULT_SORT: String = "top"

        const val COMMENTS_SEGMENT: String = "/comments"

        private const val TYPE_MOVIES = "movies"
        private const val TYPE_SHOWS = "shows"

        const val NO_AUTHORIZATION: String = "wetrakr_no_authorization"

        const val ITEM_NOT_FOUND: String = "wetrakr_item_not_found"

        const val MALFORMED_RESPONSE: String = "wetrakr_comments_malformed"

        private const val NOT_FOUND_REASON = "http_404"

        private const val AMBIGUOUS_STATUS = "http_409"

        private const val BAD_REQUEST_STATUS = "http_400"

        private fun isNotFoundReason(reason: String): Boolean = reason.startsWith(NOT_FOUND_REASON)

        private val LOOKUP_LADDER = listOf(
            TrackingExternalIds.AUTHORITY_IMDB,
            TrackingExternalIds.AUTHORITY_TMDB,
            TrackingExternalIds.AUTHORITY_TVDB,
        )

        private val PATH_SAFE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    }
}

internal object WeTrakrCommentMapping {

    fun page(body: String, headers: Map<String, String>, requestedPage: Int): ReviewPage? =
        runCatching {
            val array = JSONArray(body)
            val items = ArrayList<MediaReview>(array.length())
            for (index in 0 until array.length()) {
                val row = array.optJSONObject(index) ?: continue
                val review = review(row) ?: continue
                items += review
            }

            if (array.length() > 0 && items.isEmpty()) return null
            val pageCount = WeTrakrInboundMapping.pageCount(headers)
                ?.takeIf { it > 0 }
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
        val text = row.opt("text")?.let { scalar(it) }?.takeIf { it.isNotBlank() } ?: return null
        val user = row.optJSONObject("user") ?: return null
        val author = author(user) ?: return null
        return MediaReview(
            reviewId = id.toString(),
            author = author,
            authorRating = row.opt("rating")?.let { score(it) },
            authorAvatar = avatar(user),
            content = text,
            createdAt = row.opt("comment_added_at")?.let { scalar(it) }?.let { instant(it) },
            providerLabel = WeTrakrComments.PROVIDER_LABEL,
        )
    }

    private fun author(user: JSONObject): String? {
        val info = user.optJSONObject("info")
        info?.let { declared ->
            declared.opt("display_name")?.let { scalar(it) }?.takeIf { it.isNotBlank() }?.let { return it }
            declared.opt("username")?.let { scalar(it) }?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun avatar(user: JSONObject): ImageRef? = user.optJSONObject("images")
        ?.opt("avatar")
        ?.let { scalar(it) }
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { ImageRef(IMAGE_PROVIDER, it) }.getOrNull() }

    private fun score(value: Any?): Double? {
        val raw = when (value) {
            null, JSONObject.NULL -> return null
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull() ?: return null
            else -> return null
        }
        return raw.takeIf { it in 0.0..10.0 }
    }

    private fun instant(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()

    private fun header(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
            ?.substringBefore(',')?.trim()

    private fun scalar(value: Any?): String? = when (value) {
        null, JSONObject.NULL -> null
        is String -> value
        is Number -> value.toString()
        is Boolean -> value.toString()
        else -> null
    }

    private fun positiveLong(value: Any?): Long? = when (value) {
        is Number -> value.toLong().takeIf { it > 0L }
        is String -> value.trim().toLongOrNull()?.takeIf { it > 0L }
        else -> null
    }

    private const val IMAGE_PROVIDER = "wetrakr"

    private const val ITEM_COUNT_HEADER = "X-Pagination-Item-Count"
}
