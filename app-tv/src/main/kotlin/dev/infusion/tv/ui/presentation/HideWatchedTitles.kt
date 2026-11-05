package dev.infusion.tv.ui.presentation

import dev.infusion.core.model.MediaPreview
import dev.infusion.core.model.TitleKind
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.feature.vertical.jellyfin.JellyfinRowKind
import dev.infusion.feature.vertical.jellyfin.JellyfinRowState
import dev.infusion.feature.vertical.jellyfin.JellyfinTabRow
import dev.infusion.feature.vertical.jellyfin.JellyfinTabUiState
import dev.infusion.feature.vertical.media.MediaCardUi
import dev.infusion.feature.vertical.media.canonicalKind
import dev.infusion.feature.vertical.media.canonicalTitleIdOrNull
import dev.infusion.feature.vertical.media.PlayableEpisodeRow
import dev.infusion.feature.vertical.model.EpisodeRowUi
import dev.infusion.source.aggregation.discover.DiscoverShelf
import dev.infusion.source.aggregation.discover.ShelfState

fun CardWatchedState.hideWatchedEligibleUnit(watchKey: WatchKey?): Boolean =
    isWatched(watchKey) && !isInProgress(watchKey)

fun CardWatchedState.hideWatchedEligible(preview: MediaPreview): Boolean =
    hideWatchedEligibleUnit(preview.cardWatchedUnitOrNull())

fun MediaCardUi.cardWatchedUnitOrNull(): WatchKey? {

    if (canonicalKind != TitleKind.MOVIE) return null
    val canonicalTitleId = canonicalTitleIdOrNull() ?: return null
    return WatchKey.movie(canonicalTitleId)
}

fun PlayableEpisodeRow.cardWatchedUnitOrNull(seriesTitleId: dev.infusion.core.model.TitleId): WatchKey? =
    WatchKey.episodeOrNull(
        seriesId = seriesTitleId,
        seasonNumber = episode.seasonNumber,
        episodeNumber = episode.episodeNumber,
    )

fun EpisodeRowUi.cardWatchedUnitOrNull(seriesTitleId: dev.infusion.core.model.TitleId): WatchKey =
    WatchKey.episode(
        seriesId = seriesTitleId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
    )

fun <T> withoutHiddenWatched(
    items: List<T>,
    hideWatchedTitles: Boolean,
    watchedState: CardWatchedState,
    unitOf: (T) -> WatchKey?,
): List<T> =
    if (!hideWatchedTitles) items
    else items.filterNot { watchedState.hideWatchedEligibleUnit(unitOf(it)) }

enum class HideWatchedCollection {

    BROWSE,

    SEARCH,

    CONTINUE_WATCHING,

    WATCHLIST_RECENTLY_WATCHED,
}

fun HideWatchedCollection.isExemptFromHideWatched(): Boolean = when (this) {
    HideWatchedCollection.BROWSE -> false
    HideWatchedCollection.SEARCH,
    HideWatchedCollection.CONTINUE_WATCHING,
    HideWatchedCollection.WATCHLIST_RECENTLY_WATCHED,
    -> true
}

fun <T> withoutHiddenWatchedIn(
    collection: HideWatchedCollection,
    items: List<T>,
    hideWatchedTitles: Boolean,
    watchedState: CardWatchedState,
    unitOf: (T) -> WatchKey?,
): List<T> =
    if (collection.isExemptFromHideWatched()) items
    else withoutHiddenWatched(items, hideWatchedTitles, watchedState, unitOf)

fun <T> withoutHiddenWatchedPreviews(
    items: List<T>,
    hideWatchedTitles: Boolean,
    watchedState: CardWatchedState,
    preview: (T) -> MediaPreview,
): List<T> = withoutHiddenWatched(items, hideWatchedTitles, watchedState) {
    preview(it).cardWatchedUnitOrNull()
}

fun hideWatchedShelf(
    shelf: DiscoverShelf,
    hideWatchedTitles: Boolean,
    watchedState: CardWatchedState,
): DiscoverShelf {
    if (!hideWatchedTitles) return shelf
    val state = shelf.state
    if (state !is ShelfState.Loaded) return shelf
    val kept = withoutHiddenWatchedPreviews(state.items, true, watchedState) { it.preview }
    return shelf.copy(state = state.copy(items = kept))
}

