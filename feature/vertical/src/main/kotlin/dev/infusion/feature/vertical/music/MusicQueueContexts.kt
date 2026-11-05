package dev.infusion.feature.vertical.music

import dev.infusion.core.domain.music.MusicQueueContext
import dev.infusion.core.model.SourceId
import dev.infusion.feature.vertical.jellyfin.library.LibraryLevel
import dev.infusion.feature.vertical.jellyfin.library.LibraryQueryKey
import dev.infusion.feature.vertical.jellyfin.library.LibrarySortOrder
import dev.infusion.feature.vertical.jellyfin.library.LibraryView

fun LibraryQueryKey.musicQueueContext(
    sourceId: SourceId,
): MusicQueueContext? = when (val current = level) {
    is LibraryLevel.Album -> MusicQueueContext.Album(
        sourceId = sourceId,
        albumNativeId = current.albumId,
        label = current.albumName,
    )
    is LibraryLevel.Playlist -> MusicQueueContext.Playlist(
        sourceId = sourceId,
        playlistNativeId = current.playlistId,
        label = current.playlistName,
    )
    LibraryLevel.Root -> if (view == LibraryView.SONGS) {
        MusicQueueContext.Songs(
            sourceId = sourceId,
            libraryNativeId = libraryNativeId,
            label = "Songs",
            sortBy = sortBy.ifEmpty { listOf("SortName") },
            sortOrder = sortOrder.serverValue,
            filters = buildMap {
                putAll(filters.toQueryParameters())
                putAll(viewParameters())
            },
        )
    } else {
        null
    }
    else -> null
}

fun LibraryQueryKey.isMusicQueueLevel(): Boolean =
    level is LibraryLevel.Album || level is LibraryLevel.Playlist ||
        (level == LibraryLevel.Root && view == LibraryView.SONGS)

fun MusicQueueContext.authoredOrderDescription(): String = when (this) {
    is MusicQueueContext.Album -> "album order (disc, then track, then name)"
    is MusicQueueContext.Playlist -> "playlist membership order"
    is MusicQueueContext.Songs -> "the captured Songs query order (${sortBy.joinToString(",")} ${sortOrder})"
}

internal fun String.asLibrarySortOrder(): LibrarySortOrder =
    LibrarySortOrder.entries.firstOrNull { it.serverValue == this } ?: LibrarySortOrder.ASCENDING
