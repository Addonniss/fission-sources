package dev.infusion.feature.vertical.home

import dev.infusion.core.domain.anchor.CanonicalRootTitleStore
import dev.infusion.core.domain.metadata.MediaIdentityResolver
import dev.infusion.core.domain.metadata.MediaIdentityStore
import dev.infusion.core.domain.publist.PublicListContentSource
import dev.infusion.core.domain.publist.PublicListItem
import dev.infusion.core.domain.publist.PublicListProvider
import dev.infusion.core.domain.publist.PublicListRef
import dev.infusion.core.domain.publist.PublicListResult
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import dev.infusion.core.model.metadata.ExternalIds
import dev.infusion.core.model.metadata.MediaIdentity
import dev.infusion.core.model.metadata.MediaType
import dev.infusion.feature.vertical.browse.SourceCardFact
import dev.infusion.feature.vertical.browse.SourceCardIdentityAnchor
import dev.infusion.feature.vertical.browse.SourceRowCards
import dev.infusion.feature.vertical.browse.SourceRowIdentity
import dev.infusion.feature.vertical.browse.mapSourceRowFacts
import dev.infusion.source.aggregation.discover.DiscoverShelf
import dev.infusion.source.aggregation.discover.ShelfState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

interface PublicListWidgetRowSource {

    suspend fun shelf(
        rowKey: String,
        ref: PublicListRef,
        displayTitle: String,
    ): PublicListShelfOutcome

    suspend fun page(
        rowKey: String,
        ref: PublicListRef,
        displayTitle: String,
    ): PublicListShelfOutcome

    fun enrich(
        rowKey: String,
        ref: PublicListRef,
        displayTitle: String,
        shelf: DiscoverShelf,
    ): Flow<DiscoverShelf> = emptyFlow()
}

sealed interface PublicListShelfOutcome {

    data class Loaded(val shelf: DiscoverShelf) : PublicListShelfOutcome

    data class Failed(val reason: String) : PublicListShelfOutcome
}

class PublicListWidgetResolver(
    private val sources: Map<String, PublicListContentSource>,
    private val cards: SourceRowCards,
    private val identityResolver: MediaIdentityResolver,

    private val rootTitleStore: CanonicalRootTitleStore? = null,
    private val identityStore: MediaIdentityStore? = null,
) : PublicListWidgetRowSource {

    private val cursors = LinkedHashMap<String, String>()

    private val factsByRow = HashMap<String, List<SourceCardFact>>()
    private val lock = Any()

    private val identityAnchor = SourceCardIdentityAnchor(
        rootTitleStore = rootTitleStore,
        identityStore = identityStore,
    )

    override suspend fun shelf(
        rowKey: String,
        ref: PublicListRef,
        displayTitle: String,
    ): PublicListShelfOutcome {
        synchronized(lock) {
            cursors.remove(rowKey)
            factsByRow.remove(rowKey)
        }
        return load(rowKey, ref, displayTitle, cursor = null)
    }

    override suspend fun page(
        rowKey: String,
        ref: PublicListRef,
        displayTitle: String,
    ): PublicListShelfOutcome {
        val cursor = synchronized(lock) { cursors[rowKey] } ?: return PublicListShelfOutcome.Failed(NO_MORE_PAGES)
        return load(rowKey, ref, displayTitle, cursor = cursor)
    }

    override fun enrich(
        rowKey: String,
        ref: PublicListRef,
        displayTitle: String,
        shelf: DiscoverShelf,
    ): Flow<DiscoverShelf> {
        val facts = synchronized(lock) { factsByRow[rowKey] } ?: return emptyFlow()
        val loaded = shelf.state as? ShelfState.Loaded ?: return emptyFlow()
        return cards.enrich(facts = facts, items = loaded.items)
            .map { updated -> shelf.copy(state = loaded.copy(items = updated)) }
    }

    private suspend fun load(
        rowKey: String,
        ref: PublicListRef,
        displayTitle: String,
        cursor: String?,
    ): PublicListShelfOutcome {
        val source = sources[ref.provider.id]
            ?: return PublicListShelfOutcome.Failed(UNSUPPORTED_PROVIDER)
        return when (val result = source.page(ref, cursor)) {
            is PublicListResult.Success -> {

                val facts = result.value.items.mapSourceRowFacts(
                    identityResolver = identityResolver,
                    identityAnchor = identityAnchor,
                    describe = ::toItem,
                )
                val items = cards.cards(rowKey, "public:${ref.provider.id}:${ref.listId}", facts)
                synchronized(lock) {
                    factsByRow[rowKey] = if (cursor == null) {
                        facts
                    } else {
                        factsByRow[rowKey].orEmpty() + facts
                    }
                    val next = result.value.nextCursor
                    if (result.value.hasMore && next != null) cursors[rowKey] = next else cursors.remove(rowKey)
                }
                PublicListShelfOutcome.Loaded(
                    DiscoverShelf(
                        key = rowKey,
                        title = displayTitle,
                        sourceLabel = ref.provider.displayName,
                        state = ShelfState.Loaded(items = items, hasMore = result.value.hasMore),
                    ),
                )
            }
            is PublicListResult.Unsupported -> PublicListShelfOutcome.Failed(result.reason)
            is PublicListResult.Failed -> PublicListShelfOutcome.Failed(result.reason)
        }
    }

    private fun toItem(item: PublicListItem): SourceRowIdentity? {
        val externalIds = ExternalIds(
            tmdbId = item.tmdbId,
            imdbId = item.imdbId,
            tvdbId = item.tvdbId,
        )
        if (externalIds.isEmpty) return null
        val type = if (item.kind == TitleKind.SERIES) MediaType.SHOW else MediaType.MOVIE
        return SourceRowIdentity(
            providerItemId = item.providerItemId,
            identity = MediaIdentity(type = type, externalIds = externalIds),
            kind = item.kind,
            title = item.title,
            year = item.year,
            artwork = null,
        )
    }

    companion object {
        internal const val UNSUPPORTED_PROVIDER = "public_list_provider_unsupported"
        internal const val NO_MORE_PAGES = "public_list_no_more_pages"
    }
}
