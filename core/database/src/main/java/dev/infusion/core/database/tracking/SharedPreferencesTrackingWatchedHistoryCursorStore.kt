package dev.infusion.core.database.tracking

import android.content.Context
import dev.infusion.core.domain.profile.ProfileScope
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryCursor
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryCursorStore
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryUnit
import java.time.Instant

class SharedPreferencesTrackingWatchedHistoryCursorStore(
    context: Context,
    private val profileScope: ProfileScope,
    prefsName: String = PREFS_NAME,
) : TrackingWatchedHistoryCursorStore {

    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun cursor(
        providerId: String,
        unit: TrackingWatchedHistoryUnit,
    ): TrackingWatchedHistoryCursor {
        val profileId = profileScope.activeProfileId
        return TrackingWatchedHistoryCursor(
            watermark = readInstant(key(providerId, profileId, unit, KEY_WATERMARK)),
            next = prefs.getString(key(providerId, profileId, unit, KEY_NEXT), null)
                ?.takeIf { it.isNotBlank() },
        )
    }

    override fun save(
        providerId: String,
        unit: TrackingWatchedHistoryUnit,
        cursor: TrackingWatchedHistoryCursor,
    ) {
        val profileId = profileScope.activeProfileId
        prefs.edit().apply {
            val watermarkKey = key(providerId, profileId, unit, KEY_WATERMARK)
            val watermark = cursor.watermark
            if (watermark == null) {
                remove(watermarkKey)
            } else {
                putLong(watermarkKey, watermark.toEpochMilli())
            }
            val nextKey = key(providerId, profileId, unit, KEY_NEXT)
            if (cursor.next == null) remove(nextKey) else putString(nextKey, cursor.next)
        }.apply()
    }

    override fun clear(providerId: String) {

        val profileId = profileScope.activeProfileId
        prefs.edit().apply {
            for (unit in TrackingWatchedHistoryUnit.entries) {
                remove(key(providerId, profileId, unit, KEY_WATERMARK))
                remove(key(providerId, profileId, unit, KEY_NEXT))
            }
        }.apply()
    }

    private fun readInstant(prefKey: String): Instant? {
        if (!prefs.contains(prefKey)) return null

        return prefs.getLong(prefKey, 0L).takeIf { it > 0L }?.let(Instant::ofEpochMilli)
    }

    private fun key(
        providerId: String,
        profileId: String,
        unit: TrackingWatchedHistoryUnit,
        field: String,
    ): String = "tracking-inbound.$providerId.$profileId.watched_history.${unit.name.lowercase()}.$field"

    companion object {

        const val PREFS_NAME: String = "infusion_tracking_inbound"

        private const val KEY_WATERMARK = "watermark_ms"
        private const val KEY_NEXT = "next_cursor"
    }
}
