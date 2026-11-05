package dev.infusion.core.model.metadata

import dev.infusion.core.model.SourceId
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TypedExternalId
import java.util.Locale

private fun <T> contradicts(a: T?, b: T?): Boolean = a != null && b != null && a != b

enum class MediaType { MOVIE, SHOW }

data class ExternalIds(
    val tmdbId: Long? = null,
    val imdbId: String? = null,
    val tvdbId: Long? = null,
) {

    val isEmpty: Boolean get() = tmdbId == null && imdbId == null && tvdbId == null

    fun typed(): List<TypedExternalId> = buildList {
        tmdbId?.let { add(TypedExternalId(AUTHORITY_TMDB, it.toString())) }
        imdbId?.let { add(TypedExternalId(AUTHORITY_IMDB, it)) }
        tvdbId?.let { add(TypedExternalId(AUTHORITY_TVDB, it.toString())) }
    }

    fun merge(other: ExternalIds): ExternalIds? {
        if (contradicts(tmdbId, other.tmdbId)) return null
        if (contradicts(imdbId, other.imdbId)) return null
        if (contradicts(tvdbId, other.tvdbId)) return null
        return ExternalIds(
            tmdbId = tmdbId ?: other.tmdbId,
            imdbId = imdbId ?: other.imdbId,
            tvdbId = tvdbId ?: other.tvdbId,
        )
    }

    companion object {
        const val AUTHORITY_TMDB = "tmdb"
        const val AUTHORITY_IMDB = "imdb"
        const val AUTHORITY_TVDB = "tvdb"

        fun fromTyped(ids: Iterable<TypedExternalId>): ExternalIds {
            var tmdb: Long? = null
            var imdb: String? = null
            var tvdb: Long? = null
            for (id in ids) {
                when (id.authority) {
                    AUTHORITY_TMDB -> tmdb = tmdb ?: id.value.toLongOrNull()?.takeIf { it > 0 }
                    AUTHORITY_IMDB -> imdb = imdb ?: id.value.trim()
                        .takeIf { it.isNotEmpty() }
                    AUTHORITY_TVDB -> tvdb = tvdb ?: id.value.toLongOrNull()?.takeIf { it > 0 }
                }
            }
            return ExternalIds(tmdbId = tmdb, imdbId = imdb, tvdbId = tvdb)
        }
    }
}

data class MediaIdentity(
    val type: MediaType,
    val titleId: TitleId? = null,
    val externalIds: ExternalIds = ExternalIds(),
    val sourceIds: Map<SourceId, String> = emptyMap(),
) {
    val tmdbId: Long? get() = externalIds.tmdbId
    val imdbId: String? get() = externalIds.imdbId
    val tvdbId: Long? get() = externalIds.tvdbId

    val isUnresolved: Boolean get() = titleId == null && externalIds.isEmpty && sourceIds.isEmpty()

    val hasCanonicalMetadataAnchor: Boolean get() = externalIds.tmdbId != null

    fun withSourceId(sourceId: SourceId, nativeItemId: String): MediaIdentity? {
        if (nativeItemId.isBlank()) return null
        val existing = sourceIds[sourceId]
        if (existing != null && existing != nativeItemId) return null
        if (existing == nativeItemId) return this
        return copy(sourceIds = sourceIds + (sourceId to nativeItemId))
    }

    fun merge(other: MediaIdentity): MediaIdentity? {
        if (type != other.type) return null
        if (contradicts(titleId, other.titleId)) return null
        val mergedExternal = externalIds.merge(other.externalIds) ?: return null
        for ((sourceId, nativeId) in other.sourceIds) {
            val existing = sourceIds[sourceId]
            if (existing != null && existing != nativeId) return null
        }
        val mergedSources = LinkedHashMap(sourceIds)
        for ((sourceId, nativeId) in other.sourceIds.toSortedMap(compareBy { it.value })) {
            mergedSources[sourceId] = mergedSources[sourceId] ?: nativeId
        }
        return copy(
            titleId = titleId ?: other.titleId,
            externalIds = mergedExternal,
            sourceIds = mergedSources,
        )
    }

    fun cacheKey(): String? = when {
        externalIds.tmdbId != null -> scoped("tmdb:${externalIds.tmdbId}")
        externalIds.imdbId != null -> scoped("imdb:${externalIds.imdbId}")
        externalIds.tvdbId != null -> scoped("tvdb:${externalIds.tvdbId}")
        titleId != null -> scoped("title:${titleId.value}")
        else -> null
    }

    private fun scoped(key: String): String = "${type.name.lowercase(Locale.ROOT)}:$key"

    companion object {

        fun tmdb(type: MediaType, tmdbId: Long, titleId: TitleId? = null): MediaIdentity =
            MediaIdentity(type = type, titleId = titleId, externalIds = ExternalIds(tmdbId = tmdbId))

        fun imdb(type: MediaType, imdbId: String, titleId: TitleId? = null): MediaIdentity =
            MediaIdentity(type = type, titleId = titleId, externalIds = ExternalIds(imdbId = imdbId))

        fun authority(external: TypedExternalId): String = external.authority.lowercase(Locale.ROOT)
    }
}
