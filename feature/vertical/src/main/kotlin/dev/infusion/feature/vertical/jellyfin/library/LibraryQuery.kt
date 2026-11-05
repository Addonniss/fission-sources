package dev.infusion.feature.vertical.jellyfin.library

enum class LibraryType(val collectionType: String, val label: String) {
    MOVIES("movies", "Movies"),
    SHOWS("tvshows", "Shows"),
    MUSIC("music", "Music"),
    ;

    companion object {
        fun of(collectionType: String?): LibraryType? {
            val normalized = collectionType?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.collectionType == normalized }
        }
    }
}

enum class LibraryView(val label: String) {

    MOVIES("Movies"),
    SUGGESTIONS("Suggestions"),
    FAVORITES("Favorites"),
    COLLECTIONS("Collections"),
    GENRES("Genres"),

    SHOWS("Shows"),
    UPCOMING("Upcoming"),
    NETWORKS("TV Networks"),
    EPISODES("Episodes"),

    ALBUMS("Albums"),
    ALBUM_ARTISTS("Album artists"),
    ARTISTS("Artists"),
    PLAYLISTS("Playlists"),
    SONGS("Songs"),
}

enum class LibraryPresentation { GRID, LIST, SECTIONS }

sealed interface LibraryLevel {

    data object Root : LibraryLevel

    data class Genre(val genreId: String, val genreName: String) : LibraryLevel

    data class BoxSet(val boxSetId: String, val boxSetName: String) : LibraryLevel

    data class Network(val studioId: String, val networkName: String) : LibraryLevel

    data class Artist(val artistId: String, val artistName: String) : LibraryLevel

    data class Album(
        val albumId: String,
        val albumName: String,
        val artistId: String? = null,
        val artistName: String? = null,
    ) : LibraryLevel

    data class Playlist(val playlistId: String, val playlistName: String) : LibraryLevel
}

enum class LibraryStatusFilter(val label: String, val serverValue: String) {
    PLAYED("Played", "IsPlayed"),
    UNPLAYED("Unplayed", "IsUnplayed"),
    RESUMABLE("Continue watching", "IsResumable"),
    FAVORITE("Favorites", "IsFavorite"),
}

enum class LibrarySeriesStatus(val label: String, val serverValue: String) {
    CONTINUING("Continuing", "Continuing"),
    ENDED("Ended", "Ended"),
    UNRELEASED("Unreleased", "Unreleased"),
}

enum class LibraryEpisodeFlag(val label: String) {
    SPECIALS("Specials"),
    MISSING("Missing"),
    UNAIRED("Unaired"),
    ;

    fun parameters(): Map<String, String> = when (this) {
        SPECIALS -> mapOf("parentIndexNumber" to "0")
        MISSING -> mapOf("isMissing" to "true")
        UNAIRED -> mapOf("isUnaired" to "true")
    }
}

enum class LibraryFeature(val label: String, val parameter: String) {
    SUBTITLES("Subtitles", "hasSubtitles"),
    TRAILER("Trailers", "hasTrailer"),
    SPECIAL_FEATURE("Extras", "hasSpecialFeature"),
    THEME_SONG("Theme songs", "hasThemeSong"),
    THEME_VIDEO("Theme videos", "hasThemeVideo"),
}

enum class LibraryVideoType(val label: String) {
    BLURAY("Blu-ray"),
    DVD("DVD"),
    HD("HD"),
    FOUR_K("4K"),
    SD("SD"),
    THREE_D("3D"),
    ;

    fun parameters(): Map<String, String> = when (this) {
        BLURAY -> mapOf("videoTypes" to "Bluray")
        DVD -> mapOf("videoTypes" to "Dvd")
        HD -> mapOf("isHD" to "true")
        FOUR_K -> mapOf("is4K" to "true")
        SD -> mapOf("isHD" to "false")
        THREE_D -> mapOf("is3D" to "true")
    }
}

enum class LibraryFilterGroup(val label: String) {
    STATUS("Status"),
    SERIES_STATUS("Series status"),
    EPISODE_FLAGS("Episodes status"),
    FEATURES("Features"),
    GENRES("Genres"),
    PARENTAL("Parental rating"),
    TAGS("Tags"),
    VIDEO_TYPE("Video type"),
    YEARS("Years"),
}

