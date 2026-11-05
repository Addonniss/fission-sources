package dev.infusion.core.domain.collections

import org.json.JSONArray
import org.json.JSONObject

object CollectionImporter {

    fun parse(documentText: String): CollectionImportOutcome {
        if (documentText.isBlank()) return CollectionImportOutcome.Invalid(REASON_BLANK_DOCUMENT)
        if (documentText.toByteArray(Charsets.UTF_8).size > CollectionPackLimits.MAX_DOCUMENT_BYTES) {
            return CollectionImportOutcome.Invalid(REASON_TOO_LARGE)
        }
        val root: Any = try {
            org.json.JSONTokener(documentText).nextValue()
        } catch (_: org.json.JSONException) {
            return CollectionImportOutcome.Invalid(REASON_MALFORMED)
        }

        val (explicitName, collections) = when (root) {

            is JSONArray -> null to root
            is JSONObject -> {
                val wrapped = root.optJSONArray("collections")
                when {

                    wrapped != null -> root.optStringOrNull("name") to wrapped
                    root.optJSONArray("folders") != null ->
                        root.optStringOrNull("name") to JSONArray().put(root)
                    else -> return CollectionImportOutcome.Invalid(REASON_UNRECOGNISED_SHAPE)
                }
            }
            else -> return CollectionImportOutcome.Invalid(REASON_UNRECOGNISED_SHAPE)
        }

        val groups = ArrayList<CollectionGroup>()
        var totalTiles = 0
        var totalRows = 0
        var supportedRows = 0
        var unsupportedRows = 0
        val groupKeys = mutableSetOf<String>()

        for (index in 0 until collections.length()) {
            if (groups.size >= CollectionPackLimits.MAX_GROUPS_PER_PACK) {
                return CollectionImportOutcome.Invalid(REASON_TOO_MANY_GROUPS)
            }
            val collection = collections.optJSONObject(index) ?: continue

            val folders = collection.optJSONArray("folders") ?: continue
            val groupKey = KeyFactory.key(
                supplied = collection.optStringOrNull("id"),
                seed = "group|" + (collection.optStringOrNull("id") ?: collection.optStringOrNull("title").orEmpty()),
                used = groupKeys,
            )
            val tiles = ArrayList<CollectionTile>()
            val tileKeys = mutableSetOf<String>()
            for (folderIndex in 0 until folders.length()) {
                if (tiles.size >= CollectionPackLimits.MAX_TILES_PER_GROUP) {
                    return CollectionImportOutcome.Invalid(REASON_TOO_MANY_TILES)
                }
                if (totalTiles >= CollectionPackLimits.MAX_TILES_PER_PACK) {
                    return CollectionImportOutcome.Invalid(REASON_TOO_MANY_TILES)
                }
                val folder = folders.optJSONObject(folderIndex) ?: continue
                val tileKey = KeyFactory.key(
                    supplied = folder.optStringOrNull("id"),
                    seed = "tile|" + listOf(
                        folder.optStringOrNull("id").orEmpty(),
                        folder.optStringOrNull("title").orEmpty(),
                    ).joinToString("|"),
                    used = tileKeys,
                )
                val rows = parseRows(
                    folder = folder,
                    onSupported = { supportedRows++ },
                    onUnsupported = { unsupportedRows++ },
                    rowBudget = { totalRows < CollectionPackLimits.MAX_ROWS_PER_PACK },
                )
                totalRows += rows.size
                if (totalRows > CollectionPackLimits.MAX_ROWS_PER_PACK) {
                    return CollectionImportOutcome.Invalid(REASON_TOO_MANY_ROWS)
                }
                tiles += CollectionTile(
                    tileKey = tileKey,
                    title = folder.optStringOrNull("title").orEmpty(),
                    shape = CollectionTileShape.fromDocument(folder.optStringOrNull("tileShape")),
                    hideTitle = folder.optBooleanOrNull("hideTitle") ?: false,
                    artwork = parseArtwork(folder),
                    rows = rows,
                )
                totalTiles++
            }
            groups += CollectionGroup(
                groupKey = groupKey,
                title = collection.optStringOrNull("title").orEmpty(),
                tiles = tiles,
            )
        }

        val totalRowsParsed = supportedRows + unsupportedRows
        if (totalRowsParsed == 0) {
            return CollectionImportOutcome.NoUsableSource(unsupportedSources = 0)
        }
        if (supportedRows == 0) {
            return CollectionImportOutcome.NoUsableSource(unsupportedSources = unsupportedRows)
        }
        val document = ImportedCollectionDocument(
            name = explicitName?.take(CollectionPackLimits.MAX_TITLE_LENGTH),
            groups = groups,
            totalSources = totalRowsParsed,
            supportedSources = supportedRows,
            unsupportedSources = unsupportedRows,
        )
        return CollectionImportOutcome.Parsed(document)
    }

