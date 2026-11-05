package dev.infusion.core.domain.home

import dev.infusion.core.domain.collections.CollectionTileShape
import dev.infusion.core.model.ArtworkPolicy
import dev.infusion.core.model.MediaArtworkRef
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import java.time.LocalDate

enum class HomePresentationFamily {

    CATALOG,

    PUBLIC_LIST,

    COLLECTION_GROUP,

    ;

    val isCardRow: Boolean get() = this == CATALOG || this == PUBLIC_LIST
}

data class HomeCardPresentation(
    val key: String,
    val titleId: TitleId?,
    val kind: TitleKind?,
    val displayTitle: String,
    val year: Int?,
    val subtitle: String?,
    val providerCatalogId: String,
    val providerItemId: String,
    val artwork: MediaArtworkRef?,
)

data class HomeRowPresentation(
    val rowKey: String,
    val family: HomePresentationFamily,
    val definitionKey: String,
    val title: String,
    val sourceLabel: String,
    val cards: List<HomeCardPresentation>,
    val storedAtEpochMs: Long,
)

data class HomeTilePresentation(
    val tileKey: String,
    val title: String,
    val shape: CollectionTileShape,
    val hideTitle: Boolean,
    val coverLocator: String?,
    val logoLocator: String?,
    val backdropLocator: String?,
    val focusLocator: String?,
)

data class HomeCollectionRowPresentation(
    val rowKey: String,
    val definitionKey: String,
    val title: String,
    val packId: String,
    val groupKey: String,
    val tiles: List<HomeTilePresentation>,
    val storedAtEpochMs: Long,
)

data class HomeIdentityPresentation(
    val identityKey: String,
    val displayTitle: String,
    val year: Int? = null,
    val airDate: LocalDate? = null,
    val episodeTitleId: TitleId? = null,
    val episodeTitle: String? = null,
    val runtimeMinutes: Int? = null,
    val provenanceLabel: String? = null,

    val artwork: MediaArtworkRef? = null,
    val storedAtEpochMs: Long = 0L,
) {

    fun isEmpty(): Boolean =
        displayTitle.isBlank() && year == null && airDate == null && episodeTitleId == null &&
            episodeTitle == null && runtimeMinutes == null && provenanceLabel == null && artwork == null
}

data class HomeHeroPresentation(
    val identityKey: String,
    val mediaType: String,
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    val runtimeMinutes: Int? = null,
    val certificationCode: String? = null,
    val certificationRegion: String? = null,
    val genres: List<String> = emptyList(),
    val ratings: List<HomeHeroRating> = emptyList(),
    val overview: String? = null,
    val tagline: String? = null,
    val seasonCount: Int? = null,
    val status: String? = null,
    val backdropLocator: String? = null,
    val posterLocator: String? = null,
    val logoLocator: String? = null,
    val storedAtEpochMs: Long = 0L,
) {

    fun isEmpty(): Boolean =
        identityKey.isBlank() || title.isBlank()
}

data class HomeHeroRating(
    val label: String,
    val text: String,
    val fraction: Double,
    val metricId: String = "",
)

interface HomePresentationStore {

    fun readRow(profileId: String, rowKey: String): HomeRowPresentation?

    fun readRows(profileId: String, rowKeys: Collection<String>): Map<String, HomeRowPresentation>

    fun writeRow(profileId: String, row: HomeRowPresentation)

    fun readCollectionRow(profileId: String, rowKey: String): HomeCollectionRowPresentation?

    fun writeCollectionRow(profileId: String, row: HomeCollectionRowPresentation)

    fun readPresentation(profileId: String, identityKey: String): HomeIdentityPresentation?

    fun writePresentation(profileId: String, presentation: HomeIdentityPresentation)

    fun readHero(profileId: String, identityKey: String): HomeHeroPresentation? = null

    fun writeHero(profileId: String, hero: HomeHeroPresentation) = Unit

    fun clearRow(profileId: String, rowKey: String)

    fun clearProfile(profileId: String)
}

object HomePresentationPolicy {

