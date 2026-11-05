package dev.infusion.source.aggregation.search

import dev.infusion.core.model.profile.Profile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

interface AddonCatalogSearch {

    suspend fun search(term: String, viewer: Profile, primary: Profile): List<SearchGroup>
}

class SearchResultsService(
    private val addonCatalogSearch: AddonCatalogSearch,

    private val canonicalDiscovery: CanonicalSearchDiscovery? = null,

    private val groupDestinations: AddonGroupDestinations? = null,

    private val peopleDiscovery: PeopleSearchDiscovery? = null,
) {

    suspend fun search(term: String, viewer: Profile, primary: Profile): SearchResults {
        if (term.isBlank()) return SearchResults()
        return coroutineScope {

            val canonicalQuery = async { canonicalOrDegraded(term) }
            val addonQuery = async { addonOrFailed(term, viewer, primary) }

            val peopleQuery = async { peopleOrFailed(term) }
            val canonical = canonicalQuery.await()
            val addon = addonQuery.await()
            val people = peopleQuery.await()

            val groups = enrichedGroups(addon.groups)

            val sections = SearchSectionComposition.compose(canonical.items, groups)
            val represented = canonical.items.map { it.titleId }.toSet()

            val groupsByTitleId = groups.mapNotNull { group ->
                group.destinationTitleId?.let { it to group }
            }.toMap()
            SearchResults(
                canonical = canonical.items.map { row ->
                    val group = groupsByTitleId[row.titleId]
                    if (group == null) {
                        row
                    } else {
                        row.copy(
                            addonHits = row.addonHits + group.items,
                            badges = row.badges + group.badges,
                        )
                    }
                },

                groups = groups.filter { group ->
                    val id = group.destinationTitleId
                    id == null || id !in represented
                },
                canonicalFailed = canonical.failed,
                addonFailed = addon.failed,
                sections = sections,

                people = people.people,
                peopleFailed = people.failed,
            )
        }
    }

    private suspend fun enrichedGroups(groups: List<SearchGroup>): List<SearchGroup> {
        val ladder = groupDestinations ?: return groups.map { it.withMatcherDestination() }
        return try {
            ladder.destinations(groups)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            groups.map { it.withMatcherDestination() }
        }
    }

    private suspend fun canonicalOrDegraded(term: String): CanonicalAnswer {
        val discovery = canonicalDiscovery ?: return CanonicalAnswer(emptyList(), failed = false)
        return try {
            val result = discovery.discover(term)
            CanonicalAnswer(result.items, failed = result.degraded)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CanonicalAnswer(emptyList(), failed = true)
        }
    }

    private suspend fun addonOrFailed(
        term: String,
        viewer: Profile,
        primary: Profile,
    ): AddonAnswer = try {
        AddonAnswer(addonCatalogSearch.search(term, viewer, primary), failed = false)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        AddonAnswer(emptyList(), failed = true)
    }

    private class CanonicalAnswer(
        val items: List<CanonicalSearchResult>,
        val failed: Boolean,
    )

    private suspend fun peopleOrFailed(term: String): PeopleAnswer {
        val discovery = peopleDiscovery ?: return PeopleAnswer(emptyList(), failed = false)
        return try {
            val result = discovery.discover(term)
            PeopleAnswer(result.people, failed = result.failed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PeopleAnswer(emptyList(), failed = true)
        }
    }

    private class PeopleAnswer(
        val people: List<SearchPersonResult>,
        val failed: Boolean,
    )

    private class AddonAnswer(
        val groups: List<SearchGroup>,
        val failed: Boolean,
    )
}
