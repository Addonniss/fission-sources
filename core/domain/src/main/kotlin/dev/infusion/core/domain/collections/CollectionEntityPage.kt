package dev.infusion.core.domain.collections

import java.time.LocalDate

enum class CollectionEntityKind {

    COMPANY,

    NETWORK,
    ;

    companion object {
        fun fromName(value: String?): CollectionEntityKind? =
            entries.firstOrNull { it.name == value }
    }
}

data class CollectionEntityRef(
    val kind: CollectionEntityKind,

    val provider: String,

    val id: String,
) {
    init {
        require(provider.isNotBlank()) { "entity provider must not be blank" }
        require(id.isNotBlank()) { "entity id must not be blank" }
    }
}

data class GeneratedCollectionPage(
    val ref: CollectionEntityRef,
    val title: String,
    val logoLocator: String?,
)

object CollectionEntityPage {

    const val PROVIDER_TMDB: String = "tmdb"

    const val GROUP_KEY: String = "root"
    const val TILE_KEY: String = "main"

    private const val NAMESPACE = "gen"

    private const val SEP = '\u001E'

    private const val MAX_TITLE = 200
    private const val MAX_LOGO = 1024

    private const val COMPANY_LATEST_MOVIES = "latest-movies"
    private const val COMPANY_LATEST_SHOWS = "latest-shows"
    private const val COMPANY_POPULAR_MOVIES = "popular-movies"
    private const val COMPANY_POPULAR_SHOWS = "popular-shows"
    private const val COMPANY_TOP_RATED_MOVIES = "top-rated-movies"
    private const val COMPANY_TOP_RATED_SHOWS = "top-rated-shows"

    private const val NETWORK_LATEST_SHOWS = "latest-shows"
    private const val NETWORK_POPULAR_SHOWS = "popular-shows"
    private const val NETWORK_TOP_RATED_SHOWS = "top-rated-shows"

    private const val LATEST_MOVIES_SORT = "primary_release_date.desc"
    private const val LATEST_SHOWS_SORT = "first_air_date.desc"
    private const val POPULAR_SORT = "popularity.desc"
    private const val TOP_RATED_SORT = "vote_average.desc"

    internal const val RATED_VOTE_COUNT_FILTER_KEY = "vote_count.gte"
    internal const val RATED_VOTE_COUNT_MIN = "1"

    internal const val MOVIE_DATE_BOUND_KEY = "release_date.lte"
    internal const val SERIES_DATE_BOUND_KEY = "first_air_date.lte"

    fun encodeToken(ref: CollectionEntityRef, title: String, logoLocator: String?): String =
        listOf(
            NAMESPACE,
            ref.kind.name,
            clean(ref.provider),
            clean(ref.id),
            clean(title).take(MAX_TITLE),
            logoLocator?.let { clean(it).take(MAX_LOGO) } ?: "",
        ).joinToString(SEP.toString())

    fun decodeToken(token: String): GeneratedCollectionPage? {
        val parts = token.split(SEP)
        if (parts.size != 6 || parts[0] != NAMESPACE) return null
        val kind = CollectionEntityKind.fromName(parts[1]) ?: return null
        val provider = parts[2].trim()
        val id = parts[3].trim()
        if (provider.isEmpty() || id.isEmpty()) return null
        return GeneratedCollectionPage(
            ref = CollectionEntityRef(kind, provider, id),
            title = parts[4],
            logoLocator = parts[5].takeIf { it.isNotEmpty() },
        )
    }

    fun packFor(token: String, today: LocalDate): CollectionPack? =
        decodeToken(token)?.let { page -> pack(page, today) }

    fun pack(page: GeneratedCollectionPage, today: LocalDate): CollectionPack {
        val rows = when (page.ref.kind) {
            CollectionEntityKind.COMPANY -> companyRows(page.ref.id, today)
            CollectionEntityKind.NETWORK -> networkRows(page.ref.id, today)
        }
        return CollectionPack(
            packId = encodeToken(page.ref, page.title, page.logoLocator),
            name = page.title,
            groups = listOf(
                CollectionGroup(
                    groupKey = GROUP_KEY,
                    title = page.title,
                    tiles = listOf(
                        CollectionTile(
                            tileKey = TILE_KEY,
                            title = page.title,
                            shape = CollectionTileShape.LANDSCAPE,
                            artwork = CollectionTileArtwork(

                                logo = page.logoLocator,
                            ),
                            rows = rows,
                        ),
                    ),
                ),
            ),
        )
    }