data class LibraryFilterValues(
    val statuses: Set<LibraryStatusFilter> = emptySet(),

    val seriesStatus: LibrarySeriesStatus? = null,

    val episodeFlags: Set<LibraryEpisodeFlag> = emptySet(),
    val features: Set<LibraryFeature> = emptySet(),
    val genre: String? = null,
    val officialRating: String? = null,
    val tag: String? = null,
    val videoType: LibraryVideoType? = null,

    val years: Set<Int> = emptySet(),
) {

    val isEmpty: Boolean
        get() = statuses.isEmpty() && seriesStatus == null && episodeFlags.isEmpty() &&
            features.isEmpty() && genre == null && officialRating == null && tag == null &&
            videoType == null && years.isEmpty()

    fun activeGroupCount(groups: List<LibraryFilterGroup>): Int = groups.count { group ->
        when (group) {
            LibraryFilterGroup.STATUS -> statuses.isNotEmpty()
            LibraryFilterGroup.SERIES_STATUS -> seriesStatus != null
            LibraryFilterGroup.EPISODE_FLAGS -> episodeFlags.isNotEmpty()
            LibraryFilterGroup.FEATURES -> features.isNotEmpty()
            LibraryFilterGroup.GENRES -> genre != null
            LibraryFilterGroup.PARENTAL -> officialRating != null
            LibraryFilterGroup.TAGS -> tag != null
            LibraryFilterGroup.VIDEO_TYPE -> videoType != null
            LibraryFilterGroup.YEARS -> years.isNotEmpty()
        }
    }

    fun toQueryParameters(): Map<String, String> = buildMap {
        if (statuses.isNotEmpty()) {
            put("filters", statuses.map { it.serverValue }.sorted().joinToString(","))
        }
        seriesStatus?.let { put("seriesStatus", it.serverValue) }

        episodeFlags.sortedBy { it.ordinal }.forEach { putAll(it.parameters()) }
        features.sortedBy { it.ordinal }.forEach { put(it.parameter, "true") }
        genre?.takeIf { it.isNotBlank() }?.let { put("genres", it) }
        officialRating?.takeIf { it.isNotBlank() }?.let { put("officialRatings", it) }
        tag?.takeIf { it.isNotBlank() }?.let { put("tags", it) }
        videoType?.let { putAll(it.parameters()) }
        if (years.isNotEmpty()) put("years", years.sorted().joinToString(","))
    }

    fun withStatus(status: LibraryStatusFilter): LibraryFilterValues =
        copy(statuses = if (status in statuses) statuses - status else statuses + status)

    fun withSeriesStatus(status: LibrarySeriesStatus?): LibraryFilterValues =
        copy(seriesStatus = if (status == seriesStatus) null else status)

    fun withEpisodeFlag(flag: LibraryEpisodeFlag): LibraryFilterValues =
        copy(episodeFlags = if (flag in episodeFlags) episodeFlags - flag else episodeFlags + flag)

    fun withFeature(feature: LibraryFeature): LibraryFilterValues =
        copy(features = if (feature in features) features - feature else features + feature)

    fun withYear(year: Int): LibraryFilterValues =
        copy(years = if (year in years) years - year else years + year)
}

enum class LibrarySortField(val label: String) {
    NAME("Name"),
    TRACK_NAME("Track Name"),
    ALBUM("Album"),
    ALBUM_ARTIST("Album Artist"),
    ARTIST("Artist"),
    COMMUNITY("Community"),
    CRITICS("Critics"),
    DATE_ADDED("Date Added"),
    DATE_EPISODE_ADDED("Date Episode Added"),
    DATE_PLAYED("Date Played"),
    PARENTAL("Parental"),
    PLAY_COUNT("Play Count"),
    RELEASE_DATE("Release Date"),
    RUNTIME("Runtime"),
    ;

    val initialDescending: Boolean
        get() = when (this) {
            NAME, TRACK_NAME, ALBUM, ALBUM_ARTIST, ARTIST, PARENTAL, RUNTIME -> false
            COMMUNITY, CRITICS, DATE_ADDED, DATE_EPISODE_ADDED, DATE_PLAYED, PLAY_COUNT,
            RELEASE_DATE,
            -> true
        }

    fun labelFor(view: LibraryView): String = when {
        this == COMMUNITY && view == LibraryView.EPISODES -> "TheTVDB Rating"
        this == RELEASE_DATE && view == LibraryView.EPISODES -> "Premiere Date"
        this == RELEASE_DATE && view == LibraryView.ALBUMS -> "Release"
        else -> label
    }
}

