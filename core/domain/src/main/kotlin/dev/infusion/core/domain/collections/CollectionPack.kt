package dev.infusion.core.domain.collections

object CollectionPackLimits {

    const val MAX_PACKS_PER_PROFILE: Int = 24

    const val MAX_GROUPS_PER_PACK: Int = 64

    const val MAX_TILES_PER_GROUP: Int = 512

    const val MAX_ROWS_PER_TILE: Int = 128

    const val MAX_TILES_PER_PACK: Int = 4096

    const val MAX_ROWS_PER_PACK: Int = 20_000

    const val MAX_DOCUMENT_BYTES: Int = 16 * 1024 * 1024

    const val MAX_KEY_LENGTH: Int = 160

    const val MAX_PACK_ID_LENGTH: Int = 96

    const val MAX_TITLE_LENGTH: Int = 200

    const val MAX_FIELD_LENGTH: Int = 240

    const val MAX_ARTWORK_REF_LENGTH: Int = 1024

    const val MAX_FILTERS: Int = 48

    const val MAX_FILTER_KEY_LENGTH: Int = 64

    const val MAX_FILTER_VALUE_LENGTH: Int = 200

    const val MAX_PROVENANCE_URL_LENGTH: Int = 2048
}

data class CollectionPack(
    val packId: String,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,

    val name: String,
    val provenance: CollectionPackProvenance = CollectionPackProvenance(),
    val groups: List<CollectionGroup> = emptyList(),
) {
    companion object {

        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

data class CollectionPackProvenance(
    val sourceUrl: String? = null,
    val importedAtMillis: Long = 0L,
)

data class CollectionGroup(

    val groupKey: String,

    val title: String,
    val tiles: List<CollectionTile> = emptyList(),
)

data class CollectionTile(

    val tileKey: String,

    val title: String,
    val shape: CollectionTileShape = CollectionTileShape.LANDSCAPE,

    val hideTitle: Boolean = false,
    val artwork: CollectionTileArtwork = CollectionTileArtwork(),

    val rows: List<CollectionRow> = emptyList(),
)

enum class CollectionTileShape {
    LANDSCAPE,
    POSTER,

    UNKNOWN,
    ;

    companion object {
        fun fromDocument(value: String?): CollectionTileShape = when (value?.trim()?.uppercase()) {
            "LANDSCAPE" -> LANDSCAPE
            "POSTER" -> POSTER
            else -> UNKNOWN
        }
    }
}

data class CollectionTileArtwork(
    val cover: String? = null,
    val logo: String? = null,
    val backdrop: String? = null,
    val focus: String? = null,

    val focusEnabled: Boolean = true,
    val heroVideo: String? = null,
)

enum class CollectionMediaKind {
    MOVIE,
    SERIES,

    MIXED,
    ;

    companion object {
        fun fromDocument(value: String?): CollectionMediaKind = when (value?.trim()?.uppercase()) {
            "MOVIE", "MOVIES" -> MOVIE
            "TV", "SERIES", "SHOW", "SHOWS" -> SERIES
            else -> MIXED
        }
    }
}

data class CollectionRow(

    val rowKey: String,

    val title: String,
    val mediaKind: CollectionMediaKind = CollectionMediaKind.MIXED,
    val definition: CollectionSourceDefinition = CollectionSourceDefinition(),
)

data class CollectionSourceDefinition(

    val provider: String = "",

    val tmdbSourceType: String? = null,

    val tmdbId: String? = null,

    val traktListId: String? = null,

    val owner: String? = null,

    val addonId: String? = null,

    val catalogId: String? = null,

    val catalogType: String? = null,

    val mediaType: String? = null,

    val sortBy: String? = null,

    val sortHow: String? = null,

    val filters: Map<String, String> = emptyMap(),
) {

    val isBlank: Boolean
        get() = provider.isBlank() &&
            tmdbId == null && traktListId == null && addonId == null && catalogId == null
}

data class CollectionPackConfiguration(
    val configured: Boolean,
    val packs: List<CollectionPack> = emptyList(),
    val unreadableRecords: List<String> = emptyList(),
    val unreadablePayload: String? = null,
) {
    companion object {
        val Absent: CollectionPackConfiguration = CollectionPackConfiguration(configured = false)
    }
}

object CollectionKeys {

    fun groupId(packId: String, groupKey: String): String = "$packId/group:$groupKey"

    fun tileId(packId: String, groupKey: String, tileKey: String): String =
        "${groupId(packId, groupKey)}/tile:$tileKey"

    fun rowId(packId: String, groupKey: String, tileKey: String, rowKey: String): String =
        "${tileId(packId, groupKey, tileKey)}/row:$rowKey"
}

object CollectionPackId {
    private const val PREFIX = "pack:"

    fun forSourceUrl(url: String): String {
        val normalised = normalizeSourceUrl(url)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(normalised.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return PREFIX + digest.take(16)
    }

    fun mint(): String = PREFIX + java.util.UUID.randomUUID().toString().replace("-", "").take(16)

    fun normalizeSourceUrl(url: String): String = url.trim().substringBefore('#')

    fun isPackId(value: String): Boolean =
        value.startsWith(PREFIX) && value.length <= CollectionPackLimits.MAX_PACK_ID_LENGTH
}

data class CollectionGroupRef(
    val packId: String,
    val groupKey: String,
)
