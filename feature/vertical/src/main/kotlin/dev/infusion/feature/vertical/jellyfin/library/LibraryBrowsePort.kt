package dev.infusion.feature.vertical.jellyfin.library

import dev.infusion.feature.vertical.jellyfin.JellyfinTabRowItem

interface LibraryBrowsePort {

    suspend fun page(key: LibraryQueryKey, meta: LibraryRequestMeta): LibraryFetch

    suspend fun filterOptions(key: LibraryQueryKey): LibraryFilterOptionsFetch

    suspend fun sections(key: LibraryQueryKey): LibrarySuggestionsFetch

    suspend fun genreArtwork(
        key: LibraryQueryKey,
        genreIds: List<String>,
    ): LibraryGenreArtworkFetch
}

sealed interface LibraryFetch {
    data class Ok(val page: LibraryPage, val total: Int?) : LibraryFetch
    data class Failed(val message: String) : LibraryFetch
}

data class LibraryFilterOption(val value: String, val label: String = value)

data class LibraryFilterOptions(
    val genres: List<LibraryFilterOption> = emptyList(),
    val officialRatings: List<LibraryFilterOption> = emptyList(),
    val tags: List<LibraryFilterOption> = emptyList(),
    val years: List<Int> = emptyList(),
    val features: List<LibraryFeature> = LibraryFeature.entries,
    val statuses: List<LibraryStatusFilter> = LibraryStatusFilter.entries,
    val videoTypes: List<LibraryVideoType> = LibraryVideoType.entries,

    val seriesStatuses: List<LibrarySeriesStatus> = LibrarySeriesStatus.entries,

    val episodeFlags: List<LibraryEpisodeFlag> = LibraryEpisodeFlag.entries,
)

sealed interface LibraryFilterOptionsFetch {
    data class Ok(val options: LibraryFilterOptions) : LibraryFilterOptionsFetch
    data class Failed(val message: String) : LibraryFilterOptionsFetch
}

data class LibrarySuggestionSection(
    val key: String,
    val title: String,
    val items: List<JellyfinTabRowItem>,
)

sealed interface LibrarySuggestionsFetch {
    data class Ok(val sections: List<LibrarySuggestionSection>) : LibrarySuggestionsFetch
    data class Failed(val message: String) : LibrarySuggestionsFetch
}

sealed interface LibraryGenreArtworkFetch {
    data class Ok(val candidates: Map<String, List<String>>) : LibraryGenreArtworkFetch
    data class Failed(val message: String) : LibraryGenreArtworkFetch
}
