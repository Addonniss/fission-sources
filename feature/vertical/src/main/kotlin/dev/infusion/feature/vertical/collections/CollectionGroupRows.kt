package dev.infusion.feature.vertical.collections

import dev.infusion.core.domain.collections.CollectionEntityPage
import dev.infusion.core.domain.collections.CollectionEntityRef
import dev.infusion.core.domain.collections.CollectionPack
import dev.infusion.core.domain.collections.CollectionTile
import dev.infusion.core.domain.collections.CollectionTileShape
import dev.infusion.core.model.ArtworkLocator
import dev.infusion.feature.vertical.home.HomeWidgetRow

data class CollectionTileCard(

    val tileKey: String,
    val title: String,
    val shape: CollectionTileShape,

    val hideTitle: Boolean,

    val coverLocator: String?,
    val logoLocator: String?,
    val backdropLocator: String?,

    val focusLocator: String?,
) {

    val hasArtwork: Boolean get() = coverLocator != null || backdropLocator != null || focusLocator != null

    val focusArtworkLocator: String? get() = focusLocator ?: coverLocator ?: backdropLocator

    val restingArtworkLocator: String? get() = coverLocator ?: backdropLocator
}

data class CollectionGroupRow(
    val widgetId: String,

    val rowKey: String,
    val title: String,
    val packId: String,
    val groupKey: String,
    val tiles: List<CollectionTileCard>,
)

fun collectionGroupRows(
    widgetRows: List<HomeWidgetRow>,
    packFor: (String) -> CollectionPack?,
): List<CollectionGroupRow> = widgetRows.mapNotNull { row ->
    val ref = row.collectionGroup ?: return@mapNotNull null
    val pack = packFor(ref.packId) ?: return@mapNotNull null
    val group = pack.groups.firstOrNull { it.groupKey == ref.groupKey } ?: return@mapNotNull null
    CollectionGroupRow(
        widgetId = row.widgetId,
        rowKey = row.rowKey,
        title = row.displayTitle,
        packId = ref.packId,
        groupKey = ref.groupKey,
        tiles = group.tiles.map { tile -> tile.toTileCard(ref.packId, ref.groupKey) },
    )
}

fun CollectionTile.toTileCard(packId: String, groupKey: String): CollectionTileCard = CollectionTileCard(
    tileKey = tileKey,
    title = title,
    shape = shape,
    hideTitle = hideTitle,
    coverLocator = resolveTileSlot(packId, groupKey, tileKey, ArtworkLocator.COLLECTION_SLOT_COVER, artwork.cover),
    logoLocator = resolveTileSlot(packId, groupKey, tileKey, ArtworkLocator.COLLECTION_SLOT_LOGO, artwork.logo),
    backdropLocator = resolveTileSlot(packId, groupKey, tileKey, ArtworkLocator.COLLECTION_SLOT_BACKDROP, artwork.backdrop),

    focusLocator = artwork.focus
        ?.takeIf { artwork.focusEnabled }
        ?.let { slot -> resolveTileSlot(packId, groupKey, tileKey, ArtworkLocator.COLLECTION_SLOT_FOCUS, slot) },
)

private fun resolveTileSlot(
    packId: String,
    groupKey: String,
    tileKey: String,
    slot: String,
    raw: String?,
): String? = raw?.let { value ->
    if (ArtworkLocator.decode(value) != null) value
    else ArtworkLocator.collectionArtwork(packId, groupKey, tileKey, slot)
}

object CollectionPageId {
    private const val SEP = "\u001F"

    fun of(packId: String, groupKey: String, tileKey: String): String =
        listOf(packId, groupKey, tileKey).joinToString(SEP)

    fun decode(value: String): Triple<String, String, String>? {
        val parts = value.split(SEP)
        if (parts.size != 3) return null
        if (parts.any { it.isBlank() }) return null
        return Triple(parts[0], parts[1], parts[2])
    }
}

fun entityCollectionPageId(ref: CollectionEntityRef, title: String, logoLocator: String?): String =
    CollectionPageId.of(
        CollectionEntityPage.encodeToken(ref, title, logoLocator),
        CollectionEntityPage.GROUP_KEY,
        CollectionEntityPage.TILE_KEY,
    )