enum class LibrarySortOrder(val serverValue: String) {
    ASCENDING("Ascending"),
    DESCENDING("Descending"),
    ;

    fun reversed(): LibrarySortOrder = if (this == ASCENDING) DESCENDING else ASCENDING
}

data class LibrarySort(
    val field: LibrarySortField,
    val order: LibrarySortOrder,
) {

    fun sortBy(type: LibraryType, view: LibraryView, level: LibraryLevel): List<String> =
        LibraryCapabilities.sortBy(field, type, view, level)

    fun select(field: LibrarySortField): LibrarySort =
        if (field == this.field) {
            copy(order = order.reversed())
        } else {
            LibrarySort(field, if (field.initialDescending) LibrarySortOrder.DESCENDING else LibrarySortOrder.ASCENDING)
        }
}

data class LibraryQueryKey(

    val type: LibraryType,

    val serverId: String,

    val userId: String,

    val libraryNativeId: String,

    val view: LibraryView,

    val itemTypes: List<String>,

    val level: LibraryLevel = LibraryLevel.Root,

    val filters: LibraryFilterValues = LibraryFilterValues(),

    val sortBy: List<String> = emptyList(),

    val sortOrder: LibrarySortOrder = LibrarySortOrder.ASCENDING,
) {

    val parentNativeId: String?
        get() = when (val l = level) {
            LibraryLevel.Root -> null
            is LibraryLevel.Genre -> l.genreId
            is LibraryLevel.BoxSet -> l.boxSetId
            is LibraryLevel.Network -> l.studioId
            is LibraryLevel.Artist -> l.artistId
            is LibraryLevel.Album -> l.albumId
            is LibraryLevel.Playlist -> l.playlistId
        }

    val source: LibraryQuerySource
        get() = LibraryCapabilities.source(type, view, level)

    val presentation: LibraryPresentation
        get() = LibraryCapabilities.presentation(type, view, level)

    val queryParentId: String?
        get() = when (val l = level) {
            LibraryLevel.Root ->
                if (source == LibraryQuerySource.PLAYLISTS) null else libraryNativeId
            is LibraryLevel.BoxSet -> l.boxSetId
            is LibraryLevel.Album -> l.albumId
            is LibraryLevel.Playlist -> l.playlistId
            is LibraryLevel.Genre, is LibraryLevel.Network, is LibraryLevel.Artist -> libraryNativeId
        }

    val recursive: Boolean
        get() = when (level) {
            is LibraryLevel.Album, is LibraryLevel.Playlist -> false
            else -> true
        }

    val parentLevel: LibraryLevel?
        get() = when (val l = level) {
            LibraryLevel.Root -> null
            is LibraryLevel.Album ->
                if (l.artistId != null) {
                    LibraryLevel.Artist(l.artistId, l.artistName ?: "")
                } else {
                    LibraryLevel.Root
                }
            is LibraryLevel.Genre, is LibraryLevel.BoxSet, is LibraryLevel.Network,
            is LibraryLevel.Artist, is LibraryLevel.Playlist,
            -> LibraryLevel.Root
        }

    fun viewParameters(): Map<String, String> = buildMap {
        if (view == LibraryView.FAVORITES) put("isFavorite", "true")
        when (val l = level) {
            is LibraryLevel.Genre -> put("genreIds", l.genreId)
            is LibraryLevel.Network -> put("studioIds", l.studioId)
            is LibraryLevel.Artist -> put("albumArtistIds", l.artistId)
            else -> Unit
        }
        if (view == LibraryView.EPISODES) {
            put(
                "isMissing",
                if (LibraryEpisodeFlag.MISSING in filters.episodeFlags) "true" else "false",
            )
        }
    }
}

enum class LibraryQuerySource {

    ITEMS,

    GENRES,

    STUDIOS,

    ARTISTS,

    ALBUM_ARTISTS,

    PLAYLISTS,

    PLAYLIST_ITEMS,
}

data class LibraryRequestMeta(

    val startIndex: Int,

    val limit: Int,

    val enableTotalRecordCount: Boolean,
) {
    val pageIndex: Int get() = startIndex / limit
}

object LibraryCapabilities {

    const val PAGE_SIZE: Int = 60

    const val RETAINED_PAGES: Int = 6

    const val PREFETCH_ROWS: Int = 2

    const val CATEGORY_PAGE_SIZE: Int = 30

