package dev.infusion.core.domain.collections

import org.json.JSONArray
import org.json.JSONObject

object CollectionPackCodec {

    const val PAYLOAD_VERSION: Int = 1

    fun encode(
        packs: List<CollectionPack>,
        unreadableRecords: List<String> = emptyList(),
    ): String {
        val root = JSONObject()
            .put(FIELD_VERSION, PAYLOAD_VERSION)
            .put(FIELD_PACKS, JSONArray().apply { packs.forEach { put(encodePack(it)) } })
            .put(FIELD_UNREADABLE, JSONArray().apply { unreadableRecords.forEach { put(it) } })
        return root.toString()
    }

    fun decode(stored: String?): CollectionPackConfiguration {
        if (stored == null) return CollectionPackConfiguration.Absent
        val root = try {
            JSONObject(stored)
        } catch (_: org.json.JSONException) {
            return CollectionPackConfiguration(configured = true, unreadablePayload = stored)
        }
        if (root.optInt(FIELD_VERSION, -1) != PAYLOAD_VERSION) {
            return CollectionPackConfiguration(configured = true, unreadablePayload = stored)
        }
        val array = root.optJSONArray(FIELD_PACKS)
            ?: return CollectionPackConfiguration(configured = true, unreadablePayload = stored)
        val packs = ArrayList<CollectionPack>()
        val unreadable = ArrayList<String>()
        val seen = HashSet<String>()
        for (index in 0 until array.length()) {
            val element = array.opt(index)
            val pack = (element as? JSONObject)?.let { decodePack(it) }
            if (pack == null || pack.packId in seen) {

                if (element != null) unreadable += element.toString()
                continue
            }
            seen += pack.packId
            packs += pack
        }
        val preserved = root.optJSONArray(FIELD_UNREADABLE)
        if (preserved != null) {
            for (index in 0 until preserved.length()) {
                (preserved.opt(index) as? String)?.let { unreadable += it }
            }
        }
        return CollectionPackConfiguration(
            configured = true,
            packs = packs,
            unreadableRecords = unreadable,
        )
    }

    private fun encodePack(pack: CollectionPack): JSONObject = JSONObject()
        .put(FIELD_ID, pack.packId)
        .put(FIELD_SCHEMA, pack.schemaVersion)
        .put(FIELD_NAME, pack.name)
        .put(
            FIELD_PROVENANCE,
            JSONObject().apply {
                pack.provenance.sourceUrl?.let { put(FIELD_PROV_URL, it) }
                put(FIELD_PROV_AT, pack.provenance.importedAtMillis)
            },
        )
        .put(FIELD_GROUPS, JSONArray().apply { pack.groups.forEach { put(encodeGroup(it)) } })

    private fun encodeGroup(group: CollectionGroup): JSONObject = JSONObject()
        .put(FIELD_KEY, group.groupKey)
        .put(FIELD_TITLE, group.title)
        .put(FIELD_TILES, JSONArray().apply { group.tiles.forEach { put(encodeTile(it)) } })

    private fun encodeTile(tile: CollectionTile): JSONObject = JSONObject()
        .put(FIELD_KEY, tile.tileKey)
        .put(FIELD_TITLE, tile.title)
        .put(FIELD_SHAPE, tile.shape.name)
        .put(FIELD_HIDE_TITLE, tile.hideTitle)
        .put(
            FIELD_ARTWORK,
            JSONObject().apply {
                tile.artwork.cover?.let { put(FIELD_ART_COVER, it) }
                tile.artwork.logo?.let { put(FIELD_ART_LOGO, it) }
                tile.artwork.backdrop?.let { put(FIELD_ART_BACKDROP, it) }
                tile.artwork.focus?.let { put(FIELD_ART_FOCUS, it) }
                put(FIELD_ART_FOCUS_ON, tile.artwork.focusEnabled)
                tile.artwork.heroVideo?.let { put(FIELD_ART_HERO, it) }
            },
        )
        .put(FIELD_ROWS, JSONArray().apply { tile.rows.forEach { put(encodeRow(it)) } })

    private fun encodeRow(row: CollectionRow): JSONObject = JSONObject()
        .put(FIELD_KEY, row.rowKey)
        .put(FIELD_TITLE, row.title)
        .put(FIELD_KIND, row.mediaKind.name)
        .put(FIELD_DEFINITION, encodeDefinition(row.definition))

