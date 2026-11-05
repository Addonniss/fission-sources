package dev.infusion.source.addon.shells

import dev.infusion.core.domain.catalog.CatalogEntry
import dev.infusion.core.domain.catalog.CatalogImage
import dev.infusion.core.domain.catalog.CatalogPage
import dev.infusion.core.domain.catalog.CatalogPort
import dev.infusion.core.domain.catalog.HomeShelf
import dev.infusion.core.domain.catalog.TitleDetails
import dev.infusion.core.model.Source
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import dev.infusion.source.aggregation.details.AddonDetailsService
import dev.infusion.source.aggregation.details.EpisodeMetaTarget
import dev.infusion.source.aggregation.details.withAddonMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

class AddonMetaEnrichingCatalogPort(
    private val base: CatalogPort,
    private val detailsService: AddonDetailsService,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,

    private val episodeParentSeriesTitleId: TitleId? = null,
) : CatalogPort by base {

    override fun observeTitleDetails(titleId: TitleId): Flow<TitleDetails?> = flow {

        base.observeTitleDetails(titleId).collect { details ->
            if (details == null) {
                emit(null)
            } else {
                emit(withContext(ioDispatcher) { enrich(details) })
            }
        }
    }

    private suspend fun enrich(details: TitleDetails): TitleDetails {
        val title = details.title

        val meta = detailsService.resolve(
            titleId = title.titleId,
            kind = title.kind,
            episodeTarget = if (title.kind == TitleKind.EPISODE) {
                EpisodeMetaTarget(
                    parentSeriesTitleId = episodeParentSeriesTitleId,
                    seasonNumber = title.seasonNumber,
                    episodeNumber = title.episodeNumber,
                )
            } else {
                null
            },
            speculative = true,
        ) ?: return details
        return details.withAddonMeta(meta)
    }
}