    private fun parseRows(
        folder: JSONObject,
        onSupported: () -> Unit,
        onUnsupported: () -> Unit,
        rowBudget: () -> Boolean,
    ): List<CollectionRow> {
        val sources = folder.optJSONArray("sources") ?: return emptyList()
        val rows = ArrayList<CollectionRow>()
        val used = mutableSetOf<String>()
        for (index in 0 until sources.length()) {
            if (rows.size >= CollectionPackLimits.MAX_ROWS_PER_TILE) break
            if (!rowBudget()) break
            val source = sources.optJSONObject(index) ?: continue
            val definition = parseDefinition(source)
            val rowKey = KeyFactory.key(
                supplied = source.optStringOrNull("id"),

                seed = "row|" + definition.identitySeed(),
                used = used,
            )
            val title = source.optStringOrNull("title")
                ?: source.optStringOrNull("name")
                ?: source.optStringOrNull("genre")
                ?: ""
            val mediaKind = CollectionMediaKind.fromDocument(
                source.optStringOrNull("mediaType") ?: source.optStringOrNull("type"),
            )
            if (CollectionSourceSupport.isSupported(definition)) onSupported() else onUnsupported()
            rows += CollectionRow(
                rowKey = rowKey,
                title = title.take(CollectionPackLimits.MAX_TITLE_LENGTH),
                mediaKind = mediaKind,
                definition = definition,
            )
        }
        return rows
    }

    private fun parseDefinition(source: JSONObject): CollectionSourceDefinition {
        val filtersObject = source.optJSONObject("filters")
        val filters = LinkedHashMap<String, String>()
        if (filtersObject != null) {
            val keys = filtersObject.keys()
            while (keys.hasNext() && filters.size < CollectionPackLimits.MAX_FILTERS) {
                val key = keys.next()
                if (key.length > CollectionPackLimits.MAX_FILTER_KEY_LENGTH) continue
                val value = filterValue(filtersObject.opt(key)) ?: continue
                filters[key] = value.take(CollectionPackLimits.MAX_FILTER_VALUE_LENGTH)
            }
        }
        return CollectionSourceDefinition(
            provider = source.optStringOrNull("provider").orEmpty().take(CollectionPackLimits.MAX_FIELD_LENGTH),
            tmdbSourceType = source.optStringOrNull("tmdbSourceType")?.take(CollectionPackLimits.MAX_FIELD_LENGTH),
            tmdbId = source.optScalarOrNull("tmdbId"),
            traktListId = source.optScalarOrNull("traktListId"),
            owner = source.optStringOrNull("owner")?.take(CollectionPackLimits.MAX_FIELD_LENGTH),
            addonId = source.optStringOrNull("addonId")?.take(CollectionPackLimits.MAX_FIELD_LENGTH),
            catalogId = source.optStringOrNull("catalogId")?.take(CollectionPackLimits.MAX_FIELD_LENGTH),
            catalogType = source.optStringOrNull("type")?.take(CollectionPackLimits.MAX_FIELD_LENGTH),
            mediaType = source.optStringOrNull("mediaType")?.take(CollectionPackLimits.MAX_FIELD_LENGTH),
            sortBy = source.optStringOrNull("sortBy")?.take(CollectionPackLimits.MAX_FIELD_LENGTH),
            sortHow = source.optStringOrNull("sortHow")?.take(CollectionPackLimits.MAX_FIELD_LENGTH),
            filters = filters,
        )
    }

    private fun parseArtwork(folder: JSONObject): CollectionTileArtwork = CollectionTileArtwork(
        cover = folder.optArtworkRef("coverImageUrl"),
        logo = folder.optArtworkRef("titleLogoUrl"),
        backdrop = folder.optArtworkRef("heroBackdropUrl"),
        focus = folder.optArtworkRef("focusGifUrl"),
        focusEnabled = folder.optBooleanOrNull("focusGifEnabled") ?: true,
        heroVideo = folder.optArtworkRef("heroVideoUrl"),
    )

    private fun filterValue(value: Any?): String? = when (value) {
        null, JSONObject.NULL -> null
        is String -> value.trim().takeIf { it.isNotEmpty() }
        is Number -> value.toString()
        is Boolean -> value.toString()
        else -> null
    }

    internal const val DEFAULT_PACK_NAME = "Imported Collection"
    internal const val REASON_BLANK_DOCUMENT = "the document is empty"
    internal const val REASON_MALFORMED = "the document is not valid JSON"
    internal const val REASON_UNRECOGNISED_SHAPE = "the document is not a recognised collection format"
    internal const val REASON_TOO_LARGE = "the document is larger than this build accepts"
    internal const val REASON_TOO_MANY_GROUPS = "the document has more collections than this build accepts"
    internal const val REASON_TOO_MANY_TILES = "the document has more tiles than this build accepts"
    internal const val REASON_TOO_MANY_ROWS = "the document has more sources than this build accepts"
}

