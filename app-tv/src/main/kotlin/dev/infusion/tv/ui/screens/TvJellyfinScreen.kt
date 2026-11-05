package dev.infusion.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.infusion.tv.ui.presentation.MediaCardArtwork
import dev.infusion.tv.ui.presentation.libraryListRowArtwork
import dev.infusion.feature.vertical.music.musicLibraryTarget
import dev.infusion.core.model.MediaPreview
import dev.infusion.feature.vertical.jellyfin.JellyfinRowKind
import dev.infusion.feature.vertical.jellyfin.JellyfinRowState
import dev.infusion.feature.vertical.jellyfin.JellyfinTabRow
import dev.infusion.feature.vertical.jellyfin.JellyfinTabRowItem
import dev.infusion.feature.vertical.jellyfin.JellyfinTabStateHolder
import dev.infusion.tv.ui.focus.rememberFocusRequesters
import dev.infusion.tv.ui.presentation.ContentCardShape
import dev.infusion.tv.ui.presentation.InfusionContentRow
import dev.infusion.tv.ui.presentation.InfusionMediaCard
import dev.infusion.tv.ui.presentation.LocalCardWatchedState
import dev.infusion.tv.ui.presentation.LocalContentCardPresentation
import dev.infusion.tv.ui.presentation.TvBrowseColumn
import dev.infusion.tv.ui.presentation.contentCardArtwork
import dev.infusion.tv.ui.presentation.contentCardArtworkKey
import dev.infusion.tv.ui.presentation.contentCardArtworkTarget
import dev.infusion.tv.ui.presentation.hideWatchedJellyfinRows
import dev.infusion.tv.ui.presentation.tvBrowseNonRailInset
import dev.infusion.tv.ui.shell.InfusionTvShell
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration

@Composable
fun TvJellyfinScreen(
    stateHolder: JellyfinTabStateHolder,
    jellyfinConnected: Boolean,
    model: TvTopNavModel,
    onNavigate: (TopLevelDestination) -> Unit,
    onOpenItem: (JellyfinTabRowItem) -> Unit,
    onExit: () -> Unit,
) {
    val rawState by stateHolder.state.collectAsStateWithLifecycle()

    val presentation = LocalContentCardPresentation.current
    val state = hideWatchedJellyfinRows(
        state = rawState,
        hideWatchedTitles = presentation.hideWatchedTitles,
        watchedState = LocalCardWatchedState.current,
    )
    val requesters = rememberFocusRequesters()
    val chrome = rememberTopLevelPageChrome(TopLevelDestination.JELLYFIN, model)

    LaunchedEffect(stateHolder) {
        if (stateHolder.hasSettled) stateHolder.refresh()
    }

    val topLevelBoundaryRowKind = state.rows.firstOrNull { row ->
        val rowState = row.state
        rowState is JellyfinRowState.Loaded && rowState.items.isNotEmpty()
    }?.kind

    val arrivingRowKeys = state.rows
        .filter { it.state is JellyfinRowState.Loading }
        .mapTo(mutableSetOf<Any>()) { row -> jellyfinRowKey(row) }

    InfusionTvShell(
        page = TopLevelDestination.JELLYFIN,
        jellyfinConnected = jellyfinConnected,
        model = model,
        requesters = requesters,
        chrome = chrome,
        entryDefaultKey = state.initialFocusKey,
        onNavigateTo = onNavigate,
        onExit = onExit,
    ) {
        TvBrowseColumn(
            rows = state.rows,

            rowKey = { row -> jellyfinRowKey(row) },
            rowItemKeys = { row ->
                (row.state as? JellyfinRowState.Loaded)?.items?.map { it.key }.orEmpty()
            },
            modifier = Modifier.fillMaxSize(),
            listTag = "jellyfin-rows",

            sharedRowRules = true,

            arrivingRowKeys = arrivingRowKeys,

            originRowArrivalIndeterminate = state.loading,
        ) { row ->
            JellyfinRowSection(
                row = row,
                topLevelContentBoundary = row.kind == topLevelBoundaryRowKind,
                requesters = requesters,
                onOpenItem = onOpenItem,
                onFocusedKeyChanged = { chrome.onContentKeyFocused(it) },
            )
        }
    }
}

