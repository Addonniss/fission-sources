package dev.infusion.feature.vertical.browse

import dev.infusion.core.domain.metadata.MetadataLocale
import dev.infusion.core.domain.metadata.MetadataRepository
import dev.infusion.core.model.ArtworkPolicy
import dev.infusion.core.model.MediaArtworkRef
import dev.infusion.core.model.MediaPreview
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import dev.infusion.core.model.metadata.MediaArtwork
import dev.infusion.core.model.metadata.MediaIdentity
import dev.infusion.source.aggregation.discover.DiscoverItem
import dev.infusion.source.aggregation.discover.providerRowDiscoverItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class SourceRowCards(
    private val metadata: MetadataRepository,
    private val locale: () -> MetadataLocale = { MetadataLocale.Default },

    private val maxConcurrentLookups: Int = DEFAULT_MAX_CONCURRENT_LOOKUPS,
) {

    fun cards(
        rowKey: String,
        catalogId: String,
        facts: List<SourceCardFact>,
    ): List<DiscoverItem> = facts.map { fact -> card(rowKey, catalogId, fact) }

    fun enrich(
        facts: List<SourceCardFact>,
        items: List<DiscoverItem>,
    ): Flow<List<DiscoverItem>> = flow {
        val factById = facts.associateBy { it.providerItemId }
        val pending = items.mapNotNull { item ->
            val fact = factById[item.item.nativeItemId] ?: return@mapNotNull null
            fact.takeIf { item.preview.artwork == null && item.titleId != null }
        }
        if (pending.isEmpty()) return@flow
        var current = items
        for (batch in pending.chunked(maxConcurrentLookups.coerceAtLeast(1))) {
            val resolved = coroutineScope {
                batch.map { fact -> async { fact to enrichmentFor(fact) } }.awaitAll()
            }
            val updates = HashMap<String, SourceCardEnrichment>(resolved.size)
            resolved.forEach { (fact, enrichment) ->
                if (enrichment != null) updates[fact.providerItemId] = enrichment
            }
            if (updates.isEmpty()) continue
            var changed = false
            current = current.map { item ->
                val enrichment = updates[item.item.nativeItemId] ?: return@map item
                val preview = item.preview
                val next = preview.copy(
                    displayTitle = enrichment.title?.takeIf { it.isNotBlank() } ?: preview.displayTitle,
                    year = enrichment.year ?: preview.year,
                    artwork = enrichment.artwork ?: preview.artwork,
                )
                if (next == preview) item else item.copy(preview = next).also { changed = true }
            }
            if (changed) emit(current)
        }
    }

    private fun card(rowKey: String, catalogId: String, fact: SourceCardFact): DiscoverItem =
        providerRowDiscoverItem(
            key = "$rowKey:item:${fact.providerItemId}",
            titleId = fact.titleId,
            kind = fact.kind,
            displayTitle = fact.title,
            year = fact.year,
            providerCatalogId = catalogId,
            providerItemId = fact.providerItemId,
            preview = MediaPreview(
                titleId = fact.titleId,
                kind = fact.kind,
                displayTitle = fact.title,
                year = fact.year,
                subtitle = null,
                artwork = fact.artwork,
                progressFraction = null,
            ),
        )

    private suspend fun enrichmentFor(fact: SourceCardFact): SourceCardEnrichment? {
        val details = runCatching { metadata.details(fact.identity, locale()) }.getOrNull()
            ?: return null
        return SourceCardEnrichment(
            title = details.title,
            year = details.year,
            artwork = artworkOf(details.artwork),
        )
    }

    private fun artworkOf(artwork: MediaArtwork?): MediaArtworkRef? {
        val poster = artwork?.poster
        val backdrop = artwork?.backdrop
        return when {
            poster != null -> MediaArtworkRef.metadata(
                provider = poster.provider,
                path = poster.path,
                policy = ArtworkPolicy.POSTER,
                landscapePath = backdrop?.path,
            )
            backdrop != null -> MediaArtworkRef.metadata(
                provider = backdrop.provider,
                path = backdrop.path,
                policy = ArtworkPolicy.BACKDROP,
            )
            else -> null
        }
    }

    private companion object {

        const val DEFAULT_MAX_CONCURRENT_LOOKUPS = 2
    }
}

data class SourceCardFact(
    val providerItemId: String,
    val identity: MediaIdentity,
    val titleId: TitleId,
    val kind: TitleKind?,
    val title: String,
    val year: Int?,
    val artwork: MediaArtworkRef?,
)

private data class SourceCardEnrichment(
    val title: String?,
    val year: Int?,
    val artwork: MediaArtworkRef?,
)