    const val ROW_MAX_AGE_MS: Long = 12L * 60L * 60L * 1_000L

    const val PRESENTATION_MAX_AGE_MS: Long = 72L * 60L * 60L * 1_000L

    const val MAX_ROWS_PER_PROFILE: Int = 32

    const val MAX_PRESENTATIONS_PER_PROFILE: Int = 400

    const val MAX_HEROES_PER_PROFILE: Int = 10

    const val MAX_HERO_OVERVIEW_CHARS: Int = 1_200

    const val MAX_HERO_RATINGS: Int = 8

    const val MAX_HERO_GENRES: Int = 12

    const val MAX_CARDS_PER_ROW: Int = 60

    const val MAX_TILES_PER_ROW: Int = 60

    const val MAX_RECORD_BYTES: Int = 32 * 1024

    const val MAX_PROFILE_BYTES: Int = 512 * 1024

    fun rowIsFresh(row: HomeRowPresentation, nowEpochMs: Long): Boolean =
        isFresh(row.storedAtEpochMs, nowEpochMs, ROW_MAX_AGE_MS)

    fun collectionRowIsFresh(row: HomeCollectionRowPresentation, nowEpochMs: Long): Boolean =
        isFresh(row.storedAtEpochMs, nowEpochMs, ROW_MAX_AGE_MS)

    fun presentationIsFresh(presentation: HomeIdentityPresentation, nowEpochMs: Long): Boolean =
        isFresh(presentation.storedAtEpochMs, nowEpochMs, PRESENTATION_MAX_AGE_MS)

    fun heroIsFresh(hero: HomeHeroPresentation, nowEpochMs: Long): Boolean =
        isFresh(hero.storedAtEpochMs, nowEpochMs, PRESENTATION_MAX_AGE_MS)

    private fun isFresh(storedAtEpochMs: Long, nowEpochMs: Long, maxAgeMs: Long): Boolean {
        if (storedAtEpochMs <= 0L) return false
        val age = nowEpochMs - storedAtEpochMs
        return age >= 0L && age <= maxAgeMs
    }

    fun definitionKey(
        profileId: String,
        rowKey: String,
        family: HomePresentationFamily,
        sourceIdentity: String,
        title: String,
        presentationToken: String,
        localeToken: String,
    ): String = buildString {
        append("p=").append(profileId)
        append(";r=").append(rowKey)
        append(";f=").append(family.name)
        append(";s=").append(sourceIdentity)
        append(";t=").append(title)
        append(";x=").append(presentationToken)
        append(";l=").append(localeToken)
    }

    fun reusableArtwork(artwork: MediaArtworkRef?): MediaArtworkRef? {
        val ref = artwork ?: return null
        if (reusableArtworkLocator(ref.locator) == null) return null

        if (ref.landscapeLocator != null && reusableArtworkLocator(ref.landscapeLocator) == null) {
            return null
        }
        return ref
    }

    fun reusableArtworkLocator(locator: String?): String? =
        locator?.takeIf { dev.infusion.core.model.ArtworkLocator.resolvableWithoutFetchedPage(it) }
}

object HomePresentationCodec {

    const val VERSION_TAG_ROWS: String = "hr1"
    const val VERSION_TAG_COLLECTION_ROWS: String = "hc1"
    const val VERSION_TAG_IDENTITY: String = "hi1"
    const val VERSION_TAG_HERO: String = "he1"

    private const val RECORD_SEPARATOR = ';'
    private const val FIELD_SEPARATOR = '|'
    private const val ARTWORK_SEPARATOR = '#'
    private const val LIST_SEPARATOR = ','
    private const val RATING_SEPARATOR = '~'

    private val UNRESERVED: BooleanArray = BooleanArray(128).also { table ->
        for (index in 0..127) {
            val char = index.toChar()
            table[index] = char.isLetterOrDigit() && char.code < 128 ||
                char == '.' || char == '_' || char == '-'
        }
    }

    private const val HEX: String = "0123456789ABCDEF"

