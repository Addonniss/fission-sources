package dev.infusion.core.domain.search

interface SearchHistoryStore {

    fun read(profileId: String): List<String>

    fun write(profileId: String, queries: List<String>)

    fun clear(profileId: String)
}

object SearchHistoryLimits {

    const val MAX_ENTRIES: Int = 30

    const val LABEL_CHARS: Int = 20
}

object SearchHistoryQueries {

    fun recordable(query: String): String? {
        val cleaned = query.filterNot { it.isISOControl() }.trim()
        return cleaned.takeIf { it.isNotEmpty() }
    }

    fun record(existing: List<String>, query: String): List<String> {
        val recordable = recordable(query) ?: return existing
        val key = recordable.lowercase()
        val deduped = existing.filterNot { it.lowercase() == key }
        return (listOf(recordable) + deduped).take(SearchHistoryLimits.MAX_ENTRIES)
    }

    fun displayLabel(query: String): String {
        if (query.length <= SearchHistoryLimits.LABEL_CHARS) return query
        return query.take(SearchHistoryLimits.LABEL_CHARS).trimEnd() + ELLIPSIS
    }

    const val ELLIPSIS: String = "…"
}
