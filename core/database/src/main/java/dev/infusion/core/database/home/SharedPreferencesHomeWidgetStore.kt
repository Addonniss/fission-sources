package dev.infusion.core.database.home

import android.content.Context
import android.content.SharedPreferences
import dev.infusion.core.domain.home.HomeWidgetCodec
import dev.infusion.core.domain.home.HomeWidgetConfiguration
import dev.infusion.core.domain.home.HomeWidgetRecord
import dev.infusion.core.domain.home.HomeWidgetStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class SharedPreferencesHomeWidgetStore(
    context: Context,
    prefsName: String = PREFS_NAME,
) : HomeWidgetStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override fun read(profileId: String): HomeWidgetConfiguration =

        HomeWidgetCodec.decode(prefs.getString(key(profileId), null))

    override fun write(profileId: String, records: List<HomeWidgetRecord>) {

        val existing = read(profileId)
        val payload = HomeWidgetCodec.encode(
            records = records,
            unreadableRecords = existing.unreadableRecords,
        )
        prefs.edit().putString(key(profileId), payload).apply()
        changes.tryEmit(Unit)
    }

    override fun clearProfile(profileId: String) {
        prefs.edit().remove(key(profileId)).apply()
        changes.tryEmit(Unit)
    }

    override fun clearAllProfiles() {
        val prefix = "$PREFS_KEY_PREFIX."
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith(prefix) }.forEach { remove(it) }
        }.apply()
        changes.tryEmit(Unit)
    }

    override fun changes(): Flow<Unit> = changes.asSharedFlow()

    private fun key(profileId: String) = "$PREFS_KEY_PREFIX.$profileId"

    companion object {

        const val PREFS_NAME: String = "infusion_home_widgets"

        const val PREFS_KEY_PREFIX: String = "home_widgets_v1"
    }
}
