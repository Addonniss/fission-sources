package dev.infusion.core.database.watchlist

import android.content.Context
import android.content.SharedPreferences
import dev.infusion.core.domain.profile.ProfileScope
import dev.infusion.core.domain.watchlist.WatchlistCodec
import dev.infusion.core.domain.watchlist.WatchlistSnapshot
import dev.infusion.core.domain.watchlist.WatchlistStore

class SharedPreferencesWatchlistStore(
    context: Context,
    private val profileScope: ProfileScope,
    prefsName: String = PREFS_NAME,
) : WatchlistStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun read(profileId: String): WatchlistSnapshot {
        val entries = WatchlistCodec.decodeEntries(prefs.getString(entriesKey(profileId), null))
        val suppressions = WatchlistCodec.decodeSuppressions(prefs.getString(suppressionsKey(profileId), null))
        val guards = WatchlistCodec.decodeGuards(prefs.getString(guardsKey(profileId), null))
        val meta = prefs.getString(metaKey(profileId), null)
        val unresolved = if (meta == null) 0 else meta.substringBefore('|').toIntOrNull() ?: 0
        val complete = if (meta == null) true else meta.substringAfter('|', "1") != "0"
        return WatchlistSnapshot(
            entries = entries,
            suppressions = suppressions,
            guards = guards,
            unresolvedCount = unresolved,
            remoteReadComplete = complete,
        )
    }

    override fun write(profileId: String, snapshot: WatchlistSnapshot) {
        val editor = prefs.edit()

        if (writable(entriesKey(profileId), WatchlistCodec.ENTRIES_VERSION)) {
            editor.putString(entriesKey(profileId), WatchlistCodec.encodeEntries(snapshot.entries))
        }
        if (writable(suppressionsKey(profileId), WatchlistCodec.SUPPRESSIONS_VERSION)) {
            editor.putString(suppressionsKey(profileId), WatchlistCodec.encodeSuppressions(snapshot.suppressions))
        }
        if (writable(guardsKey(profileId), WatchlistCodec.GUARDS_VERSION)) {
            editor.putString(guardsKey(profileId), WatchlistCodec.encodeGuards(snapshot.guards))
        }
        editor.putString(
            metaKey(profileId),
            "${snapshot.unresolvedCount}|${if (snapshot.remoteReadComplete) "1" else "0"}",
        )
        editor.apply()
    }

    override fun clearProfile(profileId: String) {
        prefs.edit().apply {
            remove(entriesKey(profileId))
            remove(suppressionsKey(profileId))
            remove(guardsKey(profileId))
            remove(metaKey(profileId))
        }.apply()
    }

    private fun writable(key: String, version: String): Boolean =
        WatchlistCodec.isPayloadOfVersion(prefs.getString(key, null), version)

    private fun entriesKey(profileId: String) = "$ENTRIES_KEY_PREFIX.$profileId"
    private fun suppressionsKey(profileId: String) = "$SUPPRESSIONS_KEY_PREFIX.$profileId"
    private fun guardsKey(profileId: String) = "$GUARDS_KEY_PREFIX.$profileId"
    private fun metaKey(profileId: String) = "$META_KEY_PREFIX.$profileId"

    companion object {
        const val PREFS_NAME: String = "infusion_watchlist"
        const val ENTRIES_KEY_PREFIX: String = "watchlist_v1"
        const val SUPPRESSIONS_KEY_PREFIX: String = "watchlist_suppress_v1"
        const val GUARDS_KEY_PREFIX: String = "watchlist_guard_v1"
        const val META_KEY_PREFIX: String = "watchlist_meta_v1"
    }
}
