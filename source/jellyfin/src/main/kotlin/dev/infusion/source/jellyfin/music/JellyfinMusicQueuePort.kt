package dev.infusion.source.jellyfin.music

import dev.infusion.core.domain.music.MusicLyricLine
import dev.infusion.core.domain.music.MusicLyrics
import dev.infusion.core.domain.music.MusicLyricsResult
import dev.infusion.core.domain.music.MusicOccurrencePage
import dev.infusion.core.domain.music.MusicPageFailure
import dev.infusion.core.domain.music.MusicPageResult
import dev.infusion.core.domain.music.MusicQueueContext
import dev.infusion.core.domain.music.MusicQueueEntry
import dev.infusion.core.domain.music.MusicQueuePort
import dev.infusion.core.domain.music.MusicReportingIdentity
import dev.infusion.core.domain.music.MusicTransport
import dev.infusion.core.domain.music.MusicTransportFailure
import dev.infusion.core.domain.music.MusicTransportResult
import dev.infusion.core.domain.music.QueueEntryId
import dev.infusion.core.model.ExpectedMode
import dev.infusion.core.model.SourceId
import dev.infusion.core.network.NetworkLimits
import dev.infusion.source.jellyfin.auth.JellyfinAuthenticator
import dev.infusion.source.jellyfin.auth.JellyfinSession
import dev.infusion.source.jellyfin.catalog.JellyfinCatalogPort
import dev.infusion.source.jellyfin.catalog.JellyfinRowItem
import dev.infusion.source.jellyfin.client.ApiResult
import dev.infusion.source.jellyfin.client.FailureKind
import dev.infusion.source.jellyfin.playback.JellyfinPlaybackInfoBody
import dev.infusion.source.jellyfin.playback.JellyfinTransportCodec
import dev.infusion.source.jellyfin.playback.JellyfinTransportResolver
import dev.infusion.source.jellyfin.wire.MediaSource