internal fun jellyfinRowKey(row: JellyfinTabRow): String = "jellyfin:row:${row.kind.name}"

@Composable
private fun JellyfinRowSection(
    row: JellyfinTabRow,
    topLevelContentBoundary: Boolean,
    requesters: MutableMap<String, androidx.compose.ui.focus.FocusRequester>,
    onOpenItem: (JellyfinTabRowItem) -> Unit,
    onFocusedKeyChanged: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

        Text(
            row.title,
            style = InfusionTvScaleCalibration.Type.Emphasis,
            modifier = Modifier.tvBrowseNonRailInset(),
        )
        when (val rowState = row.state) {
            is JellyfinRowState.Loading -> Text(
                "Loading…",
                style = InfusionTvScaleCalibration.Type.Body,
                modifier = Modifier.tvBrowseNonRailInset(),
            )
            is JellyfinRowState.Failed -> Text(
                "Could not load this row: ${rowState.message}",
                style = InfusionTvScaleCalibration.Type.Body,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.tvBrowseNonRailInset(),
            )
            is JellyfinRowState.Loaded -> InfusionContentRow(
                items = rowState.items,
                key = { it.key },
            ) { item ->
                JellyfinRowCard(

                    preview = jellyfinRowCardPreview(item),
                    focusKey = item.key,
                    badge = if (item.isFolder) "Library" else null,

                    cardShape = jellyfinRowCardShape(row.kind, item),
                    topLevelContentBoundary = topLevelContentBoundary,
                    requesters = requesters,
                    onClick = { onOpenItem(item) },
                    onFocusedKeyChanged = onFocusedKeyChanged,
                )
            }
        }
    }
}

internal fun myMediaCardShape(
    rowKind: JellyfinRowKind,
    item: JellyfinTabRowItem,
): ContentCardShape? {
    if (rowKind != JellyfinRowKind.MY_MEDIA) return null

    if (!item.isFolder) return null
    return ContentCardShape.LANDSCAPE
}

internal fun jellyfinRowCardShape(
    rowKind: JellyfinRowKind,
    item: JellyfinTabRowItem,
): ContentCardShape? {
    if (item.musicLibraryTarget() != null) return ContentCardShape.SQUARE
    return myMediaCardShape(rowKind, item)
}

internal fun jellyfinRowCardPreview(item: JellyfinTabRowItem): MediaPreview {
    val musicArtwork = if (item.musicLibraryTarget() != null) libraryListRowArtwork(item) else null
    val own = item.preview
    if (musicArtwork == null || musicArtwork == own.artwork) return own
    return own.copy(artwork = musicArtwork)
}

@Composable
internal fun JellyfinRowCard(
    preview: MediaPreview,
    focusKey: String,
    badge: String? = null,
    cardShape: ContentCardShape? = null,
    topLevelContentBoundary: Boolean = false,

    categoryTitle: String? = null,
    requesters: MutableMap<String, androidx.compose.ui.focus.FocusRequester>,
    onClick: () -> Unit,
    onFocusedKeyChanged: (String) -> Unit,
) {

    val artworkTarget = contentCardArtworkTarget(preview.kind, cardShape)

    InfusionMediaCard(
        onClick = onClick,
        focusKey = focusKey,
        kind = preview.kind,

        preview = preview,
        requesters = requesters,
        onFocusedKeyChanged = onFocusedKeyChanged,
        topLevelContentBoundary = topLevelContentBoundary,

        artworkTag = contentCardArtworkKey(focusKey),

        artworkLabel = badge,

        categoryTitle = categoryTitle,
        shapeOverride = cardShape,
        artwork = {

            MediaCardArtwork(
                preview = preview,
                targetSize = artworkTarget,
                contentDescription = "Poster",
                modifier = Modifier.fillMaxSize(),
            )
        },
    )
}
