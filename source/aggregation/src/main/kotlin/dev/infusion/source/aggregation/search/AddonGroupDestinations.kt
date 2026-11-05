package dev.infusion.source.aggregation.search

import dev.infusion.core.domain.metadata.MediaIdentityResolver
import dev.infusion.core.domain.metadata.MetadataLocale
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import dev.infusion.core.model.metadata.MediaType
import dev.infusion.source.aggregation.match.AddonMetaItem
import dev.infusion.source.aggregation.match.CanonicalMatcher
import dev.infusion.source.aggregation.metadata.AddonMetadataIdentityBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

interface AddonGroupDestinations {

    suspend fun destinations(groups: List<SearchGroup>): List<SearchGroup>
}

class CanonicalGroupDestinationResolver(

    private val resolver: MediaIdentityResolver,

    private val locale: () -> MetadataLocale = { MetadataLocale.Default },

    private val perHitTimeoutMs: Long = 2_000,

    private val maxPerSearch: Int = MAX_PER_SEARCH,

    private val concurrency: Int = CONCURRENCY,
) : AddonGroupDestinations {

    private val bridge = AddonMetadataIdentityBridge(resolver)

    override suspend fun destinations(groups: List<SearchGroup>): List<SearchGroup> {

        val identified = groups.map { it.withMatcherDestination() }

        val candidates = LinkedHashMap<String, SearchGroup>()
        for (group in identified) {
            if (group.destination != null) continue
            if (itemCandidate(group) == null) continue
            candidates.putIfAbsent(identityKey(group), group)
        }
        if (candidates.isEmpty()) return identified

        val budget = candidates.entries.take(maxPerSearch)
        val resolved: Map<String, SearchDestination> = coroutineScope {
            val gate = Semaphore(concurrency)
            budget.map { (key, group) ->
                async { key to resolveOne(group, gate) }
            }.awaitAll().mapNotNull { (key, destination) ->
                destination?.let { key to it }
            }.toMap()
        }

        val enriched = identified.map { group ->
            if (group.destination != null) {
                group
            } else {
                resolved[identityKey(group)]?.let { destination ->
                    group.copy(destination = destination)
                } ?: group
            }
        }

        return mergeByDestination(enriched)
    }

    private suspend fun resolveOne(group: SearchGroup, gate: Semaphore): SearchDestination? {
        val item = itemCandidate(group) ?: return null
        val mediaType = AddonMetadataIdentityBridge.mediaTypeOf(item) ?: return null
        val title = item.displayTitle.trim().takeIf { it.isNotEmpty() } ?: return null
        return gate.withPermit {
            try {
                withTimeoutOrNull(perHitTimeoutMs) {
                    val identity = bridge.resolve(item, locale()) ?: return@withTimeoutOrNull null
                    val titleId = runCatching { resolver.titleIdFor(identity) }.getOrNull()
                        ?: return@withTimeoutOrNull null
                    val kind = if (mediaType == MediaType.SHOW) TitleKind.SERIES else TitleKind.MOVIE
                    SearchDestination(
                        titleId = titleId,
                        kind = kind,
                        preview = mediaPreview(titleId, kind, title, item.year),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun itemCandidate(group: SearchGroup): AddonMetaItem? = group.items.firstOrNull { hit ->
        AddonMetadataIdentityBridge.mediaTypeOf(hit.item) != null && hit.item.displayTitle.isNotBlank()
    }?.item

    private fun identityKey(group: SearchGroup): String {
        val item = itemCandidate(group)
        val type = item?.let { AddonMetadataIdentityBridge.mediaTypeOf(it) }?.name.orEmpty()
        val title = item?.let { CanonicalMatcher.normalizeName(it.displayTitle) }.orEmpty()
        return "$type|$title|${item?.year ?: ""}"
    }

    private fun mergeByDestination(groups: List<SearchGroup>): List<SearchGroup> {
        if (groups.size < 2) return groups
        val result = mutableListOf<SearchGroup>()
        val merged = mutableSetOf<TitleId>()
        for (group in groups) {
            val titleId = group.destination?.titleId
            if (titleId == null) {
                result += group
                continue
            }
            if (!merged.add(titleId)) continue
            val sameDestination = groups.filter { it.destination?.titleId == titleId }
            result += if (sameDestination.size == 1) {
                group
            } else {
                sameDestination.first().copy(
                    key = "destination:${titleId.value}",
                    badges = sameDestination.flatMap { it.badges }.toSet(),
                    items = sameDestination.flatMap { it.items }.distinctBy { it.key },
                )
            }
        }
        return result
    }

    private companion object {

        const val MAX_PER_SEARCH = 12

        const val CONCURRENCY = 4
    }
}