    fun encodeRow(row: HomeRowPresentation): String? {
        if (row.cards.isEmpty()) return null
        if (row.cards.size > HomePresentationPolicy.MAX_CARDS_PER_ROW) return null
        if (row.cards.any { it.key.isBlank() }) return null
        val encoded = buildString {
            append(VERSION_TAG_ROWS)
            append(RECORD_SEPARATOR).append(escape(row.rowKey))
            append(RECORD_SEPARATOR).append(escape(row.family.name))
            append(RECORD_SEPARATOR).append(escape(row.definitionKey))
            append(RECORD_SEPARATOR).append(escape(row.title))
            append(RECORD_SEPARATOR).append(escape(row.sourceLabel))
            append(RECORD_SEPARATOR).append(row.storedAtEpochMs)
            append(RECORD_SEPARATOR).append(row.cards.size)
            row.cards.forEach { card ->
                append(RECORD_SEPARATOR).append(encodeCard(card))
            }
        }
        return encoded.takeIf { withinByteBound(it) }
    }

    fun decodeRow(stored: String?): HomeRowPresentation? {
        val text = stored?.takeIf { withinByteBound(it) } ?: return null

        val parts = text.split(RECORD_SEPARATOR)
        if (parts.size < HEADER_FIELDS_ROWS + 1) return null
        if (parts[0] != VERSION_TAG_ROWS) return null
        val cardCount = parts.size - HEADER_FIELDS_ROWS
        if (cardCount > HomePresentationPolicy.MAX_CARDS_PER_ROW) return null
        if (parts[7].toIntOrNull() != cardCount) return null
        val family = HomePresentationFamily.entries.firstOrNull { it.name == unescape(parts[2]) }
            ?: return null
        if (!family.isCardRow) return null
        val storedAt = parts[6].toLongOrNull() ?: return null
        val cards = ArrayList<HomeCardPresentation>(cardCount)
        for (index in 0 until cardCount) {
            cards += decodeCard(parts[HEADER_FIELDS_ROWS + index]) ?: return null
        }
        if (cards.isEmpty()) return null
        if (cards.mapTo(HashSet()) { it.key }.size != cards.size) return null
        return HomeRowPresentation(
            rowKey = unescape(parts[1]) ?: return null,
            family = family,
            definitionKey = unescape(parts[3]) ?: return null,
            title = unescape(parts[4]) ?: return null,
            sourceLabel = unescape(parts[5]) ?: return null,
            cards = cards,
            storedAtEpochMs = storedAt,
        )
    }

    private fun encodeCard(card: HomeCardPresentation): String = buildString {
        append(escape(card.key))
        append(FIELD_SEPARATOR).append(escape(card.titleId?.value?.toString()))
        append(FIELD_SEPARATOR).append(escape(card.kind?.name))
        append(FIELD_SEPARATOR).append(escape(card.displayTitle))
        append(FIELD_SEPARATOR).append(card.year ?: "")
        append(FIELD_SEPARATOR).append(escape(card.subtitle))
        append(FIELD_SEPARATOR).append(escape(card.providerCatalogId))
        append(FIELD_SEPARATOR).append(escape(card.providerItemId))
        append(FIELD_SEPARATOR).append(encodeArtwork(card.artwork))
    }

    private fun decodeCard(field: String): HomeCardPresentation? {
        val parts = field.split(FIELD_SEPARATOR)
        if (parts.size != CARD_FIELDS) return null
        val key = unescape(parts[0])?.takeIf { it.isNotBlank() } ?: return null

        if (!artworkFieldIsValid(parts[8])) return null
        return HomeCardPresentation(
            key = key,
            titleId = decodeTitleId(parts[1]),
            kind = unescape(parts[2])?.takeIf { it.isNotBlank() }
                ?.let { name -> TitleKind.entries.firstOrNull { it.name == name } },
            displayTitle = unescape(parts[3]) ?: return null,
            year = parts[4].toIntOrNull(),
            subtitle = unescape(parts[5])?.takeIf { it.isNotBlank() },
            providerCatalogId = unescape(parts[6]) ?: return null,
            providerItemId = unescape(parts[7]) ?: return null,
            artwork = decodeArtwork(parts[8]),
        )
    }

