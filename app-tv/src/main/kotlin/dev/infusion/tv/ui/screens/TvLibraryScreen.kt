package dev.infusion.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.infusion.tv.ui.theme.InfusionTvButton
import dev.infusion.tv.ui.theme.InfusionTvTextField
import androidx.tv.material3.Card
import androidx.tv.material3.Text
import dev.infusion.tv.ui.presentation.MediaCardArtwork
import dev.infusion.core.domain.catalog.CatalogEntry
import dev.infusion.core.domain.catalog.CatalogPage
import dev.infusion.core.domain.catalog.toMediaPreview
import dev.infusion.core.model.MediaPreview
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import dev.infusion.tv.ui.presentation.LocalCardWatchedState
import dev.infusion.tv.ui.presentation.LocalContentCardPresentation
import dev.infusion.tv.ui.presentation.artworkTargetPx
import dev.infusion.tv.ui.presentation.withoutHiddenWatchedPreviews
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import dev.infusion.tv.ui.theme.infusionTvCardBorder
import dev.infusion.tv.ui.theme.infusionTvCardColors
import dev.infusion.tv.ui.theme.infusionTvCardShape

@Composable
fun TvLibraryScreen(
    libraryNativeId: String?,
    browse: suspend (parentNativeId: String?, startIndex: Int, limit: Int) -> CatalogPage?,
    search: suspend (searchTerm: String, startIndex: Int, limit: Int) -> CatalogPage?,
    children: suspend (parentNativeId: String, startIndex: Int, limit: Int) -> CatalogPage?,

    onOpenDetails: (TitleId, dev.infusion.core.model.TitleKind, dev.infusion.core.model.MediaPreview) -> Unit,
    onBack: () -> Unit,
) {
    var searchTerm by remember { mutableStateOf("") }
    var query by remember { mutableStateOf<String?>(null) }
    var rows by remember { mutableStateOf<List<LibraryBrowseRow>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }

    var parentNativeId by remember { mutableStateOf(libraryNativeId) }

    LaunchedEffect(query, parentNativeId) {
        loading = true
        val page = if (query != null) {
            search(query!!, 0, 100)
        } else {
            browse(parentNativeId, 0, 100)
        }

        rows = page?.entries.orEmpty().map { entry -> LibraryBrowseRow.of(entry) }
        total = page?.totalCount ?: 0
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text("Library", style = InfusionTvScaleCalibration.Type.Headline)
            InfusionTvTextField(
                value = searchTerm,
                onValueChange = { searchTerm = it },
                modifier = Modifier.weight(1f),
            )
            InfusionTvButton(onClick = { query = searchTerm.trim().ifEmpty { null } }) {
                Text("Search")
            }
            if (parentNativeId != null) {
                InfusionTvButton(
                    onClick = {
                        parentNativeId = libraryNativeId
                        query = null
                        searchTerm = ""
                    },
                ) {
                    Text("Library")
                }
            }
            InfusionTvButton(onClick = onBack) {
                Text("Back")
            }
        }
        Text(
            if (loading) "Loading…" else "$total items",
            style = InfusionTvScaleCalibration.Type.Body,
        )

        val cardPresentation = LocalContentCardPresentation.current
        val cardWatchedState = LocalCardWatchedState.current
        val visibleRows = if (query != null) {
            rows
        } else {
            withoutHiddenWatchedPreviews(
                items = rows,
                hideWatchedTitles = cardPresentation.hideWatchedTitles,
                watchedState = cardWatchedState,
            ) { it.preview }
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(visibleRows, key = { it.nativeItemId }) { row ->
                TvBrowseRow(
                    row = row,
                    onClick = {
                        if (row.isFolder && row.kind != TitleKind.SERIES) {

                            parentNativeId = row.nativeItemId
                            query = null
                        } else {
                            row.titleId?.let { onOpenDetails(it, row.kind, row.preview) }
                        }
                    },
                )
            }
        }
    }
}

private data class LibraryBrowseRow(
    val nativeItemId: String,
    val titleId: TitleId?,
    val kind: TitleKind,
    val isFolder: Boolean,
    val preview: MediaPreview,
) {
    companion object {
        fun of(entry: CatalogEntry): LibraryBrowseRow = LibraryBrowseRow(
            nativeItemId = entry.nativeItemId,
            titleId = entry.titleId,
            kind = entry.kind,
            isFolder = entry.isFolder,
            preview = entry.toMediaPreview(),
        )
    }
}

@Composable
private fun TvBrowseRow(
    row: LibraryBrowseRow,
    onClick: () -> Unit,
) {

    val rowArtworkSize = DpSize(72.dp, 72.dp)
    val rowArtworkTarget = artworkTargetPx(rowArtworkSize)
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),

        colors = infusionTvCardColors(),
        shape = infusionTvCardShape(),
        border = infusionTvCardBorder(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            MediaCardArtwork(
                preview = row.preview,
                targetSize = rowArtworkTarget,
                contentDescription = null,
                modifier = Modifier.size(rowArtworkSize),
            )
            Column {
                Text(row.preview.displayTitle, style = InfusionTvScaleCalibration.Type.Emphasis)
                Text(
                    buildString {
                        row.preview.kind?.let { append(it.name) }
                        row.preview.year?.let { append(" · $it") }
                        if (row.isFolder) append(" · folder")
                    },
                    style = InfusionTvScaleCalibration.Type.Body,
                    color = Color.Gray,
                )
            }
        }
    }
}
