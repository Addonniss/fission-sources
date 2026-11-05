package dev.infusion.source.aggregation.search

import dev.infusion.core.domain.metadata.MetadataLocale
import dev.infusion.core.domain.metadata.MetadataProvider
import dev.infusion.core.model.metadata.ImageRef
import dev.infusion.core.model.metadata.PersonId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

interface PeopleSearchDiscovery {

    suspend fun discover(term: String): PeopleSearchDiscoveryResult
}

data class PeopleSearchDiscoveryResult(
    val people: List<SearchPersonResult> = emptyList(),
    val failed: Boolean = false,
)

data class SearchPersonResult(
    val personId: PersonId,
    val name: String,

    val role: String? = null,

    val profile: ImageRef? = null,
)

class PeopleSearchDiscoveryService(
    private val provider: MetadataProvider,
    private val locale: () -> MetadataLocale = { MetadataLocale.Default },

    private val timeoutMs: Long = 10_000,
) : PeopleSearchDiscovery {

    override suspend fun discover(term: String): PeopleSearchDiscoveryResult {
        val query = term.trim()
        if (query.isEmpty()) return PeopleSearchDiscoveryResult()
        val candidates = try {
            withTimeoutOrNull(timeoutMs) { provider.searchPeople(query, locale()) }
                ?: return PeopleSearchDiscoveryResult(failed = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return PeopleSearchDiscoveryResult(failed = true)
        }

        return PeopleSearchDiscoveryResult(
            people = candidates.mapNotNull { candidate ->
                val name = candidate.name.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                SearchPersonResult(
                    personId = candidate.id,
                    name = name,
                    role = candidate.knownForDepartment?.trim()?.takeIf { it.isNotEmpty() },
                    profile = candidate.profile,
                )
            },
            failed = false,
        )
    }
}
