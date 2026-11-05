package dev.infusion.source.aggregation.match

import dev.infusion.core.model.SourceId
import dev.infusion.source.stremio.policy.StremioContentType
import dev.infusion.source.stremio.wire.StremioMeta
import dev.infusion.source.stremio.wire.StremioMetaPreview

data class AddonMetaItem(
    val catalogType: StremioContentType,
    val catalogId: String,
    val nativeItemId: String,
    val displayTitle: String,
    val year: Int?,
    val releaseInfo: String?,
    val genres: List<String>,
    val description: String?,
    val imdbRating: String?,
    val addonSourceId: SourceId? = null,
)

object AddonMetaItemAdapter {

    fun fromPreview(
        preview: StremioMetaPreview,
        catalogType: StremioContentType,
        catalogId: String,
        addonSourceId: SourceId? = null,
    ): AddonMetaItem =
        AddonMetaItem(
            catalogType = catalogType,
            catalogId = catalogId,
            nativeItemId = preview.id,
            displayTitle = preview.name,
            year = parseYear(preview.releaseInfo),
            releaseInfo = preview.releaseInfo,
            genres = preview.genres,
            description = preview.description,
            imdbRating = preview.imdbRating,
            addonSourceId = addonSourceId,
        )

    fun fromMeta(
        meta: StremioMeta,
        catalogType: StremioContentType,
        catalogId: String,
        addonSourceId: SourceId? = null,
    ): AddonMetaItem =
        AddonMetaItem(
            catalogType = catalogType,
            catalogId = catalogId,
            nativeItemId = meta.id,
            displayTitle = meta.name,
            year = parseYear(meta.releaseInfo),
            releaseInfo = meta.releaseInfo,
            genres = meta.genres,
            description = meta.description,
            imdbRating = meta.imdbRating,
            addonSourceId = addonSourceId,
        )

    fun parseYear(releaseInfo: String?): Int? {
        val raw = releaseInfo?.trim()?.take(4) ?: return null
        val year = raw.toIntOrNull() ?: return null
        return year.takeIf { it in 1900..2100 }
    }
}