    fun support(collectionType: String?): LibrarySupport {
        val type = LibraryType.of(collectionType) ?: return LibrarySupport.Unsupported(collectionType)
        return LibrarySupport.Supported(type, views(type))
    }

    fun views(type: LibraryType): List<LibraryView> = when (type) {
        LibraryType.MOVIES -> listOf(
            LibraryView.MOVIES,
            LibraryView.SUGGESTIONS,
            LibraryView.FAVORITES,
            LibraryView.COLLECTIONS,
            LibraryView.GENRES,
        )
        LibraryType.SHOWS -> listOf(
            LibraryView.SHOWS,
            LibraryView.SUGGESTIONS,
            LibraryView.UPCOMING,
            LibraryView.GENRES,
            LibraryView.NETWORKS,
            LibraryView.EPISODES,
        )
        LibraryType.MUSIC -> listOf(
            LibraryView.ALBUMS,
            LibraryView.SUGGESTIONS,
            LibraryView.ALBUM_ARTISTS,
            LibraryView.ARTISTS,
            LibraryView.PLAYLISTS,
            LibraryView.SONGS,
            LibraryView.GENRES,
        )
    }

    fun presentation(type: LibraryType, view: LibraryView, level: LibraryLevel): LibraryPresentation = when {
        view == LibraryView.SUGGESTIONS || view == LibraryView.UPCOMING -> LibraryPresentation.SECTIONS
        level is LibraryLevel.Album || level is LibraryLevel.Playlist -> LibraryPresentation.LIST
        view == LibraryView.SONGS -> LibraryPresentation.LIST
        else -> LibraryPresentation.GRID
    }

    fun source(type: LibraryType, view: LibraryView, level: LibraryLevel): LibraryQuerySource = when {
        level is LibraryLevel.Playlist -> LibraryQuerySource.PLAYLIST_ITEMS
        view == LibraryView.GENRES && level is LibraryLevel.Root -> LibraryQuerySource.GENRES
        view == LibraryView.NETWORKS && level is LibraryLevel.Root -> LibraryQuerySource.STUDIOS
        view == LibraryView.PLAYLISTS && level is LibraryLevel.Root -> LibraryQuerySource.PLAYLISTS
        type == LibraryType.MUSIC && view == LibraryView.ARTISTS && level is LibraryLevel.Root ->
            LibraryQuerySource.ARTISTS
        type == LibraryType.MUSIC && view == LibraryView.ALBUM_ARTISTS && level is LibraryLevel.Root ->
            LibraryQuerySource.ALBUM_ARTISTS
        else -> LibraryQuerySource.ITEMS
    }

    fun itemTypes(type: LibraryType, view: LibraryView, level: LibraryLevel): List<String> = when (type) {
        LibraryType.MOVIES -> when {
            view == LibraryView.COLLECTIONS && level is LibraryLevel.Root -> listOf("BoxSet")
            else -> listOf("Movie")
        }
        LibraryType.SHOWS -> when (view) {
            LibraryView.EPISODES, LibraryView.SUGGESTIONS, LibraryView.UPCOMING -> listOf("Episode")
            else -> listOf("Series")
        }
        LibraryType.MUSIC -> when {
            level is LibraryLevel.Album || level is LibraryLevel.Playlist -> listOf("Audio")
            view == LibraryView.SONGS -> listOf("Audio")
            view == LibraryView.ARTISTS || view == LibraryView.ALBUM_ARTISTS ->
                if (level is LibraryLevel.Root) listOf("MusicArtist") else listOf("MusicAlbum")
            view == LibraryView.PLAYLISTS -> if (level is LibraryLevel.Root) {
                listOf("Playlist")
            } else {
                listOf("Audio")
            }
            else -> listOf("MusicAlbum")
        }
    }

