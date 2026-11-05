package dev.infusion.core.database.tracking

import android.content.Context
import dev.infusion.core.domain.profile.ProfileScope
import dev.infusion.core.domain.tracking.TrackingInboundCursorStore
import java.time.Instant

class SharedPreferencesTrackingInboundCursorStore(
    context: Context,
    private val profileScope: ProfileScope,
    prefsName: String = PREFS_NAME,
) : TrackingInboundCursorStore {

    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun playbackGate(providerId: String): Instant? =
        read(key(providerId, profileScope.activeProfileId, KEY_PLAYBACK_GATE))

    override fun savePlaybackGate(providerId: String, gate: Instant?) =
        write(key(providerId, profileScope.activeProfileId, KEY_PLAYBACK_GATE), gate)

    override fun clear(providerId: String) {
        val profileId = profileScope.activeProfileId
        prefs.edit()
            .remove(key(providerId, profileId, KEY_PLAYBACK_GATE))

            .remove(key(providerId, profileId, KEY_LEGACY_WATCHED_GATE))
            .apply()
    }

    private fun read(prefKey: String): Instant? {
        if (!prefs.contains(prefKey)) return null
        return prefs.getLong(prefKey, 0L).takeIf { it > 0L }?.let(Instant::ofEpochMilli)
    }

    private fun write(prefKey: String, gate: Instant?) {
        prefs.edit().apply {
            if (gate == null) remove(prefKey) else putLong(prefKey, gate.toEpochMilli())
        }.apply()
    }

    private fun key(providerId: String, profileId: String, gateKey: String) =
        "$PREFS_KEY_PREFIX.$providerId.$profileId.$gateKey"

    companion object {

        const val PREFS_NAME: String = "infusion_tracking_inbound"

        private const val PREFS_KEY_PREFIX = "tracking-inbound"
        private const val KEY_PLAYBACK_GATE = "playback_gate_ms"

        private const val KEY_LEGACY_WATCHED_GATE = "watched_gate_ms"
    }
}
