package dev.infusion.source.aggregation.search

import dev.infusion.core.domain.metadata.MediaIdentityResolver
import dev.infusion.core.domain.metadata.MediaSearchCandidate
import dev.infusion.core.domain.metadata.MetadataLocale
import dev.infusion.core.domain.metadata.MetadataProvider
import dev.infusion.core.model.ArtworkPolicy
import dev.infusion.core.model.MediaArtworkRef
import dev.infusion.core.model.MediaPreview
import dev.infusion.core.model.TitleKind
import dev.infusion.core.model.metadata.MediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

interface CanonicalSearchDiscovery {

    suspend fun discover(term: String): CanonicalSearchDiscoveryResult
}

data class CanonicalSearchDiscoveryResult(
    val items: List<CanonicalSearchResult> = emptyList(),
    val degraded: Boolean = false,
)

class CanonicalSearchDiscoveryService(
    private val provider: MetadataProvider,
    private val resolver: MediaIdentityResolver,

    private val locale: () -> MetadataLocale = { MetadataLocale.Default },

    private val typeTimeoutMs: Long = 10_000,
) : CanonicalSearchDiscovery {

    override suspend fun discover(term: String): CanonicalSearchDiscoveryResult {
        val query = term.trim()
        if (query.isEmpty()) return CanonicalSearchDiscoveryResult()

        return coroutineScope {
            val movieQuery = async { queryOne(query, MediaType.MOVIE) }
            val showQuery = async { queryOne(query, MediaType.SHOW) }
            val movies = movieQuery.await()
            val shows = showQuery.await()

            CanonicalSearchDiscoveryResult(
                items = (movies.items + shows.items).distinctBy { it.kind to it.titleId },
                degraded = movies.failed || shows.failed,
            )
        }
    }

    private class TypeDiscovery(
        val items: List<CanonicalSearchResult>,
        val failed: Boolean,
    )

    private suspend fun queryOne(term: String, type: MediaType): TypeDiscovery {
        val candidates = try {
            withTimeoutOrNull(typeTimeoutMs) { provider.search(term, null, type, locale()) }
                ?: return TypeDiscovery(emptyList(), failed = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return TypeDiscovery(emptyList(), failed = true)
        }
        val kind = if (type == MediaType.SHOW) TitleKind.SERIES else TitleKind.MOVIE
        return TypeDiscovery(
            items = candidates.mapNotNull { candidate -> canonicalRow(candidate, kind) },
            failed = false,
        )
    }

    private suspend fun canonicalRow(
        candidate: MediaSearchCandidate,
        kind: TitleKind,
    ): CanonicalSearchResult? {
        val title = candidate.title.trim().takeIf { it.isNotEmpty() } ?: return null
        val titleId = try {
            resolver.titleIdFor(candidate.identity)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return null
        return CanonicalSearchResult(
            key = "canonical:${titleId.value}",
            titleId = titleId,
            identity = candidate.identity,
            kind = kind,
            displayTitle = title,
            year = candidate.year,

            preview = MediaPreview(
                titleId = titleId,
                kind = kind,
                displayTitle = title,
                year = candidate.year,
                subtitle = null,
                artwork = candidateArtworkRef(candidate),
                progressFraction = null,
            ),
        )
    }

    private fun candidateArtworkRef(
        candidate: MediaSearchCandidate,
    ): MediaArtworkRef? {
        val poster = candidate.artwork

        val landscape = candidate.landscapeArtwork
            ?.takeIf { poster == null || it.provider == poster.provider }
        return when {
            poster != null -> MediaArtworkRef.metadata(
                provider = poster.provider,
                path = poster.path,
                policy = ArtworkPolicy.POSTER,
                landscapePath = landscape?.path,
            )
            landscape != null -> MediaArtworkRef.metadata(
                provider = landscape.provider,
                path = landscape.path,
                policy = ArtworkPolicy.BACKDROP,
            )
            else -> null
        }
    }
}
