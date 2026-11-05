package dev.infusion.feature.vertical.music

import dev.infusion.feature.vertical.jellyfin.JellyfinTabRowItem

enum class MusicLibraryTarget {
    ALBUM,
    ARTIST,
    PLAYLIST,

    TRACK,
}

fun JellyfinTabRowItem.musicLibraryTarget(): MusicLibraryTarget? =
    when (nativeType?.trim()) {
        "MusicAlbum" -> MusicLibraryTarget.ALBUM

        "MusicArtist" -> MusicLibraryTarget.ARTIST
        "Playlist" -> MusicLibraryTarget.PLAYLIST
        "Audio" -> MusicLibraryTarget.TRACK
        else -> null
    }