    fun encodeCollectionRow(row: HomeCollectionRowPresentation): String? {
        if (row.tiles.isEmpty()) return null
        if (row.tiles.size > HomePresentationPolicy.MAX_TILES_PER_ROW) return null
        val encoded = buildString {
            append(VERSION_TAG_COLLECTION_ROWS)
            append(RECORD_SEPARATOR).append(escape(row.rowKey))
            append(RECORD_SEPARATOR).append(escape(row.definitionKey))
            append(RECORD_SEPARATOR).append(escape(row.title))
            append(RECORD_SEPARATOR).append(escape(row.packId))
            append(RECORD_SEPARATOR).append(escape(row.groupKey))
            append(RECORD_SEPARATOR).append(row.storedAtEpochMs)
            append(RECORD_SEPARATOR).append(row.tiles.size)
            row.tiles.forEach { tile ->
                append(RECORD_SEPARATOR).append(encodeTile(tile))
            }
        }
        return encoded.takeIf { withinByteBound(it) }
    }

    fun decodeCollectionRow(stored: String?): HomeCollectionRowPresentation? {
        val text = stored?.takeIf { withinByteBound(it) } ?: return null
        val parts = text.split(RECORD_SEPARATOR)
        if (parts.size < HEADER_FIELDS_COLLECTION + 1) return null
        if (parts[0] != VERSION_TAG_COLLECTION_ROWS) return null
        val tileCount = parts.size - HEADER_FIELDS_COLLECTION
        if (tileCount > HomePresentationPolicy.MAX_TILES_PER_ROW) return null
        if (parts[7].toIntOrNull() != tileCount) return null
        val storedAt = parts[6].toLongOrNull() ?: return null
        val tiles = ArrayList<HomeTilePresentation>(tileCount)
        for (index in 0 until tileCount) {
            tiles += decodeTile(parts[HEADER_FIELDS_COLLECTION + index]) ?: return null
        }
        if (tiles.isEmpty()) return null
        return HomeCollectionRowPresentation(
            rowKey = unescape(parts[1]) ?: return null,
            definitionKey = unescape(parts[2]) ?: return null,
            title = unescape(parts[3]) ?: return null,
            packId = unescape(parts[4]) ?: return null,
            groupKey = unescape(parts[5]) ?: return null,
            tiles = tiles,
            storedAtEpochMs = storedAt,
        )
    }

    private fun encodeTile(tile: HomeTilePresentation): String = buildString {
        append(escape(tile.tileKey))
        append(FIELD_SEPARATOR).append(escape(tile.title))
        append(FIELD_SEPARATOR).append(tile.shape.name)
        append(FIELD_SEPARATOR).append(if (tile.hideTitle) "1" else "0")
        append(FIELD_SEPARATOR).append(escape(tile.coverLocator))
        append(FIELD_SEPARATOR).append(escape(tile.logoLocator))
        append(FIELD_SEPARATOR).append(escape(tile.backdropLocator))
        append(FIELD_SEPARATOR).append(escape(tile.focusLocator))
    }

    private fun decodeTile(field: String): HomeTilePresentation? {
        val parts = field.split(FIELD_SEPARATOR)
        if (parts.size != TILE_FIELDS) return null
        return HomeTilePresentation(
            tileKey = unescape(parts[0])?.takeIf { it.isNotBlank() } ?: return null,
            title = unescape(parts[1]) ?: return null,
            shape = CollectionTileShape.entries.firstOrNull { it.name == parts[2] } ?: return null,
            hideTitle = when (parts[3]) {
                "1" -> true
                "0" -> false
                else -> return null
            },
            coverLocator = unescape(parts[4])?.takeIf { it.isNotBlank() },
            logoLocator = unescape(parts[5])?.takeIf { it.isNotBlank() },
            backdropLocator = unescape(parts[6])?.takeIf { it.isNotBlank() },
            focusLocator = unescape(parts[7])?.takeIf { it.isNotBlank() },
        )
    }