    fun sortFields(type: LibraryType, view: LibraryView, level: LibraryLevel): List<LibrarySortField> = when (type) {
        LibraryType.MOVIES -> when {
            view == LibraryView.SUGGESTIONS -> emptyList()
            view == LibraryView.COLLECTIONS && level is LibraryLevel.Root -> listOf(
                LibrarySortField.NAME,
                LibrarySortField.COMMUNITY,
                LibrarySortField.DATE_ADDED,
                LibrarySortField.PARENTAL,
                LibrarySortField.RELEASE_DATE,
            )
            view == LibraryView.GENRES && level is LibraryLevel.Root -> emptyList()
            else -> MOVIE_ITEM_SORTS
        }
        LibraryType.SHOWS -> when {
            view == LibraryView.SHOWS -> listOf(
                LibrarySortField.NAME,
                LibrarySortField.COMMUNITY,
                LibrarySortField.DATE_ADDED,
                LibrarySortField.DATE_EPISODE_ADDED,
                LibrarySortField.DATE_PLAYED,
                LibrarySortField.PARENTAL,
                LibrarySortField.RELEASE_DATE,
            )
            view == LibraryView.EPISODES -> listOf(
                LibrarySortField.NAME,
                LibrarySortField.COMMUNITY,
                LibrarySortField.DATE_ADDED,
                LibrarySortField.RELEASE_DATE,
                LibrarySortField.DATE_PLAYED,
                LibrarySortField.PARENTAL,
                LibrarySortField.PLAY_COUNT,
                LibrarySortField.RUNTIME,
            )
            view == LibraryView.GENRES && level is LibraryLevel.Genre -> SHOW_ITEM_SORTS
            view == LibraryView.NETWORKS && level is LibraryLevel.Network -> SHOW_ITEM_SORTS
            else -> emptyList()
        }
        LibraryType.MUSIC -> when {

            view == LibraryView.ALBUMS && level is LibraryLevel.Album -> emptyList()
            view == LibraryView.ALBUMS -> ALBUM_SORTS
            view == LibraryView.SONGS -> SONG_SORTS
            view == LibraryView.GENRES && level is LibraryLevel.Genre -> ALBUM_SORTS
            view == LibraryView.ARTISTS && level is LibraryLevel.Artist -> ALBUM_SORTS
            view == LibraryView.ALBUM_ARTISTS && level is LibraryLevel.Artist -> ALBUM_SORTS
            else -> emptyList()
        }
    }

    private val MOVIE_ITEM_SORTS: List<LibrarySortField> = listOf(
        LibrarySortField.NAME,
        LibrarySortField.COMMUNITY,
        LibrarySortField.CRITICS,
        LibrarySortField.DATE_ADDED,
        LibrarySortField.DATE_PLAYED,
        LibrarySortField.PARENTAL,
        LibrarySortField.PLAY_COUNT,
        LibrarySortField.RELEASE_DATE,
        LibrarySortField.RUNTIME,
    )

    private val SHOW_ITEM_SORTS: List<LibrarySortField> = listOf(
        LibrarySortField.NAME,
        LibrarySortField.COMMUNITY,
        LibrarySortField.DATE_ADDED,
        LibrarySortField.DATE_EPISODE_ADDED,
        LibrarySortField.DATE_PLAYED,
        LibrarySortField.PARENTAL,
        LibrarySortField.RELEASE_DATE,
    )

    private val ALBUM_SORTS: List<LibrarySortField> = listOf(
        LibrarySortField.NAME,
        LibrarySortField.ALBUM_ARTIST,
        LibrarySortField.COMMUNITY,
        LibrarySortField.CRITICS,
        LibrarySortField.DATE_ADDED,
        LibrarySortField.RELEASE_DATE,
    )

    private val SONG_SORTS: List<LibrarySortField> = listOf(
        LibrarySortField.TRACK_NAME,
        LibrarySortField.ALBUM,
        LibrarySortField.ALBUM_ARTIST,
        LibrarySortField.ARTIST,
        LibrarySortField.DATE_ADDED,
        LibrarySortField.DATE_PLAYED,
        LibrarySortField.PLAY_COUNT,
        LibrarySortField.RELEASE_DATE,
        LibrarySortField.RUNTIME,
    )

    fun filterGroups(type: LibraryType, view: LibraryView, level: LibraryLevel): List<LibraryFilterGroup> {
        if (level is LibraryLevel.Album || level is LibraryLevel.Playlist) return emptyList()
        return when (type) {
            LibraryType.MOVIES -> when {
                view == LibraryView.MOVIES || view == LibraryView.FAVORITES -> MOVIE_FILTER_GROUPS
                view == LibraryView.GENRES && level is LibraryLevel.Genre -> MOVIE_FILTER_GROUPS
                view == LibraryView.COLLECTIONS && level is LibraryLevel.BoxSet -> MOVIE_FILTER_GROUPS
                else -> emptyList()
            }
            LibraryType.SHOWS -> when {
                view == LibraryView.SHOWS -> SHOW_FILTER_GROUPS
                view == LibraryView.EPISODES -> EPISODE_FILTER_GROUPS
                view == LibraryView.GENRES && level is LibraryLevel.Genre -> SHOW_FILTER_GROUPS
                view == LibraryView.NETWORKS && level is LibraryLevel.Network -> SHOW_FILTER_GROUPS
                else -> emptyList()
            }
            LibraryType.MUSIC -> when {
                view == LibraryView.ALBUMS || view == LibraryView.SONGS ||
                    view == LibraryView.ARTISTS || view == LibraryView.ALBUM_ARTISTS ->
                    MUSIC_FILTER_GROUPS
                view == LibraryView.GENRES && level is LibraryLevel.Genre -> MUSIC_FILTER_GROUPS
                else -> emptyList()
            }
        }
    }

