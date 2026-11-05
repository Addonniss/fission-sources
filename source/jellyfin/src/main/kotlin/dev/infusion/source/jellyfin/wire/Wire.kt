package dev.infusion.source.jellyfin.wire

import org.json.JSONArray
import org.json.JSONObject

data class ServerPublicInfo(
    val id: String?,
    val version: String?,
    val serverName: String?,
    val startupWizardCompleted: Boolean?,
)

data class AuthResponse(
    val accessToken: String?,
    val serverId: String?,
    val user: UserInfo?,
)

data class UserInfo(
    val id: String?,
    val name: String?,

    val primaryImageTag: String? = null,

    val disabled: Boolean? = null,

    val hidden: Boolean? = null,
)

data class QuickConnectResult(
    val secret: String,
    val code: String,
    val authenticated: Boolean,
)

data class ItemSummary(
    val id: String?,
    val name: String?,
    val type: String?,
    val collectionType: String?,
    val parentId: String?,
    val seriesId: String?,
    val seasonId: String?,
    val indexNumber: Int?,
    val parentIndexNumber: Int?,
    val runTimeTicks: Long?,
    val isFolder: Boolean?,
    val childCount: Int?,
    val userData: UserData?,
    val overview: String?,
    val productionYear: Int?,

    val premiereDate: String? = null,
    val imageTags: Map<String, String> = emptyMap(),

    val seriesName: String? = null,

    val album: String? = null,

    val albumArtist: String? = null,

    val artists: List<String> = emptyList(),

    val albumArtistId: String? = null,

    val parentItemId: String? = null,

    val imageTypes: Set<String> = emptySet(),

    val playlistItemId: String? = null,

    val unavailable: Boolean = false,

    val providerIds: Map<String, String> = emptyMap(),

    val communityRating: Double? = null,

    val criticRating: Double? = null,

    val chapters: List<ChapterInfo> = emptyList(),
)

data class ChapterInfo(
    val startPositionTicks: Long? = null,
    val name: String? = null,
)

data class MediaSegment(
    val id: String? = null,
    val itemId: String? = null,
    val type: String? = null,
    val startTicks: Long? = null,
    val endTicks: Long? = null,
)

data class ItemsPage(
    val items: List<ItemSummary>,
    val totalRecordCount: Int?,
    val startIndex: Int?,
)

data class UserData(
    val played: Boolean?,
    val playCount: Int?,
    val playbackPositionTicks: Long?,
    val playedPercentage: Double?,
    val lastPlayedDate: String?,
    val key: String?,
)

data class MediaStream(
    val type: String?,
    val index: Int?,
    val codec: String?,
    val language: String?,
    val displayTitle: String?,
    val deliveryMethod: String?,
    val deliveryUrl: String?,
    val isExternalUrl: Boolean?,

    val isExternal: Boolean?,
    val isDefault: Boolean?,
    val isForced: Boolean?,
    val width: Int?,
    val height: Int?,

    val realFrameRate: Double? = null,

    val averageFrameRate: Double? = null,
    val channelLayout: String?,
    val channels: Int?,
)

data class MediaSource(
    val id: String?,
    val container: String?,
    val protocol: String?,
    val supportsDirectPlay: Boolean?,
    val supportsDirectStream: Boolean?,
    val supportsTranscoding: Boolean?,
    val transcodingUrl: String?,
    val directStreamUrl: String?,
    val bitrate: Long?,
    val runTimeTicks: Long?,
    val mediaStreams: List<MediaStream>,
    val name: String?,
    val startPositionTicks: Long?,
    val liveStreamId: String? = null,
    val eTag: String? = null,
    val isRemote: Boolean = false,
    val path: String? = null,
)

data class PlaybackInfoResponse(
    val mediaSources: List<MediaSource>,
    val playSessionId: String?,
    val errorCode: String?,
)

data class ItemImageInfo(
    val type: String?,
    val tag: String?,
)

internal object Json {
    fun obj(o: JSONObject, key: String): JSONObject? =
        if (o.isNull(key)) null else o.optJSONObject(key)

    fun arr(o: JSONObject, key: String): JSONArray =
        if (o.isNull(key)) JSONArray() else o.optJSONArray(key) ?: JSONArray()

