package dev.infusion.feature.vertical.collections

import dev.infusion.core.domain.anchor.CanonicalRootTitleStore
import dev.infusion.core.domain.collections.CollectionBrowseItem
import dev.infusion.core.domain.collections.CollectionBrowseOutcome
import dev.infusion.core.domain.collections.CollectionBrowsePage
import dev.infusion.core.domain.collections.CollectionRowPageSource
import dev.infusion.core.domain.collections.CollectionSourceDefinition
import dev.infusion.core.domain.metadata.MediaIdentityResolver
import dev.infusion.core.domain.metadata.MediaIdentityStore
import dev.infusion.core.domain.publist.PublicListContentSource
import dev.infusion.core.domain.publist.PublicListProvider
import dev.infusion.core.domain.publist.PublicListRef
import dev.infusion.core.domain.publist.PublicListResult
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import dev.infusion.core.model.metadata.ExternalIds
import dev.infusion.core.model.metadata.MediaIdentity
import dev.infusion.core.model.metadata.MediaType
import dev.infusion.feature.vertical.browse.SourceCardIdentityAnchor
import dev.infusion.feature.vertical.browse.SourceCardFact
import dev.infusion.feature.vertical.browse.SourceRowCards
import dev.infusion.feature.vertical.browse.SourceRowIdentity
import dev.infusion.feature.vertical.browse.mapSourceRowFacts
import dev.infusion.source.aggregation.discover.DiscoverShelf
import dev.infusion.source.aggregation.discover.ShelfState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

interface CollectionRowSource {

    suspend fun shelf(
        rowKey: String,
        title: String,
        definition: CollectionSourceDefinition,
    ): CollectionRowShelfOutcome

    suspend fun page(
        rowKey: String,
        title: String,
        definition: CollectionSourceDefinition,
    ): CollectionRowShelfOutcome

    fun enrich(
        rowKey: String,
        title: String,
        definition: CollectionSourceDefinition,
        shelf: DiscoverShelf,
    ): Flow<DiscoverShelf> = emptyFlow()
}

sealed interface CollectionRowShelfOutcome {
    data class Loaded(val shelf: DiscoverShelf) : CollectionRowShelfOutcome
    data class Failed(val reason: String) : CollectionRowShelfOutcome
}

