package dev.infusion.source.aggregation.match

import dev.infusion.core.model.TypedExternalId

object ExternalIdExtractor {

    const val AUTHORITY_IMDB = "imdb"
    const val AUTHORITY_KINOPOISK = "kinopoisk"
    const val AUTHORITY_TMDB = "tmdb"
    const val AUTHORITY_TVDB = "tvdb"

    private val IMDB_PREFIXES = setOf("imdb", "movie", "series", "tv", "channel")
    private val KINOPOISK_PREFIXES = setOf("kpo", "kinopoisk")
    private val TMDB_PREFIXES = setOf("tmdb")
    private val TVDB_PREFIXES = setOf("tvdb")

    private val BARE_IMDB = Regex("^tt\\d{5,10}$")

    fun extract(id: String): List<TypedExternalId> {
        if (id.isBlank() || id.length > 512) return emptyList()
        val firstColon = id.indexOf(':')
        return if (firstColon < 0) {

            if (BARE_IMDB.matches(id)) listOf(TypedExternalId(AUTHORITY_IMDB, id)) else emptyList()
        } else {
            val prefix = id.substring(0, firstColon).lowercase()
            val rest = id.substring(firstColon + 1)
            when {
                prefix in IMDB_PREFIXES && BARE_IMDB.matches(rest) ->
                    listOf(TypedExternalId(AUTHORITY_IMDB, rest))
                prefix in KINOPOISK_PREFIXES && rest.isNotBlank() && rest.length <= 64 ->
                    listOf(TypedExternalId(AUTHORITY_KINOPOISK, rest))
                prefix in TMDB_PREFIXES && rest.isNotBlank() && rest.length <= 64 ->
                    listOf(TypedExternalId(AUTHORITY_TMDB, rest))
                prefix in TVDB_PREFIXES && rest.isNotBlank() && rest.length <= 64 ->
                    listOf(TypedExternalId(AUTHORITY_TVDB, rest))
                else -> emptyList()
            }
        }
    }
}