    fun str(o: JSONObject, key: String): String? =
        if (o.isNull(key)) null else o.optString(key).takeIf { it.isNotEmpty() }

    fun long(o: JSONObject, key: String): Long? =
        if (o.isNull(key)) null else o.optLong(key).let { if (it == 0L && !o.has(key)) null else it }

    fun bool(o: JSONObject, key: String): Boolean? =
        if (o.isNull(key)) null else o.optBoolean(key)

    fun int(o: JSONObject, key: String): Int? =
        if (o.isNull(key)) null else o.optInt(key, -1).let { if (it < 0) null else it }

    fun double(o: JSONObject, key: String): Double? =
        if (o.isNull(key)) null else o.optDouble(key, -1.0).let { if (it < 0) null else it }
}

fun parseServerPublicInfo(o: JSONObject): ServerPublicInfo = ServerPublicInfo(
    id = Json.str(o, "Id"),
    version = Json.str(o, "Version"),
    serverName = Json.str(o, "ServerName"),
    startupWizardCompleted = if (o.isNull("StartupWizardCompleted")) null else o.optBoolean("StartupWizardCompleted"),
)

fun parseAuthResponse(o: JSONObject): AuthResponse {
    val user = Json.obj(o, "User")?.let { parseUserInfo(it) }
    return AuthResponse(
        accessToken = Json.str(o, "AccessToken"),
        serverId = Json.str(o, "ServerId"),
        user = user,
    )
}

fun parseUserInfo(o: JSONObject): UserInfo = UserInfo(
    id = Json.str(o, "Id"),
    name = Json.str(o, "Name"),
    primaryImageTag = Json.str(o, "PrimaryImageTag"),

    disabled = Json.bool(o, "Disabled")
        ?: Json.obj(o, "Policy")?.let { Json.bool(it, "IsDisabled") },
    hidden = Json.bool(o, "Hidden"),
)

fun parseQuickConnectResult(o: JSONObject): QuickConnectResult = QuickConnectResult(
    secret = Json.str(o, "Secret").orEmpty(),
    code = Json.str(o, "Code").orEmpty(),
    authenticated = Json.bool(o, "Authenticated") == true,
)

fun parseItem(o: JSONObject): ItemSummary {
    val imageTags = mutableMapOf<String, String>()
    Json.obj(o, "ImageTags")?.let { tags ->
        val keys = tags.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (!tags.isNull(k)) imageTags[k] = tags.optString(k)
        }
    }
    return ItemSummary(
        id = Json.str(o, "Id"),
        name = Json.str(o, "Name"),
        type = Json.str(o, "Type"),
        collectionType = Json.str(o, "CollectionType"),
        parentId = Json.str(o, "ParentId"),
        seriesId = Json.str(o, "SeriesId"),
        seasonId = Json.str(o, "SeasonId"),
        indexNumber = Json.int(o, "IndexNumber"),
        parentIndexNumber = Json.int(o, "ParentIndexNumber"),
        runTimeTicks = Json.long(o, "RunTimeTicks"),
        isFolder = Json.bool(o, "IsFolder"),
        childCount = Json.int(o, "ChildCount"),
        userData = Json.obj(o, "UserData")?.let { parseUserData(it) },
        overview = Json.str(o, "Overview"),
        productionYear = Json.int(o, "ProductionYear"),
        premiereDate = Json.str(o, "PremiereDate"),
        imageTags = imageTags,
        seriesName = Json.str(o, "SeriesName"),

        album = Json.str(o, "Album"),
        albumArtist = Json.str(o, "AlbumArtist")
            ?: Json.arr(o, "AlbumArtists").let { arr -> arr.optJSONObject(0)?.let { Json.str(it, "Name") } },
        artists = Json.arr(o, "Artists").let { arr ->
            buildList { for (i in 0 until arr.length()) arr.opt(i)?.toString()?.takeIf { it.isNotBlank() }?.let(::add) }
        },

        albumArtistId = Json.arr(o, "AlbumArtists").optJSONObject(0)?.let { Json.str(it, "Id") },
        parentItemId = Json.str(o, "ParentId"),
        imageTypes = buildSet {
            addAll(imageTags.keys)

            if (Json.arr(o, "BackdropImageTags").length() > 0) add("Backdrop")
        },
        playlistItemId = Json.str(o, "PlaylistItemId"),
        unavailable = Json.bool(o, "IsMissing") == true ||
            Json.bool(o, "IsUnaired") == true ||
            Json.str(o, "LocationType")?.equals("Virtual", ignoreCase = true) == true,
        providerIds = Json.obj(o, "ProviderIds")?.let { providerIds ->
            buildMap {
                providerIds.keys().forEach { key ->

                    val raw = providerIds.opt(key)
                    val value = when (raw) {
                        is String -> raw.trim()
                        is Number -> raw.toString()
                        else -> ""
                    }
                    if (value.isNotEmpty()) put(key, value)
                }
            }
        }.orEmpty(),

        communityRating = Json.double(o, "CommunityRating"),
        criticRating = Json.double(o, "CriticRating"),

        chapters = Json.arr(o, "Chapters").let { arr ->
            buildList {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { c ->
                        add(
                            ChapterInfo(
                                startPositionTicks = Json.long(c, "StartPositionTicks"),
                                name = Json.str(c, "Name"),
                            ),
                        )
                    }
                }
            }
        },
    )
}