    private fun encodeDefinition(definition: CollectionSourceDefinition): JSONObject = JSONObject()
        .put(FIELD_PROVIDER, definition.provider)
        .apply {
            definition.tmdbSourceType?.let { put(FIELD_TMDB_TYPE, it) }
            definition.tmdbId?.let { put(FIELD_TMDB_ID, it) }
            definition.traktListId?.let { put(FIELD_TRAKT_LIST, it) }
            definition.owner?.let { put(FIELD_OWNER, it) }
            definition.addonId?.let { put(FIELD_ADDON_ID, it) }
            definition.catalogId?.let { put(FIELD_CATALOG_ID, it) }
            definition.catalogType?.let { put(FIELD_CATALOG_TYPE, it) }
            definition.mediaType?.let { put(FIELD_MEDIA_TYPE, it) }
            definition.sortBy?.let { put(FIELD_SORT_BY, it) }
            definition.sortHow?.let { put(FIELD_SORT_HOW, it) }
            if (definition.filters.isNotEmpty()) {
                put(FIELD_FILTERS, JSONObject().apply { definition.filters.forEach { (k, v) -> put(k, v) } })
            }
        }

    private fun decodePack(json: JSONObject): CollectionPack? {
        val id = json.nonBlank(FIELD_ID) ?: return null
        if (!CollectionPackId.isPackId(id)) return null
        val schema = json.optInt(FIELD_SCHEMA, -1)
        if (schema != CollectionPack.CURRENT_SCHEMA_VERSION) return null
        val name = json.optString(FIELD_NAME).take(CollectionPackLimits.MAX_TITLE_LENGTH)
        val provenanceObject = json.optJSONObject(FIELD_PROVENANCE)
        val provenance = CollectionPackProvenance(
            sourceUrl = provenanceObject?.nonBlank(FIELD_PROV_URL)
                ?.take(CollectionPackLimits.MAX_PROVENANCE_URL_LENGTH),
            importedAtMillis = provenanceObject?.optLong(FIELD_PROV_AT, 0L) ?: 0L,
        )
        val groupsArray = json.optJSONArray(FIELD_GROUPS) ?: return null
        if (groupsArray.length() > CollectionPackLimits.MAX_GROUPS_PER_PACK) return null
        val groups = ArrayList<CollectionGroup>()
        var tiles = 0
        var rows = 0
        for (i in 0 until groupsArray.length()) {
            val groupObject = groupsArray.optJSONObject(i) ?: continue
            val group = decodeGroup(groupObject) ?: continue
            tiles += group.tiles.size
            rows += group.tiles.sumOf { it.rows.size }
            if (tiles > CollectionPackLimits.MAX_TILES_PER_PACK) return null
            if (rows > CollectionPackLimits.MAX_ROWS_PER_PACK) return null
            groups += group
        }
        return CollectionPack(
            packId = id,
            schemaVersion = schema,
            name = name,
            provenance = provenance,
            groups = groups,
        )
    }

    private fun decodeGroup(json: JSONObject): CollectionGroup? {
        val key = json.nonBlank(FIELD_KEY) ?: return null
        if (key.length > CollectionPackLimits.MAX_KEY_LENGTH) return null
        val tilesArray = json.optJSONArray(FIELD_TILES) ?: JSONArray()
        if (tilesArray.length() > CollectionPackLimits.MAX_TILES_PER_GROUP) return null
        val tiles = ArrayList<CollectionTile>()
        for (i in 0 until tilesArray.length()) {
            val tileObject = tilesArray.optJSONObject(i) ?: continue
            (decodeTile(tileObject) ?: continue).let { tiles += it }
        }
        return CollectionGroup(
            groupKey = key,
            title = json.optString(FIELD_TITLE).take(CollectionPackLimits.MAX_TITLE_LENGTH),
            tiles = tiles,
        )
    }

    private fun decodeTile(json: JSONObject): CollectionTile? {
        val key = json.nonBlank(FIELD_KEY) ?: return null
        if (key.length > CollectionPackLimits.MAX_KEY_LENGTH) return null
        val rowsArray = json.optJSONArray(FIELD_ROWS) ?: JSONArray()
        if (rowsArray.length() > CollectionPackLimits.MAX_ROWS_PER_TILE) return null
        val rows = ArrayList<CollectionRow>()
        for (i in 0 until rowsArray.length()) {
            val rowObject = rowsArray.optJSONObject(i) ?: continue
            (decodeRow(rowObject) ?: continue).let { rows += it }
        }
        val artworkObject = json.optJSONObject(FIELD_ARTWORK)
        val artwork = CollectionTileArtwork(
            cover = artworkObject?.artworkRef(FIELD_ART_COVER),
            logo = artworkObject?.artworkRef(FIELD_ART_LOGO),
            backdrop = artworkObject?.artworkRef(FIELD_ART_BACKDROP),
            focus = artworkObject?.artworkRef(FIELD_ART_FOCUS),
            focusEnabled = artworkObject?.optBoolean(FIELD_ART_FOCUS_ON, true) ?: true,
            heroVideo = artworkObject?.artworkRef(FIELD_ART_HERO),
        )
        return CollectionTile(
            tileKey = key,
            title = json.optString(FIELD_TITLE).take(CollectionPackLimits.MAX_TITLE_LENGTH),
            shape = runCatching { CollectionTileShape.valueOf(json.optString(FIELD_SHAPE)) }
                .getOrDefault(CollectionTileShape.UNKNOWN),
            hideTitle = json.optBoolean(FIELD_HIDE_TITLE, false),
            artwork = artwork,
            rows = rows,
        )
    }

