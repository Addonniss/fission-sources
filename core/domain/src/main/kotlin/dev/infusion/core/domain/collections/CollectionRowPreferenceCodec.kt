package dev.infusion.core.domain.collections

import org.json.JSONArray
import org.json.JSONObject

object CollectionRowPreferenceCodec {

    const val PAYLOAD_VERSION: Int = 1

    fun encode(
        pages: List<CollectionPagePreference>,
        unreadableRecords: List<String> = emptyList(),
    ): String {
        val root = JSONObject()
            .put(FIELD_VERSION, PAYLOAD_VERSION)
            .put(FIELD_PAGES, JSONArray().apply { pages.forEach { put(encodePage(it)) } })
            .put(FIELD_UNREADABLE, JSONArray().apply { unreadableRecords.forEach { put(it) } })
        return root.toString()
    }

    fun decode(stored: String?): CollectionRowPreferenceConfiguration {
        if (stored == null) return CollectionRowPreferenceConfiguration.Absent
        val root = try {
            JSONObject(stored)
        } catch (_: org.json.JSONException) {
            return CollectionRowPreferenceConfiguration(configured = true, unreadablePayload = stored)
        }
        if (root.optInt(FIELD_VERSION, -1) != PAYLOAD_VERSION) {
            return CollectionRowPreferenceConfiguration(configured = true, unreadablePayload = stored)
        }
        val array = root.optJSONArray(FIELD_PAGES)
            ?: return CollectionRowPreferenceConfiguration(configured = true, unreadablePayload = stored)
        if (array.length() > CollectionRowPreferenceLimits.MAX_PAGES_PER_PROFILE) {
            return CollectionRowPreferenceConfiguration(configured = true, unreadablePayload = stored)
        }
        val pages = ArrayList<CollectionPagePreference>()
        val unreadable = ArrayList<String>()
        val seen = HashSet<String>()
        for (index in 0 until array.length()) {
            val element = array.opt(index)
            val page = (element as? JSONObject)?.let { decodePage(it) }
            if (page == null || page.pageId in seen) {
                if (element != null) unreadable += element.toString()
                continue
            }
            seen += page.pageId
            pages += page
        }
        val preserved = root.optJSONArray(FIELD_UNREADABLE)
        if (preserved != null) {
            for (index in 0 until preserved.length()) {
                (preserved.opt(index) as? String)?.let { unreadable += it }
            }
        }
        return CollectionRowPreferenceConfiguration(
            configured = true,
            pages = pages,
            unreadableRecords = unreadable,
        )
    }

    private fun encodePage(page: CollectionPagePreference): JSONObject = JSONObject()
        .put(FIELD_PACK, page.packId)
        .put(FIELD_GROUP, page.groupKey)
        .put(FIELD_TILE, page.tileKey)
        .put(
            FIELD_ROWS,
            JSONArray().apply {
                page.entries.forEach { entry ->
                    put(
                        JSONObject()
                            .put(FIELD_ROW_KEY, entry.rowKey)
                            .put(FIELD_ROW_ENABLED, entry.enabled),
                    )
                }
            },
        )
        .put(FIELD_UNREADABLE, JSONArray().apply { page.unreadableEntries.forEach { put(it) } })

    private fun decodePage(json: JSONObject): CollectionPagePreference? {
        val packId = json.nonBlank(FIELD_PACK) ?: return null
        if (!CollectionPackId.isPackId(packId)) return null
        val groupKey = json.nonBlank(FIELD_GROUP)?.bounded(CollectionRowPreferenceLimits.MAX_ROW_KEY_LENGTH)
            ?: return null
        val tileKey = json.nonBlank(FIELD_TILE)?.bounded(CollectionRowPreferenceLimits.MAX_ROW_KEY_LENGTH)
            ?: return null
        val rowsArray = json.optJSONArray(FIELD_ROWS) ?: JSONArray()
        if (rowsArray.length() > CollectionRowPreferenceLimits.MAX_ENTRIES_PER_PAGE) return null
        val entries = ArrayList<CollectionRowPreferenceEntry>()
        val unreadableEntries = ArrayList<String>()
        val seen = HashSet<String>()
        for (index in 0 until rowsArray.length()) {
            val element = rowsArray.opt(index)
            val entry = (element as? JSONObject)?.let { decodeEntry(it) }
            if (entry == null || !seen.add(entry.rowKey)) {

                if (element != null) unreadableEntries += element.toString()
                continue
            }
            entries += entry
        }
        val preserved = json.optJSONArray(FIELD_UNREADABLE)
        if (preserved != null) {
            for (index in 0 until preserved.length()) {
                (preserved.opt(index) as? String)?.let { unreadableEntries += it }
            }
        }
        return CollectionPagePreference(
            packId = packId,
            groupKey = groupKey,
            tileKey = tileKey,
            entries = entries,
            unreadableEntries = unreadableEntries,
        )
    }

    private fun decodeEntry(json: JSONObject): CollectionRowPreferenceEntry? {
        val key = json.nonBlank(FIELD_ROW_KEY)?.bounded(CollectionRowPreferenceLimits.MAX_ROW_KEY_LENGTH)
            ?: return null
        if (!json.has(FIELD_ROW_ENABLED) || json.isNull(FIELD_ROW_ENABLED)) return null
        return CollectionRowPreferenceEntry(rowKey = key, enabled = json.optBoolean(FIELD_ROW_ENABLED))
    }

    private const val FIELD_VERSION = "v"
    private const val FIELD_PAGES = "pages"
    private const val FIELD_UNREADABLE = "unreadable"
    private const val FIELD_PACK = "id"
    private const val FIELD_GROUP = "group"
    private const val FIELD_TILE = "tile"
    private const val FIELD_ROWS = "rows"
    private const val FIELD_ROW_KEY = "k"
    private const val FIELD_ROW_ENABLED = "on"
}

private fun JSONObject.nonBlank(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }

private fun String.bounded(max: Int): String? = if (length > max) null else this