fun parseMediaSegments(o: JSONObject): List<MediaSegment> {
    val arr = Json.arr(o, "Items")
    val out = mutableListOf<MediaSegment>()
    for (i in 0 until arr.length()) {
        val entry = arr.optJSONObject(i) ?: continue
        out.add(
            MediaSegment(
                id = Json.str(entry, "Id"),
                itemId = Json.str(entry, "ItemId"),
                type = Json.str(entry, "Type"),
                startTicks = Json.long(entry, "StartTicks"),
                endTicks = Json.long(entry, "EndTicks"),
            ),
        )
    }
    return out
}

fun parseUserData(o: JSONObject): UserData = UserData(
    played = Json.bool(o, "Played"),
    playCount = Json.int(o, "PlayCount"),
    playbackPositionTicks = Json.long(o, "PlaybackPositionTicks"),
    playedPercentage = Json.double(o, "PlayedPercentage"),
    lastPlayedDate = Json.str(o, "LastPlayedDate"),
    key = Json.str(o, "Key"),
)

fun parseItemsPage(o: JSONObject): ItemsPage {
    val items = mutableListOf<ItemSummary>()
    val arr = Json.arr(o, "Items")
    for (i in 0 until arr.length()) {
        arr.optJSONObject(i)?.let { items.add(parseItem(it)) }
    }
    return ItemsPage(
        items = items,
        totalRecordCount = Json.int(o, "TotalRecordCount"),
        startIndex = Json.int(o, "StartIndex"),
    )
}

fun parseItemsArray(arr: JSONArray): ItemsPage {
    val items = mutableListOf<ItemSummary>()
    for (i in 0 until arr.length()) {
        arr.optJSONObject(i)?.let { items.add(parseItem(it)) }
    }
    return ItemsPage(
        items = items,
        totalRecordCount = items.size,
        startIndex = null,
    )
}

data class FilterValueSources(
    val genres: List<String> = emptyList(),
    val officialRatings: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val years: List<Int> = emptyList(),
)

fun parseFilterValues(o: JSONObject): FilterValueSources = FilterValueSources(
    genres = Json.arr(o, "Genres").toStringList(),
    officialRatings = Json.arr(o, "OfficialRatings").toStringList(),
    tags = Json.arr(o, "Tags").toStringList(),
    years = Json.arr(o, "Years").toStringList().mapNotNull { it.toIntOrNull() }.sorted(),
)

private fun JSONArray.toStringList(): List<String> =
    (0 until length()).mapNotNull { index -> optString(index).takeIf { it.isNotEmpty() } }