    fun encodePresentation(presentation: HomeIdentityPresentation): String? {
        if (presentation.identityKey.isBlank()) return null
        if (presentation.isEmpty()) return null
        val encoded = buildString {
            append(VERSION_TAG_IDENTITY)
            append(RECORD_SEPARATOR).append(escape(presentation.identityKey))
            append(RECORD_SEPARATOR).append(presentation.storedAtEpochMs)
            append(RECORD_SEPARATOR).append(escape(presentation.displayTitle))
            append(FIELD_SEPARATOR).append(presentation.year ?: "")
            append(FIELD_SEPARATOR).append(presentation.airDate?.toString() ?: "")
            append(FIELD_SEPARATOR).append(escape(presentation.episodeTitleId?.value?.toString()))
            append(FIELD_SEPARATOR).append(escape(presentation.episodeTitle))
            append(FIELD_SEPARATOR).append(presentation.runtimeMinutes ?: "")
            append(FIELD_SEPARATOR).append(escape(presentation.provenanceLabel))
            append(FIELD_SEPARATOR).append(encodeArtwork(presentation.artwork))
        }
        return encoded.takeIf { withinByteBound(it) }
    }

    fun decodePresentation(stored: String?): HomeIdentityPresentation? {
        val text = stored?.takeIf { withinByteBound(it) } ?: return null
        val header = text.split(RECORD_SEPARATOR, limit = HEADER_FIELDS_IDENTITY + 1)
        if (header.size != HEADER_FIELDS_IDENTITY + 1) return null
        if (header[0] != VERSION_TAG_IDENTITY) return null
        val storedAt = header[2].toLongOrNull() ?: return null
        val parts = header[3].split(FIELD_SEPARATOR)
        if (parts.size != IDENTITY_FIELDS) return null
        if (!artworkFieldIsValid(parts[7])) return null
        return HomeIdentityPresentation(
            identityKey = unescape(header[1])?.takeIf { it.isNotBlank() } ?: return null,
            displayTitle = unescape(parts[0]) ?: return null,
            year = parts[1].toIntOrNull(),
            airDate = parts[2].takeIf { it.isNotBlank() }?.let { raw ->
                runCatching { LocalDate.parse(raw) }.getOrNull() ?: return null
            },
            episodeTitleId = decodeTitleId(parts[3]),
            episodeTitle = unescape(parts[4])?.takeIf { it.isNotBlank() },
            runtimeMinutes = parts[5].toIntOrNull(),
            provenanceLabel = unescape(parts[6])?.takeIf { it.isNotBlank() },
            artwork = decodeArtwork(parts[7]),
            storedAtEpochMs = storedAt,
        )
    }

    fun encodeHero(hero: HomeHeroPresentation): String? {
        if (hero.isEmpty()) return null
        val genres = hero.genres
            .filter { genre -> genre.isNotBlank() }
            .take(HomePresentationPolicy.MAX_HERO_GENRES)
        val ratings = hero.ratings
            .filter { rating -> rating.label.isNotBlank() || rating.text.isNotBlank() }
            .take(HomePresentationPolicy.MAX_HERO_RATINGS)
        val overview = hero.overview?.take(HomePresentationPolicy.MAX_HERO_OVERVIEW_CHARS)
        val encoded = buildString {
            append(VERSION_TAG_HERO)
            append(RECORD_SEPARATOR).append(escape(hero.identityKey))
            append(RECORD_SEPARATOR).append(escape(hero.mediaType))
            append(RECORD_SEPARATOR).append(hero.storedAtEpochMs)
            append(RECORD_SEPARATOR).append(escape(hero.title))
            append(FIELD_SEPARATOR).append(escape(hero.originalTitle))
            append(FIELD_SEPARATOR).append(hero.year ?: "")
            append(FIELD_SEPARATOR).append(hero.runtimeMinutes ?: "")
            append(FIELD_SEPARATOR).append(escape(hero.certificationCode))
            append(FIELD_SEPARATOR).append(escape(hero.certificationRegion))
            append(FIELD_SEPARATOR).append(encodeGenreList(genres))
            append(FIELD_SEPARATOR).append(encodeRatingList(ratings))
            append(FIELD_SEPARATOR).append(escape(overview))
            append(FIELD_SEPARATOR).append(escape(hero.tagline))
            append(FIELD_SEPARATOR).append(hero.seasonCount ?: "")
            append(FIELD_SEPARATOR).append(escape(hero.status))
            append(FIELD_SEPARATOR).append(encodeArtworkLocator(hero.backdropLocator))
            append(FIELD_SEPARATOR).append(encodeArtworkLocator(hero.posterLocator))
            append(FIELD_SEPARATOR).append(encodeArtworkLocator(hero.logoLocator))
        }
        return encoded.takeIf { withinByteBound(it) }
    }

