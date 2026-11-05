package dev.infusion.tv.ui.presentation

import dev.infusion.core.model.ArtworkLocator
import dev.infusion.core.model.ArtworkPolicy
import dev.infusion.core.model.MediaArtworkRef
import dev.infusion.core.model.TitleKind
import dev.infusion.feature.vertical.jellyfin.JellyfinTabRowItem
import dev.infusion.feature.vertical.jellyfin.library.LibraryGenreArtwork
import dev.infusion.feature.vertical.jellyfin.library.LibraryLevel
import dev.infusion.feature.vertical.jellyfin.library.LibraryType
import dev.infusion.feature.vertical.jellyfin.library.LibraryView

fun libraryGridShape(
    type: LibraryType?,
    view: LibraryView,
    level: LibraryLevel,
    presentation: ContentCardPresentation,
): ContentCardShape = when {
    view == LibraryView.GENRES && level is LibraryLevel.Root -> ContentCardShape.LANDSCAPE
    view == LibraryView.NETWORKS && level is LibraryLevel.Root -> ContentCardShape.LANDSCAPE
    type == LibraryType.MUSIC -> ContentCardShape.SQUARE
    type == LibraryType.SHOWS -> presentation.shapeFor(TitleKind.SERIES)
    else -> presentation.shapeFor(TitleKind.MOVIE)
}

data class LibraryCategoryTile(
    val title: String,
    val artwork: MediaArtworkRef?,
)

fun libraryCategoryTiles(
    type: LibraryType?,
    view: LibraryView,
    level: LibraryLevel,
    items: List<JellyfinTabRowItem>,
    genreArtworkCandidates: Map<String, List<String>>,
): Map<String, LibraryCategoryTile> {
    if (level !is LibraryLevel.Root) return emptyMap()
    return when {
        view == LibraryView.GENRES -> genreCategoryTiles(type, items, genreArtworkCandidates)
        view == LibraryView.NETWORKS -> networkCategoryTiles(items)
        else -> emptyMap()
    }
}

private fun genreCategoryTiles(
    type: LibraryType?,
    items: List<JellyfinTabRowItem>,
    genreArtworkCandidates: Map<String, List<String>>,
): Map<String, LibraryCategoryTile> {
    val assigned = LibraryGenreArtwork.assign(
        genreIdsInPageOrder = items.map { it.nativeItemId },
        candidates = genreArtworkCandidates,
    )

    val artworkType = if (type == LibraryType.MUSIC) {
        ArtworkLocator.DEFAULT_CATALOG_TYPE
    } else {
        ArtworkLocator.CATALOG_BACKDROP_TYPE
    }
    val policy = if (type == LibraryType.MUSIC) ArtworkPolicy.POSTER else ArtworkPolicy.BACKDROP
    val tiles = LinkedHashMap<String, LibraryCategoryTile>(items.size)
    for (item in items) {
        val artworkItemId = assigned[item.nativeItemId]
        tiles[item.key] = LibraryCategoryTile(
            title = item.displayTitle,
            artwork = artworkItemId?.takeIf { it.isNotBlank() }?.let { id ->
                MediaArtworkRef.catalogTyped(
                    nativeItemId = id,
                    type = artworkType,
                    policy = policy,
                )
            },
        )
    }
    return tiles
}

private fun networkCategoryTiles(items: List<JellyfinTabRowItem>): Map<String, LibraryCategoryTile> {
    val tiles = LinkedHashMap<String, LibraryCategoryTile>(items.size)
    for (item in items) {
        val slot = when {
            "Primary" in item.imageTypes -> ArtworkLocator.DEFAULT_CATALOG_TYPE
            "Thumb" in item.imageTypes -> "Thumb"
            else -> null
        }
        tiles[item.key] = LibraryCategoryTile(
            title = item.displayTitle,
            artwork = slot?.let { type ->
                MediaArtworkRef.catalogTyped(
                    nativeItemId = item.nativeItemId,
                    type = type,
                    policy = ArtworkPolicy.POSTER,
                )
            },
        )
    }
    return tiles
}

fun libraryListRowArtwork(item: JellyfinTabRowItem): MediaArtworkRef? {
    if ("Primary" in item.imageTypes) {
        return MediaArtworkRef.catalogTyped(
            nativeItemId = item.nativeItemId,
            type = ArtworkLocator.DEFAULT_CATALOG_TYPE,
            policy = ArtworkPolicy.POSTER,
        )
    }
    val parent = item.parentNativeId?.takeIf { it.isNotBlank() } ?: return null
    return MediaArtworkRef.catalogTyped(
        nativeItemId = parent,
        type = ArtworkLocator.DEFAULT_CATALOG_TYPE,
        policy = ArtworkPolicy.POSTER,
    )
}