    private val MOVIE_FILTER_GROUPS: List<LibraryFilterGroup> = listOf(
        LibraryFilterGroup.STATUS,
        LibraryFilterGroup.FEATURES,
        LibraryFilterGroup.GENRES,
        LibraryFilterGroup.PARENTAL,
        LibraryFilterGroup.TAGS,
        LibraryFilterGroup.VIDEO_TYPE,
        LibraryFilterGroup.YEARS,
    )

    private val SHOW_FILTER_GROUPS: List<LibraryFilterGroup> = listOf(
        LibraryFilterGroup.STATUS,
        LibraryFilterGroup.SERIES_STATUS,
        LibraryFilterGroup.FEATURES,
        LibraryFilterGroup.GENRES,
        LibraryFilterGroup.PARENTAL,
        LibraryFilterGroup.TAGS,
        LibraryFilterGroup.VIDEO_TYPE,
        LibraryFilterGroup.YEARS,
    )

    private val EPISODE_FILTER_GROUPS: List<LibraryFilterGroup> = listOf(
        LibraryFilterGroup.STATUS,
        LibraryFilterGroup.EPISODE_FLAGS,
        LibraryFilterGroup.FEATURES,
        LibraryFilterGroup.GENRES,
        LibraryFilterGroup.PARENTAL,
        LibraryFilterGroup.TAGS,
        LibraryFilterGroup.VIDEO_TYPE,
        LibraryFilterGroup.YEARS,
    )

    private val MUSIC_FILTER_GROUPS: List<LibraryFilterGroup> = listOf(
        LibraryFilterGroup.GENRES,
        LibraryFilterGroup.PARENTAL,
        LibraryFilterGroup.TAGS,
        LibraryFilterGroup.YEARS,
    )

    fun hasFilters(type: LibraryType, view: LibraryView, level: LibraryLevel): Boolean =
        filterGroups(type, view, level).isNotEmpty()

    fun defaultSort(type: LibraryType, view: LibraryView, level: LibraryLevel): LibrarySort {
        val field = when {

            level is LibraryLevel.Playlist -> LibrarySortField.NAME

            level is LibraryLevel.Album -> LibrarySortField.TRACK_NAME
            type == LibraryType.MUSIC && view == LibraryView.SONGS -> LibrarySortField.ALBUM
            else -> LibrarySortField.NAME
        }
        return LibrarySort(field = field, order = LibrarySortOrder.ASCENDING)
    }

    fun sortBy(
        field: LibrarySortField,
        type: LibraryType,
        view: LibraryView,
        level: LibraryLevel,
    ): List<String> = when {

        level is LibraryLevel.Playlist -> emptyList()

        level is LibraryLevel.Album -> listOf("ParentIndexNumber", "IndexNumber", "SortName")
        else -> when (type) {
            LibraryType.MOVIES -> movieSortBy(field, view == LibraryView.COLLECTIONS)
            LibraryType.SHOWS -> when (view) {
                LibraryView.EPISODES -> episodeSortBy(field)
                else -> showSortBy(field)
            }
            LibraryType.MUSIC -> when (view) {
                LibraryView.SONGS -> songSortBy(field)
                else -> albumSortBy(field)
            }
        }
    }

