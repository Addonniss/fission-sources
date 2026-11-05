package dev.infusion.core.database.search

import android.content.Context
import android.content.SharedPreferences
import dev.infusion.core.domain.search.SearchHistoryLimits
import dev.infusion.core.domain.search.SearchHistoryQueries
import dev.infusion.core.domain.search.SearchHistoryStore

class SharedPreferencesSearchHistoryStore(
    context: Context,
    prefsName: String = PREFS_NAME,
) : SearchHistoryStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun read(profileId: String): List<String> {
        if (profileId.isBlank()) return emptyList()
        val raw = prefs.getString(key(profileId), null) ?: return emptyList()

        return raw.split(SEPARATOR)
            .mapNotNull { SearchHistoryQueries.recordable(it) }
            .take(SearchHistoryLimits.MAX_ENTRIES)
    }

    override fun write(profileId: String, queries: List<String>) {
        if (profileId.isBlank()) return
        val payload = queries
            .mapNotNull { SearchHistoryQueries.recordable(it) }
            .take(SearchHistoryLimits.MAX_ENTRIES)
            .joinToString(SEPARATOR)
        if (payload.isEmpty()) {
            prefs.edit().remove(key(profileId)).apply()
        } else {
            prefs.edit().putString(key(profileId), payload).apply()
        }
    }

    override fun clear(profileId: String) {
        if (profileId.isBlank()) return
        prefs.edit().remove(key(profileId)).apply()
    }

    private fun key(profileId: String): String = "$PREFS_KEY_PREFIX.$profileId"

    companion object {

        const val PREFS_NAME: String = "infusion_search_history"

        const val PREFS_KEY_PREFIX: String = "search_history_v1"

        private const val SEPARATOR = "\n"
    }
}
