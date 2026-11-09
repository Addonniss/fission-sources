package dev.infusion.source.jellyfin.client

import dev.infusion.core.network.CanonicalUrl
import dev.infusion.core.network.DiagnosticReason
import dev.infusion.core.network.HardenedHttpClient
import dev.infusion.core.network.HardenedRequest
import dev.infusion.core.network.MediaBrowserAuthorization
import dev.infusion.core.network.NetworkLimits
import dev.infusion.core.network.NetworkPolicyException
import dev.infusion.core.network.Redactor
import dev.infusion.source.jellyfin.session.JellyfinForensicsLog
import dev.infusion.source.jellyfin.wire.AuthResponse
import dev.infusion.source.jellyfin.wire.ItemImageInfo
import dev.infusion.source.jellyfin.wire.ItemSummary
import dev.infusion.source.jellyfin.wire.ItemsPage
import dev.infusion.source.jellyfin.wire.LyricLine
import dev.infusion.source.jellyfin.wire.MediaSegment
import dev.infusion.source.jellyfin.wire.PlaybackInfoResponse
import dev.infusion.source.jellyfin.wire.QuickConnectResult
import dev.infusion.source.jellyfin.wire.ServerPublicInfo
import dev.infusion.source.jellyfin.wire.UserInfo
import dev.infusion.source.jellyfin.wire.parseLyrics
import dev.infusion.source.jellyfin.wire.parseAuthResponse
import dev.infusion.source.jellyfin.wire.parseItem
import dev.infusion.source.jellyfin.wire.parseItemImageList
import dev.infusion.source.jellyfin.wire.FilterValueSources
import dev.infusion.source.jellyfin.wire.parseFilterValues
import dev.infusion.source.jellyfin.wire.parseItemsPage
import dev.infusion.source.jellyfin.wire.parseRecommendationGroups
import dev.infusion.source.jellyfin.wire.parseMediaSegments
import dev.infusion.source.jellyfin.wire.parsePlaybackInfoResponse
import dev.infusion.source.jellyfin.wire.parseQuickConnectResult
import dev.infusion.source.jellyfin.wire.parseServerPublicInfo
import dev.infusion.source.jellyfin.wire.parseUserInfo
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val USER_DATA_TICKS_PER_MS: Long = 10_000L

const val JELLYFIN_ITEM_FIELDS = "Overview,ProductionYear,ProviderIds,PremiereDate"

const val JELLYFIN_UPCOMING_FIELDS =
    "Overview,ProductionYear,ProviderIds,SeriesId,ParentIndexNumber,IndexNumber,PremiereDate"

data class JellyfinFailure(
    val kind: FailureKind,
    val status: Int?,
    val message: String,
    val retryable: Boolean,
) {
    override fun toString(): String = "JellyfinFailure(${kind.name}, status=${status ?: "-"}, retryable=$retryable): $message"
}

enum class FailureKind {
    UNAUTHORIZED, FORBIDDEN, NOT_FOUND, RATE_LIMITED, BAD_REQUEST, SERVER_ERROR,
    TIMEOUT, NETWORK, LIMIT_EXCEEDED, POLICY, UNKNOWN,

    DNS,

    CLEARTEXT_BLOCKED,

    NEGOTIATION,
}

sealed class ApiResult<out T> {
    data class Ok<T>(val value: T, val status: Int) : ApiResult<T>()
    data class Err<T>(val failure: JellyfinFailure) : ApiResult<T>()

    val failureOrNull: JellyfinFailure? get() = (this as? Err<T>)?.failure
    val valueOrNull: T? get() = (this as? Ok<T>)?.value
    val statusOrNull: Int? get() = (this as? Ok<T>)?.status
}

data class JellyfinRetryPolicy(
    val rateLimitedRetries: Int = 2,
    val serverErrorRetries: Int = 1,
    val baseBackoffMs: Long = 500,
    val maxBackoffMs: Long = 3_000,
)

object Endpoint {
    const val SYSTEM_INFO_PUBLIC = "/System/Info/Public"
    const val AUTHENTICATE = "/Users/AuthenticateByName"
    const val ME = "/Users/Me"
    const val USER_VIEWS = "/UserViews"
    const val USERS_ITEMS = "/Users/{userId}/Items"
    const val USERS_ITEMS_RESUME = "/Users/{userId}/Items/Resume"
    const val USERS_ITEMS_LATEST = "/Users/{userId}/Items/Latest"
    const val SHOWS_NEXT_UP = "/Shows/NextUp"
    const val USER_ITEM = "/Users/{userId}/Items/{itemId}"

    const val USER_ITEM_USER_DATA = "/UserItems/{itemId}/UserData"
    const val SHOWS_SEASONS = "/Shows/{seriesId}/Seasons"
    const val SHOWS_EPISODES = "/Shows/{seriesId}/Episodes"
    const val PLAYBACK_INFO = "/Items/{itemId}/PlaybackInfo"

    const val LYRICS = "/Audio/{itemId}/Lyrics"
    const val SESSIONS_PLAYING = "/Sessions/Playing"
    const val SESSIONS_PROGRESS = "/Sessions/Playing/Progress"
    const val SESSIONS_STOPPED = "/Sessions/Playing/Stopped"
    const val SESSIONS_PING = "/Sessions/Playing/Ping"
    const val SESSIONS_LOGOUT = "/Sessions/Logout"
    const val ITEM_IMAGE = "/Items/{itemId}/Images/{type}"
    const val ITEM_IMAGES = "/Items/{itemId}/Images"

    const val MEDIA_SEGMENTS = "/MediaSegments/{itemId}"