    private fun decodeRow(json: JSONObject): CollectionRow? {
        val key = json.nonBlank(FIELD_KEY) ?: return null
        if (key.length > CollectionPackLimits.MAX_KEY_LENGTH) return null
        return CollectionRow(
            rowKey = key,
            title = json.optString(FIELD_TITLE).take(CollectionPackLimits.MAX_TITLE_LENGTH),
            mediaKind = runCatching { CollectionMediaKind.valueOf(json.optString(FIELD_KIND)) }
                .getOrDefault(CollectionMediaKind.MIXED),
            definition = decodeDefinition(json.optJSONObject(FIELD_DEFINITION)),
        )
    }

    private fun decodeDefinition(json: JSONObject?): CollectionSourceDefinition {
        if (json == null) return CollectionSourceDefinition()
        val filters = LinkedHashMap<String, String>()
        json.optJSONObject(FIELD_FILTERS)?.let { filtersObject ->
            val keys = filtersObject.keys()
            while (keys.hasNext() && filters.size < CollectionPackLimits.MAX_FILTERS) {
                val key = keys.next()
                if (key.length > CollectionPackLimits.MAX_FILTER_KEY_LENGTH) continue
                filters[key] = filtersObject.optString(key).take(CollectionPackLimits.MAX_FILTER_VALUE_LENGTH)
            }
        }
        return CollectionSourceDefinition(
            provider = json.optString(FIELD_PROVIDER).take(CollectionPackLimits.MAX_FIELD_LENGTH),
            tmdbSourceType = json.fieldOrNull(FIELD_TMDB_TYPE),
            tmdbId = json.fieldOrNull(FIELD_TMDB_ID),
            traktListId = json.fieldOrNull(FIELD_TRAKT_LIST),
            owner = json.fieldOrNull(FIELD_OWNER),
            addonId = json.fieldOrNull(FIELD_ADDON_ID),
            catalogId = json.fieldOrNull(FIELD_CATALOG_ID),
            catalogType = json.fieldOrNull(FIELD_CATALOG_TYPE),
            mediaType = json.fieldOrNull(FIELD_MEDIA_TYPE),
            sortBy = json.fieldOrNull(FIELD_SORT_BY),
            sortHow = json.fieldOrNull(FIELD_SORT_HOW),
            filters = filters,
        )
    }

    private const val FIELD_VERSION = "v"
    private const val FIELD_PACKS = "packs"
    private const val FIELD_UNREADABLE = "unreadable"
    private const val FIELD_ID = "id"
    private const val FIELD_SCHEMA = "schema"
    private const val FIELD_NAME = "name"
    private const val FIELD_PROVENANCE = "prov"
    private const val FIELD_PROV_URL = "url"
    private const val FIELD_PROV_AT = "at"
    private const val FIELD_GROUPS = "groups"
    private const val FIELD_TILES = "tiles"
    private const val FIELD_ROWS = "rows"
    private const val FIELD_KEY = "key"
    private const val FIELD_TITLE = "title"
    private const val FIELD_SHAPE = "shape"
    private const val FIELD_HIDE_TITLE = "hideTitle"
    private const val FIELD_ARTWORK = "art"
    private const val FIELD_ART_COVER = "cover"
    private const val FIELD_ART_LOGO = "logo"
    private const val FIELD_ART_BACKDROP = "backdrop"
    private const val FIELD_ART_FOCUS = "focus"
    private const val FIELD_ART_FOCUS_ON = "focusOn"
    private const val FIELD_ART_HERO = "heroVideo"
    private const val FIELD_KIND = "kind"
    private const val FIELD_DEFINITION = "def"
    private const val FIELD_PROVIDER = "provider"
    private const val FIELD_TMDB_TYPE = "tmdbType"
    private const val FIELD_TMDB_ID = "tmdbId"
    private const val FIELD_TRAKT_LIST = "traktListId"
    private const val FIELD_OWNER = "owner"
    private const val FIELD_ADDON_ID = "addonId"
    private const val FIELD_CATALOG_ID = "catalogId"
    private const val FIELD_CATALOG_TYPE = "catalogType"
    private const val FIELD_MEDIA_TYPE = "mediaType"
    private const val FIELD_SORT_BY = "sortBy"
    private const val FIELD_SORT_HOW = "sortHow"
    private const val FIELD_FILTERS = "filters"
}

private fun JSONObject.nonBlank(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }

private fun JSONObject.fieldOrNull(key: String): String? =
    nonBlank(key)?.take(CollectionPackLimits.MAX_FIELD_LENGTH)

private fun JSONObject.artworkRef(key: String): String? =
    nonBlank(key)?.take(CollectionPackLimits.MAX_ARTWORK_REF_LENGTH)