fun hideWatchedMediaCards(
    cards: List<MediaCardUi>,
    hideWatchedTitles: Boolean,
    watchedState: CardWatchedState,
): List<MediaCardUi> = withoutHiddenWatched(cards, hideWatchedTitles, watchedState) {
    it.cardWatchedUnitOrNull()
}

fun hideWatchedEpisodeRows(
    rows: List<PlayableEpisodeRow>,
    hideWatchedTitles: Boolean,
    watchedState: CardWatchedState,
    seriesTitleId: dev.infusion.core.model.TitleId,
): List<PlayableEpisodeRow> =
    withoutHiddenWatched(rows, hideWatchedTitles, watchedState) {
        it.cardWatchedUnitOrNull(seriesTitleId)
    }

fun hideWatchedPersonCredits(
    credits: List<dev.infusion.feature.vertical.media.PersonCreditUi>,
    hideWatchedTitles: Boolean,
    watchedState: CardWatchedState,
): List<dev.infusion.feature.vertical.media.PersonCreditUi> =
    withoutHiddenWatched(credits, hideWatchedTitles, watchedState) { it.card.cardWatchedUnitOrNull() }

fun withoutHiddenWatchedJellyfinItems(
    items: List<dev.infusion.feature.vertical.jellyfin.JellyfinTabRowItem>,
    hideWatchedTitles: Boolean,
    watchedState: CardWatchedState,
): List<dev.infusion.feature.vertical.jellyfin.JellyfinTabRowItem> =
    withoutHiddenWatchedPreviews(items, hideWatchedTitles, watchedState) { it.preview }

@androidx.compose.runtime.Composable
fun hideWatchedJellyfinItems(
    items: List<dev.infusion.feature.vertical.jellyfin.JellyfinTabRowItem>,
): List<dev.infusion.feature.vertical.jellyfin.JellyfinTabRowItem> =
    withoutHiddenWatchedJellyfinItems(
        items = items,
        hideWatchedTitles = LocalContentCardPresentation.current.hideWatchedTitles,
        watchedState = LocalCardWatchedState.current,
    )

fun hideWatchedHierarchyEpisodeRows(
    rows: List<EpisodeRowUi>,
    hideWatchedTitles: Boolean,
    watchedState: CardWatchedState,
    seriesTitleId: dev.infusion.core.model.TitleId,
): List<EpisodeRowUi> =
    withoutHiddenWatched(rows, hideWatchedTitles, watchedState) {
        it.cardWatchedUnitOrNull(seriesTitleId)
    }

fun hideWatchedJellyfinRows(
    state: JellyfinTabUiState,
    hideWatchedTitles: Boolean,
    watchedState: CardWatchedState,
): JellyfinTabUiState {
    if (!hideWatchedTitles) return state
    val rows = state.rows.mapNotNull { row -> filteredJellyfinRow(row, watchedState) }
    return state.copy(
        rows = rows,
        loading = rows.any { it.state is JellyfinRowState.Loading },
        initialFocusKey = jellyfinEntryFocusKey(rows),
    )
}

private fun filteredJellyfinRow(
    row: JellyfinTabRow,
    watchedState: CardWatchedState,
): JellyfinTabRow? {

    if (row.kind == JellyfinRowKind.CONTINUE_WATCHING) return row
    val loaded = row.state as? JellyfinRowState.Loaded ?: return row
    val kept = withoutHiddenWatchedPreviews(loaded.items, true, watchedState) { it.preview }
    if (kept.isEmpty()) return null
    return row.copy(state = JellyfinRowState.Loaded(kept))
}

private fun jellyfinEntryFocusKey(rows: List<JellyfinTabRow>): String? {
    for (row in rows) {
        when (val rowState = row.state) {
            JellyfinRowState.Loading -> break
            is JellyfinRowState.Failed -> continue
            is JellyfinRowState.Loaded ->
                rowState.items.firstOrNull()?.key?.let { return it }
        }
    }
    return null
}
