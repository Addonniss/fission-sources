package dev.infusion.core.database.acquisition

import android.content.Context
import android.content.SharedPreferences
import dev.infusion.core.domain.acquisition.AcquisitionKind
import dev.infusion.core.domain.acquisition.AcquisitionOperationStore
import dev.infusion.core.domain.acquisition.AcquisitionRecord
import dev.infusion.core.domain.acquisition.AcquisitionRecordStatus
import dev.infusion.core.domain.profile.ProfileScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import org.json.JSONObject

class SharedPreferencesAcquisitionOperationStore(
    context: Context,
    private val profileScope: ProfileScope,
    prefsName: String = PREFS_NAME,
) : AcquisitionOperationStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private val _changes = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override fun changes(): Flow<Unit> = _changes.asSharedFlow()

    override fun read(profileId: String): List<AcquisitionRecord> {
        val raw = prefs.getString(key(profileId), null) ?: return emptyList()
        if (!raw.startsWith(PAYLOAD_PREFIX)) return emptyList()
        return try {
            decode(raw.removePrefix(PAYLOAD_PREFIX))
        } catch (_: Exception) {

            emptyList()
        }
    }

    override fun write(profileId: String, records: List<AcquisitionRecord>) {
        if (!writable(profileId)) return
        prefs.edit().putString(key(profileId), PAYLOAD_PREFIX + encode(records)).apply()
        _changes.tryEmit(Unit)
    }

    override fun clearProfile(profileId: String) {
        prefs.edit().remove(key(profileId)).apply()
        _changes.tryEmit(Unit)
    }

    private fun writable(profileId: String): Boolean {
        val raw = prefs.getString(key(profileId), null) ?: return true
        return raw.startsWith("$VERSION|")
    }

    private fun encode(records: List<AcquisitionRecord>): String {
        val arr = JSONArray()
        for (r in records) {
            arr.put(
                JSONObject().apply {
                    put("id", r.id)
                    put("serviceId", r.serviceId)
                    put("serviceInstance", r.serviceInstance)
                    put("kind", r.kind.name)
                    put("titleId", r.titleId)
                    put("displayTitle", r.displayTitle)
                    putIfNotNull("season", r.seasonNumber)
                    putIfNotNull("episode", r.episodeNumber)
                    put("status", r.status.name)
                    put("titleAdded", r.titleAdded)
                    put("monitored", r.monitored)
                    put("searchSubmitted", r.searchSubmitted)
                    put("searchPending", r.searchPending)
                    put("filePresent", r.filePresent)
                    putIfNotNull("importProvenAtMs", r.importProvenAtMs)
                    putIfNotNull("serverId", r.serverId)
                    putIfNotNull("detail", r.detail)
                    put("updatedAtMs", r.updatedAtMs)
                },
            )
        }
        return JSONObject().put("records", arr).toString()
    }

    private fun decode(body: String): List<AcquisitionRecord> {
        val root = JSONObject(body)
        val arr = root.optJSONArray("records") ?: return emptyList()
        val out = ArrayList<AcquisitionRecord>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            val serviceId = o.optString("serviceId", "")
            val instance = o.optString("serviceInstance", "")
            val titleId = o.optString("titleId", "")
            if (id.isEmpty() || serviceId.isEmpty() || instance.isEmpty() || titleId.isEmpty()) continue
            out += AcquisitionRecord(
                id = id,
                serviceId = serviceId,
                serviceInstance = instance,
                kind = runCatching { AcquisitionKind.valueOf(o.optString("kind")) }.getOrDefault(AcquisitionKind.MOVIE),
                titleId = titleId,
                displayTitle = o.optString("displayTitle", ""),
                seasonNumber = o.optIntOrNull("season"),
                episodeNumber = o.optIntOrNull("episode"),
                status = runCatching { AcquisitionRecordStatus.valueOf(o.optString("status")) }
                    .getOrDefault(AcquisitionRecordStatus.UNKNOWN),
                titleAdded = o.optBoolean("titleAdded", false),
                monitored = o.optBoolean("monitored", false),
                searchSubmitted = o.optBoolean("searchSubmitted", false),
                searchPending = o.optBoolean("searchPending", false),
                filePresent = o.optBoolean("filePresent", false),
                importProvenAtMs = o.optLongOrNull("importProvenAtMs"),
                serverId = o.optIntOrNull("serverId"),
                detail = o.optString("detail", "").takeIf { it.isNotEmpty() },
                updatedAtMs = o.optLong("updatedAtMs", 0L),
            )
        }
        return out
    }

    private fun JSONObject.putIfNotNull(key: String, value: Any?) {
        if (value != null) put(key, value)
    }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null

    private fun key(profileId: String) = "$KEY_PREFIX.$profileId"

    companion object {

        const val PREFS_NAME: String = "infusion_acquisition_operations"

        private const val KEY_PREFIX = "acq_ops_v1"
        private const val VERSION = "v1"
        private const val PAYLOAD_PREFIX = "$VERSION|"
    }
}