    fun decodeHero(stored: String?): HomeHeroPresentation? {
        val text = stored?.takeIf { withinByteBound(it) } ?: return null
        val header = text.split(RECORD_SEPARATOR, limit = HEADER_FIELDS_HERO + 1)
        if (header.size != HEADER_FIELDS_HERO + 1) return null
        if (header[0] != VERSION_TAG_HERO) return null
        val storedAt = header[3].toLongOrNull() ?: return null
        val parts = header[4].split(FIELD_SEPARATOR)
        if (parts.size != HERO_FIELDS) return null
        val genres = decodeGenreList(parts[6]) ?: return null
        val ratings = decodeRatingList(parts[7]) ?: return null
        val overview = unescape(parts[8])?.takeIf { it.isNotBlank() }
        if (overview != null && overview.length > HomePresentationPolicy.MAX_HERO_OVERVIEW_CHARS) {
            return null
        }

        for (index in HERO_LOCATOR_FIRST_FIELD..HERO_LOCATOR_LAST_FIELD) {
            if (!artworkLocatorFieldIsValid(parts[index])) return null
        }
        return HomeHeroPresentation(
            identityKey = unescape(header[1])?.takeIf { it.isNotBlank() } ?: return null,
            mediaType = unescape(header[2])?.takeIf { it.isNotBlank() } ?: return null,
            title = unescape(parts[0])?.takeIf { it.isNotBlank() } ?: return null,
            originalTitle = unescape(parts[1])?.takeIf { it.isNotBlank() },
            year = parts[2].toIntOrNull(),
            runtimeMinutes = parts[3].toIntOrNull(),
            certificationCode = unescape(parts[4])?.takeIf { it.isNotBlank() },
            certificationRegion = unescape(parts[5])?.takeIf { it.isNotBlank() },
            genres = genres,
            ratings = ratings,
            overview = overview,
            tagline = unescape(parts[9])?.takeIf { it.isNotBlank() },
            seasonCount = parts[10].toIntOrNull(),
            status = unescape(parts[11])?.takeIf { it.isNotBlank() },
            backdropLocator = unescape(parts[12])?.takeIf { it.isNotBlank() },
            posterLocator = unescape(parts[13])?.takeIf { it.isNotBlank() },
            logoLocator = unescape(parts[14])?.takeIf { it.isNotBlank() },
            storedAtEpochMs = storedAt,
        )
    }

    private fun encodeGenreList(genres: List<String>): String =
        genres.joinToString(LIST_SEPARATOR.toString()) { genre -> escape(genre) }

    private fun decodeGenreList(field: String): List<String>? {
        if (field.isEmpty()) return emptyList()
        val parts = field.split(LIST_SEPARATOR)
        if (parts.size > HomePresentationPolicy.MAX_HERO_GENRES) return null
        return parts.map { part -> unescape(part)?.takeIf { it.isNotBlank() } ?: return null }
    }

    private fun encodeRatingList(ratings: List<HomeHeroRating>): String =
        ratings.joinToString(LIST_SEPARATOR.toString()) { rating ->
            buildString {
                append(escape(rating.label))
                append(RATING_SEPARATOR).append(escape(rating.text))
                append(RATING_SEPARATOR).append(rating.fraction)
                append(RATING_SEPARATOR).append(escape(rating.metricId))
            }
        }