    private fun movieSortBy(field: LibrarySortField, shortMenu: Boolean): List<String> = when (field) {
        LibrarySortField.NAME -> if (shortMenu) listOf("SortName") else listOf("SortName", "ProductionYear")
        LibrarySortField.COMMUNITY ->
            if (shortMenu) listOf("CommunityRating", "SortName")
            else listOf("CommunityRating", "SortName", "ProductionYear")
        LibrarySortField.CRITICS -> listOf("CriticRating", "SortName", "ProductionYear")
        LibrarySortField.DATE_ADDED ->
            if (shortMenu) listOf("DateCreated", "SortName")
            else listOf("DateCreated", "SortName", "ProductionYear")
        LibrarySortField.DATE_PLAYED -> listOf("DatePlayed", "SortName", "ProductionYear")
        LibrarySortField.PARENTAL ->
            if (shortMenu) listOf("OfficialRating", "SortName")
            else listOf("OfficialRating", "SortName", "ProductionYear")
        LibrarySortField.PLAY_COUNT -> listOf("PlayCount", "SortName", "ProductionYear")
        LibrarySortField.RELEASE_DATE ->
            if (shortMenu) listOf("PremiereDate", "SortName")
            else listOf("PremiereDate", "SortName", "ProductionYear")
        LibrarySortField.RUNTIME -> listOf("Runtime", "SortName", "ProductionYear")
        else -> listOf("SortName", "ProductionYear")
    }

    private fun showSortBy(field: LibrarySortField): List<String> = when (field) {
        LibrarySortField.NAME -> listOf("SortName")
        LibrarySortField.COMMUNITY -> listOf("CommunityRating", "SortName")
        LibrarySortField.DATE_ADDED -> listOf("DateCreated", "SortName")
        LibrarySortField.DATE_EPISODE_ADDED -> listOf("DateLastContentAdded", "SortName")
        LibrarySortField.DATE_PLAYED -> listOf("SeriesDatePlayed", "SortName")
        LibrarySortField.PARENTAL -> listOf("OfficialRating", "SortName")
        LibrarySortField.RELEASE_DATE -> listOf("PremiereDate", "SortName")
        else -> listOf("SortName")
    }

    private fun episodeSortBy(field: LibrarySortField): List<String> = when (field) {
        LibrarySortField.NAME -> listOf("SeriesSortName", "SortName")
        LibrarySortField.COMMUNITY -> listOf("CommunityRating", "SeriesSortName", "SortName")
        LibrarySortField.DATE_ADDED -> listOf("DateCreated", "SeriesSortName", "SortName")
        LibrarySortField.RELEASE_DATE -> listOf("PremiereDate", "SeriesSortName", "SortName")
        LibrarySortField.DATE_PLAYED -> listOf("DatePlayed", "SeriesSortName", "SortName")
        LibrarySortField.PARENTAL -> listOf("OfficialRating", "SeriesSortName", "SortName")
        LibrarySortField.PLAY_COUNT -> listOf("PlayCount", "SeriesSortName", "SortName")
        LibrarySortField.RUNTIME -> listOf("Runtime", "SeriesSortName", "SortName")
        else -> listOf("SeriesSortName", "SortName")
    }

    private fun albumSortBy(field: LibrarySortField): List<String> = when (field) {
        LibrarySortField.NAME -> listOf("SortName")
        LibrarySortField.ALBUM_ARTIST -> listOf("AlbumArtist", "SortName")
        LibrarySortField.COMMUNITY -> listOf("CommunityRating", "SortName")
        LibrarySortField.CRITICS -> listOf("CriticRating", "SortName")
        LibrarySortField.DATE_ADDED -> listOf("DateCreated", "SortName")
        LibrarySortField.RELEASE_DATE -> listOf("ProductionYear", "PremiereDate", "SortName")
        else -> listOf("SortName")
    }

    private fun songSortBy(field: LibrarySortField): List<String> = when (field) {
        LibrarySortField.TRACK_NAME -> listOf("Name")
        LibrarySortField.ALBUM -> listOf("Album", "AlbumArtist", "SortName")
        LibrarySortField.ALBUM_ARTIST -> listOf("AlbumArtist", "Album", "SortName")
        LibrarySortField.ARTIST -> listOf("Artist", "Album", "SortName")
        LibrarySortField.DATE_ADDED -> listOf("DateCreated", "SortName")
        LibrarySortField.DATE_PLAYED -> listOf("DatePlayed", "SortName")
        LibrarySortField.PLAY_COUNT -> listOf("PlayCount", "SortName")
        LibrarySortField.RELEASE_DATE -> listOf("PremiereDate", "AlbumArtist", "Album", "SortName")
        LibrarySortField.RUNTIME -> listOf("Runtime", "AlbumArtist", "Album", "SortName")
        else -> listOf("Album", "SortName")
    }