    private fun companyRows(entityId: String, today: LocalDate): List<CollectionRow> = listOf(
        row(
            key = COMPANY_LATEST_MOVIES,
            title = "Latest Movies",
            kind = CollectionEntityKind.COMPANY,
            entityId = entityId,
            mediaType = "MOVIE",
            sortBy = LATEST_MOVIES_SORT,
            filters = mapOf(MOVIE_DATE_BOUND_KEY to today.toString()),
        ),
        row(
            key = COMPANY_LATEST_SHOWS,
            title = "Latest Shows",
            kind = CollectionEntityKind.COMPANY,
            entityId = entityId,
            mediaType = "TV",
            sortBy = LATEST_SHOWS_SORT,
            filters = mapOf(SERIES_DATE_BOUND_KEY to today.toString()),
        ),
        row(
            key = COMPANY_POPULAR_MOVIES,
            title = "Popular Movies",
            kind = CollectionEntityKind.COMPANY,
            entityId = entityId,
            mediaType = "MOVIE",
            sortBy = POPULAR_SORT,
        ),
        row(
            key = COMPANY_POPULAR_SHOWS,
            title = "Popular Shows",
            kind = CollectionEntityKind.COMPANY,
            entityId = entityId,
            mediaType = "TV",
            sortBy = POPULAR_SORT,
        ),
        row(
            key = COMPANY_TOP_RATED_MOVIES,
            title = "Top Rated Movies",
            kind = CollectionEntityKind.COMPANY,
            entityId = entityId,
            mediaType = "MOVIE",
            sortBy = TOP_RATED_SORT,
            filters = mapOf(RATED_VOTE_COUNT_FILTER_KEY to RATED_VOTE_COUNT_MIN),
        ),
        row(
            key = COMPANY_TOP_RATED_SHOWS,
            title = "Top Rated Shows",
            kind = CollectionEntityKind.COMPANY,
            entityId = entityId,
            mediaType = "TV",
            sortBy = TOP_RATED_SORT,
            filters = mapOf(RATED_VOTE_COUNT_FILTER_KEY to RATED_VOTE_COUNT_MIN),
        ),
    )

    private fun networkRows(entityId: String, today: LocalDate): List<CollectionRow> = listOf(
        row(
            key = NETWORK_LATEST_SHOWS,
            title = "Latest Shows",
            kind = CollectionEntityKind.NETWORK,
            entityId = entityId,
            mediaType = "TV",
            sortBy = LATEST_SHOWS_SORT,
            filters = mapOf(SERIES_DATE_BOUND_KEY to today.toString()),
        ),
        row(
            key = NETWORK_POPULAR_SHOWS,
            title = "Popular Shows",
            kind = CollectionEntityKind.NETWORK,
            entityId = entityId,
            mediaType = "TV",
            sortBy = POPULAR_SORT,
        ),
        row(
            key = NETWORK_TOP_RATED_SHOWS,
            title = "Top Rated Shows",
            kind = CollectionEntityKind.NETWORK,
            entityId = entityId,
            mediaType = "TV",
            sortBy = TOP_RATED_SORT,
            filters = mapOf(RATED_VOTE_COUNT_FILTER_KEY to RATED_VOTE_COUNT_MIN),
        ),
    )

    private fun row(
        key: String,
        title: String,
        kind: CollectionEntityKind,
        entityId: String,
        mediaType: String,
        sortBy: String,
        filters: Map<String, String> = emptyMap(),
    ): CollectionRow = CollectionRow(
        rowKey = key,
        title = title,
        mediaKind = if (mediaType == "MOVIE") CollectionMediaKind.MOVIE else CollectionMediaKind.SERIES,
        definition = CollectionSourceDefinition(
            provider = PROVIDER_TMDB,
            tmdbSourceType = kind.name,
            tmdbId = entityId,
            mediaType = mediaType,
            sortBy = sortBy,
            filters = filters,
        ),
    )

    private fun clean(value: String): String =
        value.replace(SEP, ' ').trim()
}