    private fun decodeRatingList(field: String): List<HomeHeroRating>? {
        if (field.isEmpty()) return emptyList()
        val parts = field.split(LIST_SEPARATOR)
        if (parts.size > HomePresentationPolicy.MAX_HERO_RATINGS) return null
        return parts.map { chip ->
            val fields = chip.split(RATING_SEPARATOR)
            if (fields.size != RATING_FIELDS) return null
            HomeHeroRating(
                label = unescape(fields[0]) ?: return null,
                text = unescape(fields[1]) ?: return null,
                fraction = fields[2].toDoubleOrNull() ?: return null,
                metricId = unescape(fields[3]) ?: "",
            )
        }
    }

    private fun encodeArtworkLocator(locator: String?): String = escape(locator)

    private fun artworkLocatorFieldIsValid(field: String): Boolean =
        field.isEmpty() || (unescape(field)?.isNotBlank() == true)

    internal fun encodeArtwork(ref: MediaArtworkRef?): String {
        if (ref == null) return ""
        return buildString {
            append(escape(ref.locator))
            append(ARTWORK_SEPARATOR).append(ref.policy.name)
            append(ARTWORK_SEPARATOR).append(escape(ref.landscapeLocator))
        }
    }

    internal fun decodeArtwork(field: String?): MediaArtworkRef? {
        val text = field ?: return null
        if (text.isEmpty()) return null
        val parts = text.split(ARTWORK_SEPARATOR)
        if (parts.size != 3) return null
        val locator = unescape(parts[0])?.takeIf { it.isNotBlank() } ?: return null
        val policy = ArtworkPolicy.entries.firstOrNull { it.name == parts[1] } ?: return null
        return MediaArtworkRef(
            locator = locator,
            policy = policy,
            landscapeLocator = unescape(parts[2])?.takeIf { it.isNotBlank() },
        )
    }

    private fun artworkFieldIsValid(field: String): Boolean =
        field.isEmpty() || decodeArtwork(field) != null

    private fun decodeTitleId(field: String?): TitleId? {
        val raw = unescape(field)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { TitleId.fromString(raw) }.getOrNull()
    }

    private fun withinByteBound(text: String): Boolean =
        text.length <= HomePresentationPolicy.MAX_RECORD_BYTES &&
            text.toByteArray(Charsets.UTF_8).size <= HomePresentationPolicy.MAX_RECORD_BYTES

    private fun escape(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size)
        for (byte in bytes) {
            val unsigned = byte.toInt() and 0xFF
            if (unsigned < 128 && UNRESERVED[unsigned]) {
                out.append(unsigned.toChar())
            } else {
                out.append('%')
                out.append(HEX[unsigned shr 4])
                out.append(HEX[unsigned and 0x0F])
            }
        }
        return out.toString()
    }

    private fun unescape(field: String?): String? {
        if (field == null) return null
        if (field.isEmpty()) return ""
        if (field.indexOf('%') < 0) return field
        val out = java.io.ByteArrayOutputStream(field.length)
        var index = 0
        while (index < field.length) {
            val char = field[index]
            if (char == '%') {
                if (index + 2 >= field.length) return null
                val high = HEX.indexOf(field[index + 1].uppercaseChar())
                val low = HEX.indexOf(field[index + 2].uppercaseChar())
                if (high < 0 || low < 0) return null
                out.write((high shl 4) or low)
                index += 3
            } else {
                out.write(char.code)
                index += 1
            }
        }
        return runCatching { out.toByteArray().toString(Charsets.UTF_8) }.getOrNull()
    }

    private const val HEADER_FIELDS_ROWS = 8
    private const val CARD_FIELDS = 9
    private const val HEADER_FIELDS_COLLECTION = 8
    private const val TILE_FIELDS = 8
    private const val HEADER_FIELDS_IDENTITY = 3
    private const val IDENTITY_FIELDS = 8
    private const val HEADER_FIELDS_HERO = 4
    private const val HERO_FIELDS = 15
    private const val RATING_FIELDS = 4
    private const val HERO_LOCATOR_FIRST_FIELD = 12
    private const val HERO_LOCATOR_LAST_FIELD = 14
}