    const val ITEMS_SUGGESTIONS = "/Items/Suggestions"
    const val SHOWS_UPCOMING = "/Shows/Upcoming"
    const val GENRES = "/Genres"
    const val STUDIOS = "/Studios"

    const val ARTISTS = "/Artists"
    const val ALBUM_ARTISTS = "/Artists/AlbumArtists"

    const val PLAYLIST_ITEMS = "/Playlists/{playlistId}/Items"

    const val ITEMS_FILTERS = "/Items/Filters"

    const val MOVIES_RECOMMENDATIONS = "/Movies/Recommendations"

    const val SYSTEM_INFO = "/System/Info"
    const val USERS = "/Users"
    const val USER = "/Users/{userId}"
    const val QUICK_CONNECT_ENABLED = "/QuickConnect/Enabled"
    const val QUICK_CONNECT_INITIATE = "/QuickConnect/Initiate"
    const val QUICK_CONNECT_CONNECT = "/QuickConnect/Connect"
    const val AUTHENTICATE_QUICK_CONNECT = "/Users/AuthenticateWithQuickConnect"

    const val USER_IMAGE = "/UserImage"

    const val SOCKET = "/socket"
}

class JellyfinClient(
    private val origin: CanonicalUrl,
    private val transport: HardenedHttpClient,
    private val deviceId: String? = null,
    private val clientName: String = "fission",
    private val clientVersion: String = "0.1.0",
    private val retryPolicy: JellyfinRetryPolicy = JellyfinRetryPolicy(),
) : java.io.Closeable {

    val hardenedTransport: HardenedHttpClient get() = transport

    val hardenedWebSockets: dev.infusion.core.network.HardenedWebSocketFactory?
        get() = transport as? dev.infusion.core.network.HardenedWebSocketFactory

    private val effectiveDeviceIdValue: String = deviceId?.takeIf { it.isNotBlank() }
        ?: "infusion-" + UUID.randomUUID().toString().substring(0, 8)

    fun embyAuthHeader(deviceId: String = effectiveDeviceIdValue): String =
        MediaBrowserAuthorization.identity(clientName, "Android", deviceId, clientVersion)

    fun authHeaders(token: String?): Map<String, String> =
        MediaBrowserAuthorization.headers(embyAuthHeader(), token)

    val effectiveDeviceId: String get() = effectiveDeviceIdValue

    val originLabel: String get() = origin.origin

    fun socketUrl(): CanonicalUrl = buildUrl(Endpoint.SOCKET, emptyMap())

    private suspend fun <T> json(
        path: String,
        token: String?,
        method: String = "GET",
        query: Map<String, String> = emptyMap(),
        body: JSONObject? = null,
        limits: NetworkLimits = NetworkLimits.DEFAULT,
        retryable: Boolean = true,
        arrayBody: Boolean = false,

        scalarBody: Boolean = false,
        parse: (Int, JSONObject?) -> ApiResult<T>,
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        var attempt = 0
        retry@ while (true) {
            val url = buildUrl(path, query)
            val response = try {
                val builder = buildMap {
                    putAll(authHeaders(token))

                    if (body != null) put("Content-Type", "application/json")
                }
                transport.execute(
                    HardenedRequest(
                        url = url,
                        method = method,
                        headers = builder,
                        limits = limits,
                        body = body?.toString()?.toByteArray(StandardCharsets.UTF_8),
                        sensitiveHeaders = JELLYFIN_SENSITIVE_HEADERS,
                    ),
                )
            } catch (e: NetworkPolicyException) {
                JellyfinForensicsLog.event(
                    "client-policy-failure",
                    "reason=${e.reason.name} causes=${e.causeChain()}",
                )
                return@withContext ApiResult.Err(
                    JellyfinFailure(
                        kind = when (e.reason) {
                            DiagnosticReason.TIMEOUT -> FailureKind.TIMEOUT
                            DiagnosticReason.LIMIT_EXCEEDED -> FailureKind.LIMIT_EXCEEDED
                            DiagnosticReason.CONCURRENCY_EXCEEDED -> FailureKind.LIMIT_EXCEEDED
                            DiagnosticReason.DNS_FAILED -> FailureKind.DNS
                            DiagnosticReason.CLEARTEXT_BLOCKED -> FailureKind.CLEARTEXT_BLOCKED
                            DiagnosticReason.CONNECTION_FAILED -> FailureKind.NETWORK
                            else -> FailureKind.POLICY
                        },
                        status = null,
                        message = "policy/network failure: ${e.reason.name}",
                        retryable = e.reason in RETRYABLE_REASONS,
                    ),
                )
            } catch (e: IOException) {

                JellyfinForensicsLog.event(
                    "client-io-failure",
                    "io=${e::class.simpleName} causes=${e.causeChain()}",
                )
                return@withContext ApiResult.Err(
                    JellyfinFailure(
                        kind = FailureKind.NETWORK,
                        status = null,
                        message = "io: ${e::class.simpleName}",
                        retryable = true,
                    ),
                )
            }

            var outcome: ApiResult<T>? = null
            response.use { resp ->
                val status = resp.statusCode
                if (status == 429 || (status in 500..599)) {
                    val retriesLeft = if (status == 429) retryPolicy.rateLimitedRetries else retryPolicy.serverErrorRetries
                    if (retryable && attempt < retriesLeft) {
                        attempt++
                        val retryAfter = resp.headers["retry-after"]?.toLongOrNull()
                        val delay = if (retryAfter != null) {
                            minOf(retryAfter * 1000L, retryPolicy.maxBackoffMs)
                        } else {
                            minOf(retryPolicy.baseBackoffMs * (1L shl (attempt - 1)), retryPolicy.maxBackoffMs)
                        }
                        Thread.sleep(delay)
                        outcome = null
                        return@use
                    }
                    outcome = ApiResult.Err(
                        JellyfinFailure(
                            kind = if (status == 429) FailureKind.RATE_LIMITED else FailureKind.SERVER_ERROR,
                            status = status,
                            message = "http $status after $attempt retries",
                            retryable = retryable,
                        ),
                    )
                    return@use
                }
                val read = readBounded(resp.body, limits.maxResponseBytes)
                val bytes = when (read) {
                    is BoundedRead.Ok -> read.bytes
                    is BoundedRead.Overflow -> {
                        outcome = ApiResult.Err(
                            JellyfinFailure(FailureKind.LIMIT_EXCEEDED, status, "response exceeds limit", retryable = false),
                        )
                        return@use
                    }
                    is BoundedRead.Io -> {
                        outcome = ApiResult.Err(
                            JellyfinFailure(FailureKind.NETWORK, status, "response read failed", retryable = true),
                        )
                        return@use
                    }
                }

                val parsed = try {
                    if (bytes.isEmpty()) {
                        null
                    } else {
                        val text = String(bytes, StandardCharsets.UTF_8).trim()
                        when {
                            text.startsWith("[") && arrayBody -> {
                                val arr = JSONArray(text)
                                JSONObject()
                                    .put("Items", arr)
                                    .put("TotalRecordCount", arr.length())
                            }
                            scalarBody -> JSONObject().put("Scalar", text)
                            else -> JSONObject(text)
                        }
                    }
                } catch (_: org.json.JSONException) {
                    null
                }
                outcome = parse(status, parsed)
            }
            val result = outcome ?: continue@retry
            return@withContext result
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private fun readBounded(stream: java.io.InputStream, max: Long): BoundedRead {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        try {
            while (total < max) {
                val n = stream.read(buf, 0, minOf(buf.size, (max - total).toInt()))
                if (n < 0) return BoundedRead.Ok(out.toByteArray())
                out.write(buf, 0, n)
                total += n
            }

            val extra = stream.read()
            if (extra >= 0) return BoundedRead.Overflow
            return BoundedRead.Ok(out.toByteArray())
        } catch (e: IOException) {

            var t: Throwable? = e
            while (t != null) {
                if (t is NetworkPolicyException && t.reason == DiagnosticReason.LIMIT_EXCEEDED) {
                    return BoundedRead.Overflow
                }
                t = t.cause
            }
            return BoundedRead.Io(e)
        }
    }

    private fun buildUrl(path: String, query: Map<String, String>): CanonicalUrl {
        val rawPath = if (path.startsWith("/")) path else "/$path"
        val q = if (query.isEmpty()) "" else query.entries.joinToString("&") { (k, v) ->
            "${enc(k)}=${enc(v)}"
        }
        val raw = origin.toString().trimEnd('/') + rawPath + if (q.isEmpty()) "" else "?$q"
        return try {
            dev.infusion.core.network.UrlParser.parse(raw)
        } catch (e: NetworkPolicyException) {
            throw JellyfinClientException("invalid url for ${Redactor.url(raw)}", e)
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    suspend fun publicInfo(limits: NetworkLimits = NetworkLimits.DEFAULT): ApiResult<ServerPublicInfo> =
        json(Endpoint.SYSTEM_INFO_PUBLIC, token = null, limits = limits, retryable = true) { status, parsed ->
            if (status in 200..299 && parsed != null) {
                ApiResult.Ok(parseServerPublicInfo(parsed), status)
            } else {
                ApiResult.Err(JellyfinFailure(classify(status), status, "publicInfo", retryable = false))
            }
        }

    suspend fun authenticate(
        username: String,
        password: String,
        deviceId: String = effectiveDeviceIdValue,
        limits: NetworkLimits = NetworkLimits.DEFAULT,
    ): ApiResult<AuthResponse> {
        val body = JSONObject().put("Username", username).put("Pw", password)
        return json(
            Endpoint.AUTHENTICATE,
            token = null,
            method = "POST",
            body = body,
            limits = limits,
            retryable = false,
        ) { status, parsed ->
            if (status in 200..299 && parsed != null) {
                ApiResult.Ok(parseAuthResponse(parsed), status)
            } else {
                ApiResult.Err(JellyfinFailure(classify(status), status, "authenticate", retryable = false))
            }
        }
    }

    suspend fun me(token: String, limits: NetworkLimits = NetworkLimits.DEFAULT): ApiResult<UserInfo> =
        json(Endpoint.ME, token, limits = limits) { status, parsed ->
            if (status in 200..299 && parsed != null) {
                ApiResult.Ok(parseUserInfo(parsed), status)
            } else {
                ApiResult.Err(JellyfinFailure(classify(status), status, "me", retryable = false))
            }
        }

    suspend fun logout(token: String, limits: NetworkLimits = NetworkLimits.DEFAULT): ApiResult<Unit> =
        json(Endpoint.SESSIONS_LOGOUT, token, method = "POST", body = JSONObject(), limits = limits, retryable = false) { status, _ ->
            if (status in 200..299 || status == 204) ApiResult.Ok(Unit, status)
            else ApiResult.Err(JellyfinFailure(classify(status), status, "logout", retryable = false))
        }

    suspend fun userViews(userId: String, token: String, limits: NetworkLimits = NetworkLimits.DEFAULT): ApiResult<ItemsPage> =
        json(Endpoint.USER_VIEWS, token, query = mapOf("userId" to userId), limits = limits) { status, parsed ->
            if (status in 200..299 && parsed != null) {
                ApiResult.Ok(parseItemsPage(parsed), status)
            } else {
                ApiResult.Err(JellyfinFailure(classify(status), status, "userViews", retryable = false))
            }
        }

    suspend fun items(
        userId: String,
        token: String,
        query: Map<String, String>,
        limits: NetworkLimits = NetworkLimits.DEFAULT,
    ): ApiResult<ItemsPage> {
        val params = LinkedHashMap(query)
        params["userId"] = userId
        return json(Endpoint.USERS_ITEMS.replace("{userId}", userId), token, query = params, limits = limits) { status, parsed ->
            if (status in 200..299 && parsed != null) {
                ApiResult.Ok(parseItemsPage(parsed), status)
            } else {
                ApiResult.Err(JellyfinFailure(classify(status), status, "items", retryable = false))
            }
        }
    }

    suspend fun resumeItems(
        userId: String,
        token: String,
        query: Map<String, String> = emptyMap(),
        limits: NetworkLimits = NetworkLimits.DEFAULT,
    ): ApiResult<ItemsPage> {
        val params = LinkedHashMap(query)
        params["userId"] = userId
        return json(
            Endpoint.USERS_ITEMS_RESUME.replace("{userId}", userId),
            token,
            query = params,
            limits = limits,
        ) { status, parsed ->
            if (status in 200..299 && parsed != null) {
                ApiResult.Ok(parseItemsPage(parsed), status)
            } else {
                ApiResult.Err(JellyfinFailure(classify(status), status, "resumeItems", retryable = false))
            }
        }
    }

    suspend fun nextUp(
        userId: String,
        token: String,
        query: Map<String, String> = emptyMap(),
        limits: NetworkLimits = NetworkLimits.DEFAULT,
    ): ApiResult<ItemsPage> {
        val params = LinkedHashMap(query)
        params["userId"] = userId
        return json(Endpoint.SHOWS_NEXT_UP, token, query = params, limits = limits) { status, parsed ->
            if (status in 200..299 && parsed != null) {
                ApiResult.Ok(parseItemsPage(parsed), status)
            } else {
                ApiResult.Err(JellyfinFailure(classify(status), status, "nextUp", retryable = false))
            }
        }
    }

    suspend fun latestItems(
        userId: String,
        token: String,
        query: Map<String, String> = emptyMap(),
        limits: NetworkLimits = NetworkLimits.DEFAULT,
    ): ApiResult<ItemsPage> {
        val params = LinkedHashMap(query)
        params["userId"] = userId
        return json(
            Endpoint.USERS_ITEMS_LATEST.replace("{userId}", userId),
            token,
            query = params,
            limits = limits,
            arrayBody = true,
        ) { status, parsed ->
            if (status in 200..299 && parsed != null) {
                ApiResult.Ok(parseItemsPage(parsed), status)
            } else {
                ApiResult.Err(JellyfinFailure(classify(status), status, "latestItems", retryable = false))
            }
        }
    }

    suspend fun item(userId: String, itemId: String, token: String, limits: NetworkLimits = NetworkLimits.DEFAULT): ApiResult<ItemSummary> =
        json(
            Endpoint.USER_ITEM.replace("{userId}", userId).replace("{itemId}", itemId),
            token,
            query = mapOf("fields" to "Chapters"),
            limits = limits,
        ) { status, parsed ->
            if (status in 200..299 && parsed != null) {
                ApiResult.Ok(parseItem(parsed), status)
            } else {
                ApiResult.Err(JellyfinFailure(classify(status), status, "item", retryable = false))
            }
        }

    suspend fun mediaSegments(itemId: String, token: String, limits: NetworkLimits = NetworkLimits.DEFAULT): ApiResult<List<MediaSegment>> =
        json(
            Endpoint.MEDIA_SEGMENTS.replace("{itemId}", itemId),
            token,
            limits = limits,
        ) { status, parsed ->
            if (status in 200..299 && parsed != null) {
                ApiResult.Ok(parseMediaSegments(parsed), status)
            } else {
                ApiResult.Err(JellyfinFailure(classify(status), status, "mediaSegments", retryable = false))
            }
        }

    suspend fun seasons(seriesId: String, userId: String, token: String, limits: NetworkLimits = NetworkLimits.DEFAULT): ApiResult<ItemsPage> =
        json(
            Endpoint.SHOWS_SEASONS.replace("{seriesId}", seriesId),
            token,

            query = mapOf("userId" to userId, "fields" to JELLYFIN_ITEM_FIELDS),
            limits = limits,
        ) { status, parsed ->
            if (status in 200..299 && parsed != null) {
                ApiResult.Ok(parseItemsPage(parsed), status)
            } else {
                ApiResult.Err(JellyfinFailure(classify(status), status, "seasons", retryable = false))
            }
        }

    suspend fun episodes(seriesId: String, userId: String, token: String, seasonId: String? = null, limits: NetworkLimits = NetworkLimits.DEFAULT): ApiResult<ItemsPage> =
        json(
            Endpoint.SHOWS_EPISODES.replace("{seriesId}", seriesId),
            token,
            query = buildMap {
                put("userId", userId)
                if (seasonId != null) put("seasonId", seasonId)

                put("fields", JELLYFIN_ITEM_FIELDS)
            },
            limits = limits,
        ) { status, parsed ->
            if (status in 200..299 && parsed != null) {
                ApiResult.Ok(parseItemsPage(parsed), status)
            } else {
                ApiResult.Err(JellyfinFailure(classify(status), status, "episodes", retryable = false))
            }
        }

    private suspend fun itemsPageRoute(
        path: String,
        userId: String,
        token: String,
        query: Map<String, String>,
        label: String,
        limits: NetworkLimits,
    ): ApiResult<ItemsPage> = json(path, token, query = query + mapOf("userId" to userId), limits = limits) { status, parsed ->
        if (status in 200..299 && parsed != null) {
            ApiResult.Ok(parseItemsPage(parsed), status)
        } else {
            ApiResult.Err(JellyfinFailure(classify(status), status, label, retryable = false))
        }
    }

    suspend fun suggestionsSeries(
        userId: String,
        token: String,
        parentId: String? = null,
        limit: Int = 60,
        limits: NetworkLimits = NetworkLimits.DEFAULT,
    ): ApiResult<ItemsPage> = itemsPageRoute(
        Endpoint.ITEMS_SUGGESTIONS,
        userId,
        token,
        buildMap {
            put("type", "Series")
            put("limit", limit.toString())

            put("fields", JELLYFIN_ITEM_FIELDS)
            if (parentId != null) put("parentId", parentId)
        },
        "suggestions",
        limits,
    )

    suspend fun suggestionsMovies(
        userId: String,
        token: String,
        parentId: String? = null,
        limit: Int = 60,
        limits: NetworkLimits = NetworkLimits.DEFAULT,
    ): ApiResult<ItemsPage> = itemsPageRoute(
        Endpoint.ITEMS_SUGGESTIONS,
        userId,
        token,
        buildMap {
            put("type", "Movie")
            put("limit", limit.toString())

            put("fields", JELLYFIN_ITEM_FIELDS)
            if (parentId != null) put("parentId", parentId)
        },
        "suggestions",
        limits,
    )

    suspend fun showsUpcoming(
        userId: String,
        token: String,
        parentId: String? = null,
        limit: Int = 60,
        limits: NetworkLimits = NetworkLimits.DEFAULT,
    ): ApiResult<ItemsPage> = itemsPageRoute(
        Endpoint.SHOWS_UPCOMING,
        userId,
        token,
        buildMap {
            put("limit", limit.toString())
            put("fields", JELLYFIN_UPCOMING_FIELDS)
            if (parentId != null) put("parentId", parentId)
        },
        "upcoming",
        limits,
    )

    suspend fun genres(
        userId: String,
        token: String,
        parentId: String? = null,
        limit: Int = 200,
        limits: NetworkLimits = NetworkLimits.DEFAULT,

        startIndex: Int = 0,
        includeItemTypes: String? = null,
        sortBy: List<String> = emptyList(),
        sortOrder: String? = null,
    ): ApiResult<ItemsPage> = itemsPageRoute(
        Endpoint.GENRES,
        userId,
        token,
        buildMap {
            put("limit", limit.toString())
            if (parentId != null) put("parentId", parentId)
            if (startIndex > 0) put("startIndex", startIndex.toString())
            if (!includeItemTypes.isNullOrBlank()) put("includeItemTypes", includeItemTypes)
            if (sortBy.isNotEmpty()) put("sortBy", sortBy.joinToString(","))
            if (sortOrder != null) put("sortOrder", sortOrder)
        },
        "genres",
        limits,
    )

    suspend fun filterValues(
        userId: String,
        token: String,
        parentId: String?,
        includeItemTypes: List<String>,
        limits: NetworkLimits = NetworkLimits.DEFAULT,
    ): ApiResult<FilterValueSources> = json(
        Endpoint.ITEMS_FILTERS,
        token,
        query = buildMap {
            put("userId", userId)
            if (parentId != null) put("parentId", parentId)
            if (includeItemTypes.isNotEmpty()) put("includeItemTypes", includeItemTypes.joinToString(","))
        },
        limits = limits,
    ) { status, parsed ->
        if (status in 200..299 && parsed != null) {
            ApiResult.Ok(parseFilterValues(parsed), status)
        } else {
            ApiResult.Err(JellyfinFailure(classify(status), status, "filterValues", retryable = false))
        }
    }

    suspend fun moviesRecommendations(
        userId: String,
        token: String,
        parentId: String?,
        categoryLimit: Int = 6,
        itemLimit: Int = 8,
        limits: NetworkLimits = NetworkLimits.DEFAULT,
    ): ApiResult<ItemsPage> = json(
        Endpoint.MOVIES_RECOMMENDATIONS,
        token,
        query = buildMap {
            put("userId", userId)
            if (parentId != null) put("parentId", parentId)
            put("categoryLimit", categoryLimit.toString())
            put("itemLimit", itemLimit.toString())
            put("fields", "PrimaryImageAspectRatio,MediaSourceCount")
            put("imageTypeLimit", "1")
            put("enableImageTypes", "Primary,Backdrop,Banner,Thumb")
        },
        limits = limits,
        arrayBody = true,
    ) { status, parsed ->
        if (status in 200..299 && parsed != null) {
            ApiResult.Ok(parseRecommendationGroups(parsed), status)
        } else {
            ApiResult.Err(JellyfinFailure(classify(status), status, "moviesRecommendations", retryable = false))
        }
    }

    suspend fun studios(
        userId: String,
        token: String,
        parentId: String? = null,
        includeItemTypes: String? = null,
        startIndex: Int = 0,
        limit: Int = 200,
        sortBy: String? = null,
        sortOrder: String? = null,
        fields: String? = null,
        limits: NetworkLimits = NetworkLimits.DEFAULT,
    ): ApiResult<ItemsPage> = itemsPageRoute(
        Endpoint.STUDIOS,
        userId,
        token,
        buildMap {
            put("limit", limit.toString())
            if (parentId != null) put("parentId", parentId)
            if (startIndex > 0) put("startIndex", startIndex.toString())
            if (!includeItemTypes.isNullOrBlank()) put("includeItemTypes", includeItemTypes)
            if (!sortBy.isNullOrBlank()) put("sortBy", sortBy)
            if (!sortOrder.isNullOrBlank()) put("sortOrder", sortOrder)
            if (!fields.isNullOrBlank()) put("fields", fields)
        },
        "studios",
        limits,
    )

    suspend fun artists(
        userId: String,
        token: String,
        parentId: String? = null,
        albumArtists: Boolean = false,
        startIndex: Int = 0,
        limit: Int = 200,
        sortBy: String? = null,
        sortOrder: String? = null,
        fields: String? = null,
        limits: NetworkLimits = NetworkLimits.DEFAULT,
    ): ApiResult<ItemsPage> = itemsPageRoute(
        if (albumArtists) Endpoint.ALBUM_ARTISTS else Endpoint.ARTISTS,
        userId,
        token,
        buildMap {
            put("limit", limit.toString())
            if (parentId != null) put("parentId", parentId)
            if (startIndex > 0) put("startIndex", startIndex.toString())
            if (!sortBy.isNullOrBlank()) put("sortBy", sortBy)
            if (!sortOrder.isNullOrBlank()) put("sortOrder", sortOrder)
            if (!fields.isNullOrBlank()) put("fields", fields)
        },
        if (albumArtists) "album-artists" else "artists",
        limits,
    )

    suspend fun playlistItems(
        userId: String,
        token: String,
        playlistId: String,
        startIndex: Int = 0,
        limit: Int = 60,
        fields: String? = null,
        limits: NetworkLimits = NetworkLimits.DEFAULT,
    ): ApiResult<ItemsPage> = itemsPageRoute(
        Endpoint.PLAYLIST_ITEMS.replace("{playlistId}", playlistId),
        userId,
        token,
        buildMap {
            put("limit", limit.toString())
            if (startIndex > 0) put("startIndex", startIndex.toString())
            if (!fields.isNullOrBlank()) put("fields", fields)
        },
        "playlist-items",
        limits,
    )

    suspend fun playbackInfo(
        itemId: String,
        userId: String,
        token: String,
        body: JSONObject,
        limits: NetworkLimits = NetworkLimits.DEFAULT,
    ): ApiResult<PlaybackInfoResponse> =
        json(
            Endpoint.PLAYBACK_INFO.replace("{itemId}", itemId),
            token,
            method = "POST",
            query = mapOf("userId" to userId),
            body = body,
            limits = limits,
        ) { status, parsed ->
            if (status in 200..299 && parsed != null) {
                val response = parsePlaybackInfoResponse(parsed)

                if (response.errorCode != null) {
                    ApiResult.Err(
                        JellyfinFailure(
                            FailureKind.NEGOTIATION,
                            status,
                            "playback negotiation failed: ${response.errorCode}",
                            retryable = false,
                        ),
                    )
                } else {
                    ApiResult.Ok(response, status)
                }
            } else {
                ApiResult.Err(JellyfinFailure(classify(status), status, "playbackInfo", retryable = false))
            }
        }

    suspend fun lyrics(
        itemId: String,
        token: String,
        limits: NetworkLimits = NetworkLimits.DEFAULT,
    ): ApiResult<List<LyricLine>> =
        json(
            Endpoint.LYRICS.replace("{itemId}", itemId),
            token,
            limits = limits,
        ) { status, parsed ->
            when {
                status == 404 -> ApiResult.Ok(emptyList(), status)
                status in 200..299 && parsed != null -> ApiResult.Ok(parseLyrics(parsed), status)
                else -> ApiResult.Err(JellyfinFailure(classify(status), status, "lyrics", retryable = false))
            }
        }

    suspend fun sessionEvent(path: String, token: String, body: JSONObject, limits: NetworkLimits = NetworkLimits.DEFAULT): ApiResult<Unit> =
        json(path, token, method = "POST", body = body, limits = limits, retryable = true) { status, _ ->
            if (status in 200..299 || status == 204) ApiResult.Ok(Unit, status)
            else ApiResult.Err(JellyfinFailure(classify(status), status, "sessionEvent($path)", retryable = false))
        }

    suspend fun updateUserItemData(
        userId: String,
        itemId: String,
        token: String,
        positionMs: Long? = null,
        played: Boolean? = null,
        limits: NetworkLimits = NetworkLimits.DEFAULT,
    ): ApiResult<Unit> {
        val body = JSONObject()
        if (positionMs != null) body.put("PlaybackPositionTicks", positionMs * USER_DATA_TICKS_PER_MS)
        if (played != null) body.put("Played", played)
        return json(
            Endpoint.USER_ITEM_USER_DATA.replace("{itemId}", itemId),
            token,
            method = "POST",
            query = mapOf("userId" to userId),
            body = body,
            limits = limits,
            retryable = true,
        ) { status, _ ->
            if (status in 200..299 || status == 204) ApiResult.Ok(Unit, status)
            else ApiResult.Err(JellyfinFailure(classify(status), status, "updateUserItemData", retryable = false))
        }
    }

    suspend fun ping(playSessionId: String, token: String, limits: NetworkLimits = NetworkLimits.DEFAULT): ApiResult<Unit> =
        json(
            Endpoint.SESSIONS_PING,
            token,
            method = "POST",
            query = mapOf("playSessionId" to playSessionId),
            limits = limits,
            retryable = true,
        ) { status, _ ->
            if (status in 200..299 || status == 204) ApiResult.Ok(Unit, status)
            else ApiResult.Err(JellyfinFailure(classify(status), status, "ping", retryable = false))
        }

    suspend fun image(
        itemId: String,
        type: String,
        token: String,
        rendition: dev.infusion.core.model.ArtworkRendition = dev.infusion.core.model.ArtworkRendition.Unbounded,
        limits: NetworkLimits = NetworkLimits.DEFAULT,
    ): ApiResult<ImageFetch> = fetchImage(
        path = Endpoint.ITEM_IMAGE.replace("{itemId}", itemId).replace("{type}", type),
        query = renditionQuery(rendition),
        token = token,
        limits = limits,
        label = "image",
    )

    private fun renditionQuery(
        rendition: dev.infusion.core.model.ArtworkRendition,
    ): Map<String, String> {
        val side = rendition.maxSidePx ?: return emptyMap()
        return mapOf("maxWidth" to side.toString(), "maxHeight" to side.toString())
    }

    suspend fun userPrimaryImage(
        userId: String,
        tag: String?,
        token: String,
        limits: NetworkLimits = NetworkLimits.DEFAULT,
    ): ApiResult<ImageFetch> = fetchImage(
        path = Endpoint.USER_IMAGE,
        query = buildMap {
            put("userId", userId)
            if (!tag.isNullOrBlank()) put("tag", tag)
        },
        token = token,
        limits = limits,
        label = "userPrimaryImage",
    )

    private suspend fun fetchImage(
        path: String,
        query: Map<String, String>,
        token: String,
        limits: NetworkLimits,
        label: String,
    ): ApiResult<ImageFetch> = withContext(Dispatchers.IO) {
        val url = buildUrl(path, query)
        val response = try {
            transport.execute(
                HardenedRequest(
                    url = url,
                    headers = authHeaders(token),
                    limits = limits,
                    sensitiveHeaders = JELLYFIN_SENSITIVE_HEADERS,
                ),
            )
        } catch (e: NetworkPolicyException) {
            return@withContext ApiResult.Err(
                JellyfinFailure(
                    kind = if (e.reason == DiagnosticReason.TIMEOUT) FailureKind.TIMEOUT else FailureKind.NETWORK,
                    status = null,
                    message = "$label fetch failed: ${e.reason.name}",
                    retryable = true,
                ),
            )
        } catch (e: IOException) {
            return@withContext ApiResult.Err(
                JellyfinFailure(FailureKind.NETWORK, null, "$label io: ${e::class.simpleName}", retryable = true),
            )
        }
        response.use { resp ->
            val status = resp.statusCode
            if (status !in 200..299) {
                return@withContext ApiResult.Err(JellyfinFailure(classify(status), status, label, retryable = false))
            }
            val read = readBounded(resp.body, limits.maxResponseBytes)
            val bytes = when (read) {
                is BoundedRead.Ok -> read.bytes
                is BoundedRead.Overflow -> return@withContext ApiResult.Err(
                    JellyfinFailure(FailureKind.LIMIT_EXCEEDED, status, "$label exceeds limit", retryable = false),
                )
                is BoundedRead.Io -> return@withContext ApiResult.Err(
                    JellyfinFailure(FailureKind.NETWORK, status, "$label read failed", retryable = true),
                )
            }
            return@withContext ApiResult.Ok(
                ImageFetch(
                    status = status,
                    contentType = resp.headers["content-type"],
                    lastModified = resp.headers["last-modified"],
                    cacheControl = resp.headers["cache-control"],
                    bytes = bytes,
                ),
                status,
            )
        }
    }

    suspend fun systemInfo(token: String, limits: NetworkLimits = NetworkLimits.DEFAULT): ApiResult<ServerPublicInfo> =
        json(Endpoint.SYSTEM_INFO, token, limits = limits) { status, parsed ->
            if (status in 200..299 && parsed != null) {
                ApiResult.Ok(parseServerPublicInfo(parsed), status)
            } else {
                ApiResult.Err(JellyfinFailure(classify(status), status, "systemInfo", retryable = false))
            }
        }

    suspend fun users(token: String, limits: NetworkLimits = NetworkLimits.DEFAULT): ApiResult<List<UserInfo>> =
        json(Endpoint.USERS, token, limits = limits, arrayBody = true) { status, parsed ->
            if (status in 200..299 && parsed != null) {
                val arr = parsed.optJSONArray("Items") ?: JSONArray()
                val out = mutableListOf<UserInfo>()
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { out.add(parseUserInfo(it)) }
                }
                ApiResult.Ok(out, status)
            } else {
                ApiResult.Err(JellyfinFailure(classify(status), status, "users", retryable = false))
            }
        }

    suspend fun user(userId: String, token: String, limits: NetworkLimits = NetworkLimits.DEFAULT): ApiResult<UserInfo> =
        json(Endpoint.USER.replace("{userId}", userId), token, limits = limits) { status, parsed ->
            if (status in 200..299 && parsed != null) {
                ApiResult.Ok(parseUserInfo(parsed), status)
            } else {
                ApiResult.Err(JellyfinFailure(classify(status), status, "user", retryable = false))
            }
        }

    suspend fun quickConnectEnabled(limits: NetworkLimits = NetworkLimits.DEFAULT): ApiResult<Boolean> =
        json(
            Endpoint.QUICK_CONNECT_ENABLED,
            token = null,
            limits = limits,
            retryable = false,
            scalarBody = true,
        ) { status, parsed ->
            if (status in 200..299) {
                ApiResult.Ok(parsed?.optString("Scalar")?.trim()?.toBooleanStrictOrNull() == true, status)
            } else {
                ApiResult.Err(JellyfinFailure(classify(status), status, "quickConnectEnabled", retryable = false))
            }
        }

    suspend fun quickConnectInitiate(limits: NetworkLimits = NetworkLimits.DEFAULT): ApiResult<QuickConnectResult> =
        json(
            Endpoint.QUICK_CONNECT_INITIATE,
            token = null,
            method = "POST",
            body = JSONObject(),
            limits = limits,
            retryable = false,
        ) { status, parsed ->
            if (status in 200..299 && parsed != null) {
                ApiResult.Ok(parseQuickConnectResult(parsed), status)
            } else {
                ApiResult.Err(JellyfinFailure(classify(status), status, "quickConnectInitiate", retryable = false))
            }
        }

    suspend fun quickConnectConnect(secret: String, limits: NetworkLimits = NetworkLimits.DEFAULT): ApiResult<QuickConnectResult> =
        json(
            Endpoint.QUICK_CONNECT_CONNECT,
            token = null,
            query = mapOf("secret" to secret),
            limits = limits,
            retryable = false,
        ) { status, parsed ->
            if (status in 200..299 && parsed != null) {
                ApiResult.Ok(parseQuickConnectResult(parsed), status)
            } else {
                ApiResult.Err(JellyfinFailure(classify(status), status, "quickConnectConnect", retryable = false))
            }
        }

    suspend fun authenticateWithQuickConnect(
        secret: String,
        limits: NetworkLimits = NetworkLimits.DEFAULT,
    ): ApiResult<AuthResponse> {
        val body = JSONObject().put("Secret", secret)
        return json(
            Endpoint.AUTHENTICATE_QUICK_CONNECT,
            token = null,
            method = "POST",
            body = body,
            limits = limits,
            retryable = false,
        ) { status, parsed ->
            if (status in 200..299 && parsed != null) {
                ApiResult.Ok(parseAuthResponse(parsed), status)
            } else {
                ApiResult.Err(JellyfinFailure(classify(status), status, "authenticateWithQuickConnect", retryable = false))
            }
        }
    }

    suspend fun itemImages(itemId: String, token: String, limits: NetworkLimits = NetworkLimits.DEFAULT): ApiResult<List<ItemImageInfo>> =
        withContext(Dispatchers.IO) {
            val url = buildUrl(Endpoint.ITEM_IMAGES.replace("{itemId}", itemId), emptyMap())
            val response = try {
                transport.execute(
                    HardenedRequest(
                        url = url,
                        headers = authHeaders(token),
                        limits = limits,
                        sensitiveHeaders = JELLYFIN_SENSITIVE_HEADERS,
                    ),
                )
            } catch (e: NetworkPolicyException) {
                return@withContext ApiResult.Err(
                    JellyfinFailure(
                        kind = if (e.reason == DiagnosticReason.TIMEOUT) FailureKind.TIMEOUT else FailureKind.NETWORK,
                        status = null,
                        message = "itemImages failed: ${e.reason.name}",
                        retryable = true,
                    ),
                )
            } catch (e: IOException) {
                return@withContext ApiResult.Err(
                    JellyfinFailure(FailureKind.NETWORK, null, "itemImages io: ${e::class.simpleName}", retryable = true),
                )
            }
            response.use { resp ->
                val status = resp.statusCode
                if (status !in 200..299) {
                    return@withContext ApiResult.Err(JellyfinFailure(classify(status), status, "itemImages", retryable = false))
                }
                val read = readBounded(resp.body, limits.maxResponseBytes)
                val bytes = when (read) {
                    is BoundedRead.Ok -> read.bytes
                    is BoundedRead.Overflow -> return@withContext ApiResult.Err(
                        JellyfinFailure(FailureKind.LIMIT_EXCEEDED, status, "itemImages exceeds limit", retryable = false),
                    )
                    is BoundedRead.Io -> return@withContext ApiResult.Err(
                        JellyfinFailure(FailureKind.NETWORK, status, "itemImages read failed", retryable = true),
                    )
                }
                val arr = try {
                    if (bytes.isEmpty()) null else org.json.JSONArray(String(bytes, StandardCharsets.UTF_8))
                } catch (_: org.json.JSONException) {
                    null
                }
                if (arr == null) {
                    return@withContext ApiResult.Err(JellyfinFailure(FailureKind.UNKNOWN, status, "itemImages: malformed body", retryable = false))
                }
                return@withContext ApiResult.Ok(parseItemImageList(arr), status)
            }
        }

    override fun close() {
        transport.close()
    }

    companion object {
        private val RETRYABLE_REASONS = setOf(
            DiagnosticReason.TIMEOUT,
            DiagnosticReason.DNS_FAILED,
            DiagnosticReason.CONNECTION_FAILED,

        )

        val JELLYFIN_SENSITIVE_HEADERS: Set<String> = setOf(
            MediaBrowserAuthorization.HEADER.lowercase(),
            "x-emby-token",
            "x-emby-authorization",
        )

        private fun classify(status: Int): FailureKind = when (status) {
            401 -> FailureKind.UNAUTHORIZED
            403 -> FailureKind.FORBIDDEN
            404 -> FailureKind.NOT_FOUND
            429 -> FailureKind.RATE_LIMITED
            in 400..499 -> FailureKind.BAD_REQUEST
            in 500..599 -> FailureKind.SERVER_ERROR
            else -> FailureKind.UNKNOWN
        }
    }
}

data class ImageFetch(
    val status: Int,
    val contentType: String?,
    val lastModified: String?,
    val cacheControl: String?,
    val bytes: ByteArray,
)

class JellyfinClientException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

private fun Throwable.causeChain(): String {
    val names = mutableListOf<String>()
    var t: Throwable? = this
    while (t != null && names.size < 4) {
        names += t::class.simpleName ?: "?"
        t = t.cause
    }
    return names.joinToString("<-")
}

sealed interface BoundedRead {

    data class Ok(val bytes: ByteArray) : BoundedRead

    data object Overflow : BoundedRead

    data class Io(val cause: IOException) : BoundedRead
}