class JellyfinMusicQueuePort(
    private val sourceId: SourceId,
    private val catalog: JellyfinCatalogPort,
    private val client: dev.infusion.source.jellyfin.client.JellyfinClient,
    private val authenticator: JellyfinAuthenticator,
    private val transportResolver: JellyfinTransportResolver,
    private val sessionProvider: () -> JellyfinSession?,
    private val limits: NetworkLimits = NetworkLimits.DEFAULT,
) : MusicQueuePort {

    companion object {

        private const val AUDIO_FIELDS =
            "ParentId,Album,AlbumArtist,AlbumArtists,Artists,RunTimeTicks,SortName,IndexNumber,ParentIndexNumber"

        private val ALBUM_ORDER = listOf("ParentIndexNumber", "IndexNumber", "SortName")
    }

    override suspend fun page(context: MusicQueueContext, offset: Int, limit: Int): MusicPageResult =

        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { readPage(context, offset, limit) }

    private suspend fun readPage(context: MusicQueueContext, offset: Int, limit: Int): MusicPageResult {
        val result = when (context) {
            is MusicQueueContext.Album -> catalog.libraryItemPage(
                parentId = context.albumNativeId,
                recursive = false,
                includeItemTypes = listOf("Audio"),
                startIndex = offset,
                limit = limit,
                sortBy = ALBUM_ORDER,
                sortOrder = "Ascending",
                fields = AUDIO_FIELDS,

                enableTotalRecordCount = offset == 0,
            )

            is MusicQueueContext.Playlist -> catalog.libraryPlaylistItems(
                playlistId = context.playlistNativeId,
                startIndex = offset,
                limit = limit,
            )

            is MusicQueueContext.Songs -> catalog.libraryItemPage(
                parentId = context.libraryNativeId,
                recursive = true,
                includeItemTypes = listOf("Audio"),
                startIndex = offset,
                limit = limit,
                sortBy = context.sortBy,
                sortOrder = context.sortOrder,
                fields = AUDIO_FIELDS,
                parameters = context.filters,
                enableTotalRecordCount = offset == 0,
            )
        }
        return when (result) {
            is ApiResult.Ok -> {
                val page = result.value
                MusicPageResult.Ok(
                    MusicOccurrencePage(
                        offset = offset,
                        entries = page.items.mapIndexed { index, row ->
                            row.toQueueEntry(context, offset + index)
                        },
                        total = page.totalRecordCount,
                    ),
                )
            }
            is ApiResult.Err -> MusicPageResult.Failed(
                message = pageFailureMessage(result.failure.kind),
                kind = when (result.failure.kind) {
                    FailureKind.UNAUTHORIZED, FailureKind.FORBIDDEN -> MusicPageFailure.DISCONNECTED
                    FailureKind.NOT_FOUND -> MusicPageFailure.SOURCE_GONE
                    else -> MusicPageFailure.TRANSPORT
                },
            )
        }
    }

    override suspend fun transportFor(entry: MusicQueueEntry, requestId: String): MusicTransportResult =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { resolveTransport(entry, requestId) }

    private suspend fun resolveTransport(entry: MusicQueueEntry, requestId: String): MusicTransportResult {
        val session = sessionProvider()
            ?: return MusicTransportResult.Failed("Jellyfin is not connected.", MusicTransportFailure.DISCONNECTED)
        val token = authenticator.token(session)
            ?: return MusicTransportResult.Failed("Jellyfin is not connected.", MusicTransportFailure.DISCONNECTED)

        val response = when (
            val result = client.playbackInfo(
                itemId = entry.trackNativeId,
                userId = session.userId,
                token = token,
                body = JellyfinPlaybackInfoBody.build(session.userId),
                limits = limits,
            )
        ) {
            is ApiResult.Ok -> result.value
            is ApiResult.Err -> return MusicTransportResult.Failed(
                message = transportFailureMessage(result.failure.kind),
                kind = when (result.failure.kind) {
                    FailureKind.UNAUTHORIZED, FailureKind.FORBIDDEN -> MusicTransportFailure.DISCONNECTED
                    FailureKind.NOT_FOUND -> MusicTransportFailure.SOURCE_GONE
                    FailureKind.NEGOTIATION -> MusicTransportFailure.UNAVAILABLE
                    else -> MusicTransportFailure.TRANSPORT
                },
            )
        }

        val source = response.mediaSources.firstOrNull()
            ?: return MusicTransportResult.Failed(
                "This track is not available on the server.",
                MusicTransportFailure.UNAVAILABLE,
            )
        val mediaSourceId = source.id?.takeIf { it.isNotBlank() }
            ?: return MusicTransportResult.Failed(
                "This track is not available on the server.",
                MusicTransportFailure.UNAVAILABLE,
            )

        val mode = when {
            source.supportsDirectPlay == true -> ExpectedMode.DIRECT
            source.supportsDirectStream == true -> ExpectedMode.REMUX
            source.supportsTranscoding == true -> ExpectedMode.TRANSCODE
            else -> return MusicTransportResult.Failed(
                "This track cannot be played on this device.",
                MusicTransportFailure.UNAVAILABLE,
            )
        }
        val route = when (mode) {
            ExpectedMode.DIRECT -> "direct"
            ExpectedMode.REMUX -> "remux"
            else -> "transcode"
        }
        val playMethod = when (mode) {
            ExpectedMode.DIRECT -> "DirectPlay"
            ExpectedMode.REMUX -> "DirectStream"
            else -> "Transcode"
        }
        val audioStreamIndex = source.mediaStreams.firstOrNull { it.type == "Audio" && it.isDefault == true }?.index
            ?: source.mediaStreams.firstOrNull { it.type == "Audio" }?.index
        val payload = JellyfinTransportCodec.Payload(
            itemId = entry.trackNativeId,
            mediaSourceId = mediaSourceId,
            route = route,
            playSessionId = response.playSessionId,
            serverRoute = when (mode) {
                ExpectedMode.TRANSCODE, ExpectedMode.REMUX -> source.transcodingUrl
                else -> null
            },
            playMethod = playMethod,
            liveStreamId = source.liveStreamId,
            audioStreamIndex = audioStreamIndex,
            eTag = source.eTag,
            isRemote = source.isRemote,
            remotePath = source.path,
        )
        val request = try {
            transportResolver.requestFor(payload, requestId, container = source.container)
        } catch (_: IllegalArgumentException) {
            return MusicTransportResult.Failed(
                "This track's route was rejected by the network policy.",
                MusicTransportFailure.TRANSPORT,
            )
        } catch (_: IllegalStateException) {
            return MusicTransportResult.Failed("Jellyfin is not connected.", MusicTransportFailure.DISCONNECTED)
        }
        return MusicTransportResult.Ok(
            MusicTransport(
                request = request,
                reporting = MusicReportingIdentity(
                    itemId = entry.trackNativeId,
                    mediaSourceId = mediaSourceId,
                    playSessionId = response.playSessionId.orEmpty(),
                    playMethod = playMethod,
                ),
            ),
        )
    }

    override suspend fun lyrics(trackNativeId: String): MusicLyricsResult =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { readLyrics(trackNativeId) }

    private suspend fun readLyrics(trackNativeId: String): MusicLyricsResult {
        val session = sessionProvider()
            ?: return MusicLyricsResult.Failed("Jellyfin is not connected.", retryable = true)
        val token = authenticator.token(session) ?: return MusicLyricsResult.Failed(
            "Jellyfin is not connected.",
            retryable = true,
        )
        return when (val result = client.lyrics(trackNativeId, token, limits)) {
            is ApiResult.Ok -> {
                val lines = result.value
                if (lines.isEmpty()) {
                    MusicLyricsResult.Ok(MusicLyrics.None)
                } else if (lines.all { it.startTicks == null }) {
                    MusicLyricsResult.Ok(MusicLyrics.Plain(lines.map { it.text }))
                } else {

                    var lastStartTicks = 0L
                    val synced = lines.map { line ->
                        val ticks = line.startTicks ?: lastStartTicks
                        lastStartTicks = ticks
                        MusicLyricLine(startMs = ticks / 10_000L, text = line.text)
                    }
                    MusicLyricsResult.Ok(MusicLyrics.Synced(synced))
                }
            }
            is ApiResult.Err -> MusicLyricsResult.Failed(
                message = "Lyrics could not be loaded.",
                retryable = result.failure.kind != FailureKind.NOT_FOUND,
            )
        }
    }

    private fun JellyfinRowItem.toQueueEntry(context: MusicQueueContext, absoluteIndex: Int): MusicQueueEntry =
        MusicQueueEntry(
            occurrenceId = QueueEntryId("${context.occurrenceScope()}#$absoluteIndex"),
            trackNativeId = nativeItemId,
            albumNativeId = parentNativeId?.takeIf { it.isNotBlank() },
            albumArtistNativeId = albumArtistId?.takeIf { it.isNotBlank() },
            title = displayTitle,
            artist = albumArtistName?.takeIf { it.isNotBlank() },
            albumName = albumName?.takeIf { it.isNotBlank() },
            durationMs = runtimeTicks?.div(10_000L)?.takeIf { it > 0L },

            membershipId = playlistItemId?.takeIf { it.isNotBlank() },
        )

    private fun MusicQueueContext.occurrenceScope(): String = when (this) {
        is MusicQueueContext.Album -> "album:${albumNativeId}"
        is MusicQueueContext.Playlist -> "playlist:${playlistNativeId}"
        is MusicQueueContext.Songs -> "songs:${libraryNativeId}:${sortBy.joinToString(",")}:$sortOrder"
    }

    private fun pageFailureMessage(kind: FailureKind): String = when (kind) {
        FailureKind.UNAUTHORIZED, FailureKind.FORBIDDEN -> "Jellyfin is not connected."
        FailureKind.NOT_FOUND -> "This album or playlist is no longer available."
        FailureKind.TIMEOUT, FailureKind.NETWORK, FailureKind.DNS, FailureKind.SERVER_ERROR ->
            "The music library could not be reached."
        else -> "The music library could not be read."
    }

    private fun transportFailureMessage(kind: FailureKind): String = when (kind) {
        FailureKind.UNAUTHORIZED, FailureKind.FORBIDDEN -> "Jellyfin is not connected."
        FailureKind.NOT_FOUND -> "This track is no longer available."
        FailureKind.NEGOTIATION -> "This track cannot be prepared."
        else -> "This track could not be prepared."
    }
}
