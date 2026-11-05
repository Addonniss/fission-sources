@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package dev.infusion.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.infusion.core.artworkimage.ArtworkImage
import dev.infusion.core.artworkimage.ArtworkRequest
import dev.infusion.core.domain.collections.CollectionTileShape
import dev.infusion.core.model.ArtworkPolicy
import dev.infusion.core.model.MediaPreview
import dev.infusion.feature.vertical.collections.CollectionGroupRow
import dev.infusion.feature.vertical.collections.CollectionPageId
import dev.infusion.feature.vertical.collections.CollectionTileCard
import dev.infusion.tv.ui.focus.FocusKeyRegistry
import dev.infusion.tv.ui.presentation.ContentCardShape
import dev.infusion.tv.ui.presentation.InfusionContentRow
import dev.infusion.tv.ui.presentation.InfusionMediaCard
import dev.infusion.tv.ui.presentation.LocalContentCardPresentation
import dev.infusion.tv.ui.presentation.contentCardArtworkTarget
import dev.infusion.tv.ui.presentation.tvBrowseNonRailInset
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration

internal fun collectionTileFocusKey(widgetId: String, groupKey: String, tileKey: String): String =
    "placement:$widgetId:group:$groupKey:tile:$tileKey"

internal fun collectionTilePageId(row: CollectionGroupRow, tile: CollectionTileCard): String =
    CollectionPageId.of(row.packId, row.groupKey, tile.tileKey)

@Composable
internal fun CollectionGroupTileRow(
    row: CollectionGroupRow,
    requesters: FocusKeyRegistry,
    onOpenTile: (pageId: String, tileFocusKey: String) -> Unit,

    onFocusedKeyChanged: (String) -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            row.title,
            style = InfusionTvScaleCalibration.Type.Emphasis,
            modifier = Modifier.tvBrowseNonRailInset(),
        )
        if (row.tiles.isEmpty()) {
            Text(
                "This collection group has no tiles.",
                style = InfusionTvScaleCalibration.Type.Body,
                modifier = Modifier.tvBrowseNonRailInset(),
            )
            return@Column
        }
        InfusionContentRow(
            items = row.tiles,
            key = { collectionTileFocusKey(row.widgetId, row.groupKey, it.tileKey) },
        ) { tile ->
            val focusKey = collectionTileFocusKey(row.widgetId, row.groupKey, tile.tileKey)
            CollectionTileCardView(
                tile = tile,
                focusKey = focusKey,
                requesters = requesters,
                onFocusedKeyChanged = onFocusedKeyChanged,
                onOpen = { onOpenTile(collectionTilePageId(row, tile), focusKey) },
            )
        }
    }
}

@Composable
private fun CollectionTileCardView(
    tile: CollectionTileCard,
    focusKey: String,
    requesters: FocusKeyRegistry,
    onFocusedKeyChanged: (String) -> Unit,
    onOpen: () -> Unit,
) {
    val presentation = LocalContentCardPresentation.current
    val hideTitle = tile.hideTitle && tile.hasArtwork
    val effectivePresentation = if (hideTitle) presentation.copy(hideTitles = true) else presentation
    val shape = if (tile.shape == CollectionTileShape.POSTER) {
        ContentCardShape.PORTRAIT
    } else {
        ContentCardShape.LANDSCAPE
    }
    CompositionLocalProvider(LocalContentCardPresentation provides effectivePresentation) {
        InfusionMediaCard(
            onClick = onOpen,
            focusKey = focusKey,
            kind = null,
            preview = MediaPreview(
                titleId = null,
                kind = null,
                displayTitle = tile.title,
                year = null,
                subtitle = null,
                artwork = null,
                progressFraction = null,
            ),
            requesters = requesters,

            onFocusedKeyChanged = onFocusedKeyChanged,
            shapeOverride = shape,
            artworkLabel = if (hideTitle) tile.title else null,
        ) {
            val locator = tile.restingArtworkLocator
            if (locator != null) {

                val target = contentCardArtworkTarget(null, shapeOverride = shape)
                ArtworkImage(
                    request = ArtworkRequest(
                        locator = locator,
                        policy = if (shape == ContentCardShape.PORTRAIT) ArtworkPolicy.POSTER else ArtworkPolicy.BACKDROP,
                        targetWidthPx = target.width,
                        targetHeightPx = target.height,

                        retainsPreviousSuccess = false,
                    ),
                    contentDescription = tile.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = presentation.artworkContentScaleForShape(shape),
                )
            }

        }
    }
}