fun parseRecommendationGroups(wrapper: JSONObject): ItemsPage {
    val groups = Json.arr(wrapper, "Items")
    val items = mutableListOf<ItemSummary>()
    for (i in 0 until groups.length()) {
        val group = groups.optJSONObject(i) ?: continue

        val nested = group.optJSONArray("Items")
        if (nested == null) {
            items.add(parseItem(group))
        } else {
            for (j in 0 until nested.length()) {
                nested.optJSONObject(j)?.let { items.add(parseItem(it)) }
            }
        }
    }
    return ItemsPage(items = items, totalRecordCount = items.size, startIndex = null)
}

fun parseMediaStream(o: JSONObject): MediaStream = MediaStream(
    type = Json.str(o, "Type"),
    index = Json.int(o, "Index"),
    codec = Json.str(o, "Codec"),
    language = Json.str(o, "Language"),
    displayTitle = Json.str(o, "DisplayTitle"),
    deliveryMethod = Json.str(o, "DeliveryMethod"),
    deliveryUrl = Json.str(o, "DeliveryUrl"),
    isExternalUrl = Json.bool(o, "IsExternalUrl"),
    isExternal = Json.bool(o, "IsExternal"),
    isDefault = Json.bool(o, "IsDefault"),
    isForced = Json.bool(o, "IsForced"),
    width = Json.int(o, "Width"),
    height = Json.int(o, "Height"),

    realFrameRate = Json.double(o, "RealFrameRate"),
    averageFrameRate = Json.double(o, "AverageFrameRate"),
    channelLayout = Json.str(o, "ChannelLayout"),
    channels = Json.int(o, "Channels"),
)

fun parseMediaSource(o: JSONObject): MediaSource {
    val streams = mutableListOf<MediaStream>()
    val arr = Json.arr(o, "MediaStreams")
    for (i in 0 until arr.length()) {
        arr.optJSONObject(i)?.let { streams.add(parseMediaStream(it)) }
    }
    return MediaSource(
        id = Json.str(o, "Id"),
        container = Json.str(o, "Container"),
        protocol = Json.str(o, "Protocol"),
        supportsDirectPlay = Json.bool(o, "SupportsDirectPlay"),
        supportsDirectStream = Json.bool(o, "SupportsDirectStream"),
        supportsTranscoding = Json.bool(o, "SupportsTranscoding"),
        transcodingUrl = Json.str(o, "TranscodingUrl"),
        directStreamUrl = Json.str(o, "DirectStreamUrl"),
        bitrate = Json.long(o, "Bitrate"),
        runTimeTicks = Json.long(o, "RunTimeTicks"),
        mediaStreams = streams,
        name = Json.str(o, "Name"),
        startPositionTicks = Json.long(o, "StartPositionTicks"),
        liveStreamId = Json.str(o, "LiveStreamId"),
        eTag = Json.str(o, "ETag"),
        isRemote = Json.bool(o, "IsRemote") == true,
        path = Json.str(o, "Path"),
    )
}

fun parsePlaybackInfoResponse(o: JSONObject): PlaybackInfoResponse {
    val sources = mutableListOf<MediaSource>()
    val arr = Json.arr(o, "MediaSources")
    for (i in 0 until arr.length()) {
        arr.optJSONObject(i)?.let { sources.add(parseMediaSource(it)) }
    }
    return PlaybackInfoResponse(
        mediaSources = sources,
        playSessionId = Json.str(o, "PlaySessionId"),
        errorCode = Json.str(o, "ErrorCode"),
    )
}

fun parseItemImageList(o: JSONArray): List<ItemImageInfo> {
    val out = mutableListOf<ItemImageInfo>()
    for (i in 0 until o.length()) {
        o.optJSONObject(i)?.let { entry ->
            out.add(
                ItemImageInfo(
                    type = Json.str(entry, "Type"),
                    tag = Json.str(entry, "Tag"),
                ),
            )
        }
    }
    return out
}

data class LyricLine(
    val text: String,
    val startTicks: Long?,
)

fun parseLyrics(o: JSONObject): List<LyricLine> {
    val arr = Json.arr(o, "Lyrics")
    val out = mutableListOf<LyricLine>()
    for (i in 0 until arr.length()) {
        val entry = arr.optJSONObject(i) ?: continue
        val text = Json.str(entry, "Text") ?: continue
        out.add(LyricLine(text = text, startTicks = Json.long(entry, "Start")))
    }
    return out
}