class CollectionRowResolver(
    private val sources: Map<String, CollectionRowPageSource>,
    private val cards: SourceRowCards,
    private val identityResolver: MediaIdentityResolver,

    private val rootTitleStore: CanonicalRootTitleStore? = null,
    private val identityStore: MediaIdentityStore? = null,
) : CollectionRowSource {

    private val cursors = LinkedHashMap<String, String>()

    private val factsByRow = HashMap<String, List<SourceCardFact>>()
    private val lock = Any()

    override suspend fun shelf(
        rowKey: String,
        title: String,
        definition: CollectionSourceDefinition,
    ): CollectionRowShelfOutcome {
        synchronized(lock) {
            cursors.remove(rowKey)
            factsByRow.remove(rowKey)
        }
        return load(rowKey, title, definition, cursor = null)
    }

    override suspend fun page(
        rowKey: String,
        title: String,
        definition: CollectionSourceDefinition,
    ): CollectionRowShelfOutcome {
        val cursor = synchronized(lock) { cursors[rowKey] }
            ?: return CollectionRowShelfOutcome.Failed(NO_MORE_PAGES)
        return load(rowKey, title, definition, cursor = cursor)
    }

    override fun enrich(
        rowKey: String,
        title: String,
        definition: CollectionSourceDefinition,
        shelf: DiscoverShelf,
    ): Flow<DiscoverShelf> {
        val facts = synchronized(lock) { factsByRow[rowKey] } ?: return emptyFlow()
        val loaded = shelf.state as? ShelfState.Loaded ?: return emptyFlow()
        val providerId = definition.provider.trim().lowercase()
        return cards.enrich(facts = facts, items = loaded.items)
            .map { updated -> shelf.copy(state = loaded.copy(items = updated)) }
    }

    private suspend fun load(
        rowKey: String,
        title: String,
        definition: CollectionSourceDefinition,
        cursor: String?,
    ): CollectionRowShelfOutcome {
        val providerId = definition.provider.trim().lowercase()
        val source = sources[providerId]
            ?: return CollectionRowShelfOutcome.Failed(UNSUPPORTED_PROVIDER)
        return when (val outcome = source.page(definition, cursor, rowKey)) {
            is CollectionBrowseOutcome.Loaded -> {

                val facts = outcome.page.items.mapSourceRowFacts(
                    identityResolver = identityResolver,
                    identityAnchor = identityAnchor,
                    describe = ::toItem,
                )
                val items = cards.cards(rowKey, "collection:$providerId", facts)
                synchronized(lock) {
                    factsByRow[rowKey] = if (cursor == null) {
                        facts
                    } else {
                        factsByRow[rowKey].orEmpty() + facts
                    }
                    val next = outcome.page.nextCursor
                    if (outcome.page.hasMore && next != null) cursors[rowKey] = next else cursors.remove(rowKey)
                }
                CollectionRowShelfOutcome.Loaded(
                    DiscoverShelf(
                        key = rowKey,
                        title = title,
                        sourceLabel = providerLabel(providerId),
                        state = ShelfState.Loaded(items = items, hasMore = outcome.page.hasMore),
                    ),
                )
            }
            is CollectionBrowseOutcome.Unsupported -> CollectionRowShelfOutcome.Failed(outcome.reason)
            is CollectionBrowseOutcome.Failed -> CollectionRowShelfOutcome.Failed(outcome.reason)
        }
    }

    private fun toItem(item: CollectionBrowseItem): SourceRowIdentity? {
        val externalIds = ExternalIds(
            tmdbId = item.tmdbId,
            imdbId = item.imdbId,
        )
        if (externalIds.isEmpty) return null
        val type = if (item.kind == TitleKind.SERIES) MediaType.SHOW else MediaType.MOVIE
        return SourceRowIdentity(
            providerItemId = item.providerItemId,
            identity = MediaIdentity(type = type, externalIds = externalIds),
            kind = item.kind,
            title = item.title,
            year = item.year,
            artwork = item.artwork,
        )
    }

    private val identityAnchor = SourceCardIdentityAnchor(
        rootTitleStore = rootTitleStore,
        identityStore = identityStore,
    )

    companion object {
        internal const val UNSUPPORTED_PROVIDER = "collection_source_provider_unsupported"
        internal const val NO_MORE_PAGES = "collection_row_no_more_pages"

        internal fun providerLabel(providerId: String): String = when (providerId) {
            "tmdb" -> "TMDB"
            "trakt" -> "Trakt"
            "mdblist" -> "MDBList"
            "addon" -> "Add-on"
            else -> providerId.ifBlank { "Source" }
        }
    }
}

class PublicListCollectionRowSource(
    private val sources: Map<String, PublicListContentSource>,
) : CollectionRowPageSource {

    override suspend fun page(
        definition: CollectionSourceDefinition,
        cursor: String?,
        ownerKey: String?,
    ): CollectionBrowseOutcome {
        val provider = PublicListProvider.fromId(definition.provider.trim().lowercase())
            ?: return CollectionBrowseOutcome.Unsupported(UNSUPPORTED_PROVIDER)
        val listId = definition.traktListId?.takeIf { it.isNotBlank() }
            ?: return CollectionBrowseOutcome.Unsupported(MISSING_LIST_ID)
        val source = sources[provider.id]
            ?: return CollectionBrowseOutcome.Unsupported(UNSUPPORTED_PROVIDER)
        val ref = PublicListRef(provider = provider, listId = listId, owner = definition.owner)
        return when (val result = source.page(ref, cursor)) {
            is PublicListResult.Success -> CollectionBrowseOutcome.Loaded(
                CollectionBrowsePage(
                    items = result.value.items.map { item ->
                        CollectionBrowseItem(
                            providerItemId = item.providerItemId,
                            kind = item.kind ?: TitleKind.MOVIE,
                            title = item.title,
                            year = item.year,
                            tmdbId = item.tmdbId,
                            imdbId = item.imdbId,
                        )
                    },
                    hasMore = result.value.hasMore,
                    nextCursor = result.value.nextCursor,
                ),
            )
            is PublicListResult.Unsupported -> CollectionBrowseOutcome.Unsupported(result.reason)
            is PublicListResult.Failed -> CollectionBrowseOutcome.Failed(result.reason)
        }
    }

    companion object {
        internal const val UNSUPPORTED_PROVIDER = "public_list_provider_unsupported"
        internal const val MISSING_LIST_ID = "public list id is missing"
    }
}
