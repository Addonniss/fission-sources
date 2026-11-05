package dev.infusion.core.domain.collections

object CollectionRowPreferenceLimits {

    const val MAX_ENTRIES_PER_PAGE: Int = CollectionPackLimits.MAX_ROWS_PER_TILE

    const val MAX_PAGES_PER_PROFILE: Int = 512

    const val MAX_ROW_KEY_LENGTH: Int = CollectionPackLimits.MAX_KEY_LENGTH
}

data class CollectionRowPreferenceEntry(
    val rowKey: String,
    val enabled: Boolean,
)

data class CollectionPageRowPreferences(
    val entries: List<CollectionRowPreferenceEntry> = emptyList(),
    val unreadableEntries: List<String> = emptyList(),
) {
    companion object {

        val None: CollectionPageRowPreferences = CollectionPageRowPreferences()
    }
}

data class CollectionPagePreference(
    val packId: String,
    val groupKey: String,
    val tileKey: String,
    val entries: List<CollectionRowPreferenceEntry> = emptyList(),
    val unreadableEntries: List<String> = emptyList(),
) {

    val pageId: String get() = CollectionRowPageId.of(packId, groupKey, tileKey)
}

object CollectionRowPageId {
    private const val SEP = "\u001F"

    fun of(packId: String, groupKey: String, tileKey: String): String =
        listOf(packId, groupKey, tileKey).joinToString(SEP)
}

data class CollectionRowPreferenceConfiguration(
    val configured: Boolean,
    val pages: List<CollectionPagePreference> = emptyList(),
    val unreadableRecords: List<String> = emptyList(),
    val unreadablePayload: String? = null,
) {
    companion object {
        val Absent: CollectionRowPreferenceConfiguration =
            CollectionRowPreferenceConfiguration(configured = false)
    }
}

object CollectionRowOrdering {

    fun orderedEntries(
        definedRowKeys: List<String>,
        preferences: CollectionPageRowPreferences,
    ): List<CollectionRowPreferenceEntry> {
        val defined = LinkedHashSet<String>()
        for (key in definedRowKeys) {
            if (key.isNotBlank()) defined.add(key)
        }
        val result = ArrayList<CollectionRowPreferenceEntry>(defined.size)
        val seen = HashSet<String>()
        for (entry in preferences.entries) {
            if (entry.rowKey !in defined || !seen.add(entry.rowKey)) continue
            result.add(entry)
        }
        for (key in defined) {
            if (seen.add(key)) result.add(CollectionRowPreferenceEntry(key, enabled = true))
        }
        return result
    }

    fun effectiveRowKeys(
        definedRowKeys: List<String>,
        preferences: CollectionPageRowPreferences,
    ): List<String> = orderedEntries(definedRowKeys, preferences)
        .filter { it.enabled }
        .map { it.rowKey }

    fun orderedRowKeys(
        definedRowKeys: List<String>,
        preferences: CollectionPageRowPreferences,
    ): List<String> = orderedEntries(definedRowKeys, preferences).map { it.rowKey }
}