    fun opensChildLevel(type: LibraryType, view: LibraryView, level: LibraryLevel): Boolean = when {
        level is LibraryLevel.Root && (
            view == LibraryView.GENRES || view == LibraryView.COLLECTIONS ||
                view == LibraryView.NETWORKS || view == LibraryView.ARTISTS ||
                view == LibraryView.ALBUM_ARTISTS || view == LibraryView.PLAYLISTS ||
                (type == LibraryType.MUSIC && view == LibraryView.ALBUMS)
            ) -> true
        type == LibraryType.MUSIC && level is LibraryLevel.Genre && view == LibraryView.GENRES -> true
        type == LibraryType.MUSIC && level is LibraryLevel.Artist &&
            (view == LibraryView.ARTISTS || view == LibraryView.ALBUM_ARTISTS) -> true
        else -> false
    }

    fun childLevel(
        type: LibraryType,
        view: LibraryView,
        level: LibraryLevel,
        nativeId: String,
        title: String,
    ): LibraryLevel? {
        if (!opensChildLevel(type, view, level)) return null
        return when {
            level is LibraryLevel.Root && view == LibraryView.GENRES ->
                LibraryLevel.Genre(nativeId, title)
            level is LibraryLevel.Root && view == LibraryView.COLLECTIONS ->
                LibraryLevel.BoxSet(nativeId, title)
            level is LibraryLevel.Root && view == LibraryView.NETWORKS ->
                LibraryLevel.Network(nativeId, title)
            level is LibraryLevel.Root && (view == LibraryView.ARTISTS || view == LibraryView.ALBUM_ARTISTS) ->
                LibraryLevel.Artist(nativeId, title)
            level is LibraryLevel.Root && view == LibraryView.PLAYLISTS ->
                LibraryLevel.Playlist(nativeId, title)
            type == LibraryType.MUSIC && level is LibraryLevel.Root && view == LibraryView.ALBUMS ->
                LibraryLevel.Album(nativeId, title)
            type == LibraryType.MUSIC && level is LibraryLevel.Genre && view == LibraryView.GENRES ->
                LibraryLevel.Album(nativeId, title)
            type == LibraryType.MUSIC && level is LibraryLevel.Artist ->
                LibraryLevel.Album(nativeId, title, level.artistId, level.artistName)
            else -> null
        }
    }

    fun itemFields(type: LibraryType, view: LibraryView, level: LibraryLevel): String = when {
        level is LibraryLevel.Playlist -> "ParentId,Album,AlbumArtist,Artists,RunTimeTicks,SortName"
        level is LibraryLevel.Album -> "ParentId,Album,AlbumArtist,Artists,RunTimeTicks,SortName"
        type == LibraryType.MUSIC && view == LibraryView.SONGS ->
            "ParentId,Album,AlbumArtist,Artists,RunTimeTicks,SortName"
        type == LibraryType.MUSIC -> "PrimaryImageAspectRatio,SortName"
        type == LibraryType.SHOWS && view == LibraryView.SHOWS -> "PrimaryImageAspectRatio"
        type == LibraryType.SHOWS && view == LibraryView.EPISODES -> "PrimaryImageAspectRatio,MediaSourceCount"
        type == LibraryType.SHOWS -> "PrimaryImageAspectRatio"
        view == LibraryView.COLLECTIONS && level is LibraryLevel.Root -> "PrimaryImageAspectRatio,SortName"
        else -> "PrimaryImageAspectRatio,MediaSourceCount"
    }

    fun key(
        serverId: String,
        userId: String,
        libraryNativeId: String,
        type: LibraryType,
        view: LibraryView,
        level: LibraryLevel = LibraryLevel.Root,
        filters: LibraryFilterValues = LibraryFilterValues(),
        sort: LibrarySort? = null,
    ): LibraryQueryKey {
        val effectiveSort = sort ?: defaultSort(type, view, level)
        return LibraryQueryKey(
            type = type,
            serverId = serverId,
            userId = userId,
            libraryNativeId = libraryNativeId,
            view = view,
            itemTypes = itemTypes(type, view, level),
            level = level,
            filters = filters,
            sortBy = effectiveSort.sortBy(type, view, level),
            sortOrder = effectiveSort.order,
        )
    }
}

sealed interface LibrarySupport {

    data class Supported(val type: LibraryType, val views: List<LibraryView>) : LibrarySupport

    data class Unsupported(val collectionType: String?) : LibrarySupport
}