internal object KeyFactory {
    fun key(supplied: String?, seed: String, used: MutableSet<String>): String {
        val suppliedClean = supplied?.trim()?.takeIf { it.isNotEmpty() }
            ?.take(CollectionPackLimits.MAX_KEY_LENGTH)
        val base = suppliedClean ?: (FALLBACK_PREFIX + digest(seed).take(FALLBACK_DIGEST_LENGTH))
        var candidate = base
        var suffix = 1
        while (!used.add(candidate)) {
            candidate = "$base-$suffix"
            suffix++
        }
        return candidate
    }

    private fun digest(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    const val FALLBACK_PREFIX = "k"
    const val FALLBACK_DIGEST_LENGTH = 16
}

internal fun CollectionSourceDefinition.identitySeed(): String = listOf(
    provider,
    tmdbSourceType.orEmpty(),
    tmdbId.orEmpty(),
    traktListId.orEmpty(),
    owner.orEmpty(),
    addonId.orEmpty(),
    catalogId.orEmpty(),
    catalogType.orEmpty(),
    mediaType.orEmpty(),
    sortBy.orEmpty(),
    sortHow.orEmpty(),
    filters.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" },
).joinToString("|")

object CollectionSourceSupport {

    val SUPPORTED_TMDB_TYPES: Set<String> = setOf("DISCOVER", "COLLECTION", "COMPANY", "NETWORK", "LIST")

    fun isSupported(definition: CollectionSourceDefinition): Boolean =
        when (definition.provider.trim().lowercase()) {
            PROVIDER_TMDB -> tmdbSupported(definition)
            PROVIDER_TRAKT -> !definition.traktListId.isNullOrBlank()
            PROVIDER_ADDON -> !definition.addonId.isNullOrBlank() && !definition.catalogId.isNullOrBlank()
            else -> false
        }

    private fun tmdbSupported(definition: CollectionSourceDefinition): Boolean {
        val type = definition.tmdbSourceType?.trim()?.uppercase() ?: return false
        if (type !in SUPPORTED_TMDB_TYPES) return false

        return type == "DISCOVER" || !definition.tmdbId.isNullOrBlank()
    }

    fun unsupportedReason(definition: CollectionSourceDefinition): String = when {
        definition.provider.isBlank() -> "no source provider"
        definition.provider.equals(PROVIDER_TMDB, ignoreCase = true) ->
            "TMDB ${definition.tmdbSourceType ?: "source"} is not supported"
        definition.provider.equals(PROVIDER_TRAKT, ignoreCase = true) -> "Trakt list is not addressable"
        definition.provider.equals(PROVIDER_ADDON, ignoreCase = true) -> "add-on catalog is not addressable"
        else -> "${definition.provider} sources are not supported"
    }

    const val PROVIDER_TMDB = "tmdb"
    const val PROVIDER_TRAKT = "trakt"
    const val PROVIDER_MDBLIST = "mdblist"
    const val PROVIDER_ADDON = "addon"
}

data class ImportedCollectionDocument(

    val name: String?,
    val groups: List<CollectionGroup>,
    val totalSources: Int,
    val supportedSources: Int,
    val unsupportedSources: Int,
) {

    val tileCount: Int get() = groups.sumOf { it.tiles.size }
}

object CollectionPackName {
    fun fromSourceUrl(url: String): String {
        val path = url.trim().substringBefore('#').substringBefore('?')
        val leaf = path.substringAfterLast('/').trim()
        val stem = leaf.substringBeforeLast('.', leaf)
        val readable = stem
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        return readable
            .take(CollectionPackLimits.MAX_TITLE_LENGTH)
            .ifBlank { CollectionImporter.DEFAULT_PACK_NAME }
    }
}

sealed interface CollectionImportOutcome {

    data class Parsed(val document: ImportedCollectionDocument) : CollectionImportOutcome

    data class Invalid(val reason: String) : CollectionImportOutcome

    data class NoUsableSource(val unsupportedSources: Int) : CollectionImportOutcome
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }

private fun JSONObject.optScalarOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return when (val value = opt(key)) {
        is Number -> value.toString()
        is String -> value.trim().takeIf { it.isNotEmpty() }
        else -> null
    }
}

private fun JSONObject.optBooleanOrNull(key: String): Boolean? {
    if (!has(key) || isNull(key)) return null
    return opt(key) as? Boolean
}

private fun JSONObject.optArtworkRef(key: String): String? =
    optStringOrNull(key)?.take(CollectionPackLimits.MAX_ARTWORK_REF_LENGTH)
