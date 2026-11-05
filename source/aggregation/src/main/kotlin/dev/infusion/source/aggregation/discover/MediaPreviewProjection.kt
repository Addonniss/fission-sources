package dev.infusion.source.aggregation.discover

import dev.infusion.core.model.MediaArtworkRef
import dev.infusion.core.model.MediaPreview
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import dev.infusion.source.aggregation.match.AddonMetaItem

fun addonMediaPreview(
    item: AddonMetaItem,
    titleId: TitleId?,
    kind: TitleKind?,
    addonId: String,
    posterAvailable: Boolean,
    landscapeAvailable: Boolean,
    posterIsLandscape: Boolean = false,
): MediaPreview = MediaPreview(
    titleId = titleId,
    kind = kind,
    displayTitle = item.displayTitle,
    year = item.year,

    subtitle = null,
    artwork = addonArtworkRef(
        addonId = addonId,
        nativeItemId = item.nativeItemId,
        posterAvailable = posterAvailable,
        landscapeAvailable = landscapeAvailable,
        posterIsLandscape = posterIsLandscape,
    ),

    progressFraction = null,
)

fun addonArtworkRef(
    addonId: String,
    nativeItemId: String,
    posterAvailable: Boolean,
    landscapeAvailable: Boolean,
    posterIsLandscape: Boolean = false,
): MediaArtworkRef? = when {

    landscapeAvailable && !posterAvailable ->
        MediaArtworkRef.addonLandscape(addonId = addonId, nativeItemId = nativeItemId)

    posterAvailable && posterIsLandscape && !landscapeAvailable ->
        MediaArtworkRef.addonLandscape(addonId = addonId, nativeItemId = nativeItemId)
    posterAvailable ->
        MediaArtworkRef.addon(
            addonId = addonId,
            nativeItemId = nativeItemId,
            landscapeAvailable = landscapeAvailable,
        )
    else -> null
}
