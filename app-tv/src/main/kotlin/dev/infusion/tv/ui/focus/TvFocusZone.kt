package dev.infusion.tv.ui.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester

class FocusKeyRegistry : LinkedHashMap<String, FocusRequester>() {

    private val liveKeys = HashSet<String>()

    var armedKey: String? by mutableStateOf(null)
        private set

    fun arm(key: String?) {
        armedKey = key
    }

    fun disarm() {
        armedKey = null
        restorationKey = null
        restorationHeldFocus = false
    }

    var restorationKey: String? by mutableStateOf(null)
        private set

    private var restorationHeldFocus: Boolean = false

    fun restore(key: String?) {
        restorationKey = key
        restorationHeldFocus = false
    }

    fun pendingKey(): String? = restorationKey ?: armedKey

    fun claimArmed(key: String): Boolean {
        if (pendingKey() != key) return false
        if (!request(key)) return false
        armedKey = null

        return true
    }

    var lastFocusedKey: String? by mutableStateOf(null)
        private set

    var focusedKey: String? by mutableStateOf(null)
        private set

    fun onFocusReport(key: String) {
        if (armedKey != null && armedKey != key) armedKey = null

        if (restorationKey != null) {
            if (key == restorationKey) {
                restorationHeldFocus = true
            } else if (restorationHeldFocus) {
                restorationKey = null
                restorationHeldFocus = false
            }
        }
        lastFocusedKey = key
        focusedKey = key
        val slot = contentNeighbourhood?.locationOf(key)
        if (slot != null) lastContentSlot = slot
        contentFocusObserver?.onContentFocus(key, slot)
    }

    fun onFocusLost(key: String) {
        if (focusedKey == key) focusedKey = null
    }

    internal fun registerKey(key: String, requester: FocusRequester) {
        put(key, requester)
        liveKeys += key
    }

    internal fun unregisterKey(key: String, requester: FocusRequester) {
        if (this[key] === requester) {
            remove(key)
            liveKeys -= key
        }
    }

    fun isLive(key: String?): Boolean = key != null && key in liveKeys

    private var materializationProbe: MaterializationProbe? = null

    internal fun registerMaterialization(probe: MaterializationProbe) {
        materializationProbe = probe
    }

    internal fun unregisterMaterialization(probe: MaterializationProbe) {
        if (materializationProbe === probe) materializationProbe = null
    }

    fun canMaterialize(key: String?): Boolean =
        key != null && materializationProbe?.canMaterialize(key) == true

    fun contentDataAvailable(): Boolean = materializationProbe?.hasData() == true

    private var contentNeighbourhood: ContentNeighbourhood? = null

    internal fun registerContentNeighbourhood(probe: ContentNeighbourhood) {
        contentNeighbourhood = probe
    }

    internal fun unregisterContentNeighbourhood(probe: ContentNeighbourhood) {
        if (contentNeighbourhood === probe) contentNeighbourhood = null
    }

    private var contentFocusObserver: ContentFocusObserver? = null

    internal fun registerContentFocusObserver(observer: ContentFocusObserver) {
        contentFocusObserver = observer
    }

    internal fun unregisterContentFocusObserver(observer: ContentFocusObserver) {
        if (contentFocusObserver === observer) contentFocusObserver = null
    }

    var lastContentSlot: ContentSlot? by mutableStateOf(null)
        private set

    fun nearbySurvivor(slot: ContentSlot? = null): String? {
        val probe = contentNeighbourhood ?: return null
        val target = slot ?: lastContentSlot ?: return null
        return probe.nearbySurvivor(target)
    }

    fun isOriginRowArriving(slot: ContentSlot?): Boolean =
        slot != null && contentNeighbourhood?.isOriginRowArriving(slot) == true

    fun request(key: String?): Boolean {
        val requester = if (isLive(key)) get(key) else null
        return requester?.requestFocus() == true
    }

    fun requestFirst(vararg candidates: String?): String? {
        for (candidate in candidates) {
            if (request(candidate)) return candidate
        }
        return null
    }

    fun firstLive(vararg candidates: String?): String? =
        candidates.firstOrNull { isLive(it) }
}

class TvZoneEntry(
    val registry: FocusKeyRegistry,

    val adoptsPageRegistrations: Boolean = false,
) {

    val armedKey: String? get() = registry.pendingKey()

    fun arm(key: String?) {
        registry.arm(key)
    }

    fun restore(key: String?) {
        registry.restore(key)
    }

    fun disarm() {
        registry.disarm()
    }

    fun onNodeAttached(key: String): Boolean = registry.claimArmed(key)

    fun onFocusReport(key: String) {
        registry.onFocusReport(key)
    }

    fun canMaterialize(key: String?): Boolean = registry.canMaterialize(key)

    fun contentDataAvailable(): Boolean = registry.contentDataAvailable()

    fun nearbySurvivor(slot: ContentSlot? = null): String? = registry.nearbySurvivor(slot)

    fun isOriginRowArriving(slot: ContentSlot? = null): Boolean = registry.isOriginRowArriving(slot)
}

val LocalTvZoneEntry = compositionLocalOf<TvZoneEntry?> { null }

fun interface MaterializationProbe {

    fun canMaterialize(key: String): Boolean

    fun hasData(): Boolean = true
}

data class ContentSlot(

    val rowKey: Any,

    val rowIndex: Int,

    val itemIndex: Int,
)

interface ContentNeighbourhood {

    fun locationOf(key: String): ContentSlot?

    fun nearbySurvivor(slot: ContentSlot): String?

    fun isOriginRowArriving(slot: ContentSlot): Boolean = false
}

fun interface ContentFocusObserver {
    fun onContentFocus(key: String, slot: ContentSlot?)
}

enum class TvEntryEvent {
    LANDING,
    FRESH_TAB,
    SHELF_REENTRY,
    CHILD_RETURN,

    ACTIVE_TAB_RESELECT,
}

enum class TvFreshEntry {

    REMEMBERED,

    PAGE_DEFAULT,

    FRESH_KEY_THEN_DEFAULT,
}

@Composable
fun rememberTvZoneEntry(
    registry: FocusKeyRegistry,
    adoptsPageRegistrations: Boolean = false,
): TvZoneEntry = remember(registry, adoptsPageRegistrations) {
    TvZoneEntry(registry, adoptsPageRegistrations)
}

@Composable
fun RequestZoneEntry(
    entry: TvZoneEntry,
    entryToken: Int,
    force: Boolean,
    rememberedKey: String?,
    defaultKey: String?,
    anchorKey: String?,
    alreadyEntered: () -> Boolean,
    settleOnAnchor: Boolean = false,
    resetKey: Any? = null,

    event: TvEntryEvent = TvEntryEvent.LANDING,

    freshEntryKey: String? = null,

    freshEntry: TvFreshEntry = TvFreshEntry.REMEMBERED,

    freshEntryOnLanding: Boolean = false,

    freshEntryReady: Boolean = true,

    rememberedSlot: ContentSlot? = null,
) {

    val settled = remember(resetKey, entryToken) { mutableStateOf(false) }

    val freshApplied = remember(entry) { mutableSetOf<Pair<Int, String?>>() }

    val explicitActApplied = remember(entry) { mutableSetOf<Int>() }
    LaunchedEffect(
        entryToken,
        rememberedKey,
        defaultKey,

        freshEntryKey,
        freshEntryReady,
    ) {
        if (settled.value && !force) return@LaunchedEffect
        if (alreadyEntered() && !force) return@LaunchedEffect
        if (settleOnAnchor) {

            if (anchorKey != null) entry.registry.request(anchorKey)
            settled.value = true
            entry.disarm()
            return@LaunchedEffect
        }

        val freshAct = event == TvEntryEvent.FRESH_TAB ||
            event == TvEntryEvent.ACTIVE_TAB_RESELECT ||
            (event == TvEntryEvent.LANDING && freshEntryOnLanding)
        if (freshAct && freshEntry != TvFreshEntry.REMEMBERED) {
            if (!freshEntryReady && event == TvEntryEvent.LANDING) {

                entry.disarm()
                if (anchorKey != null) entry.registry.request(anchorKey)
                return@LaunchedEffect
            }
            val candidates = when (freshEntry) {
                TvFreshEntry.PAGE_DEFAULT -> listOfNotNull(defaultKey)
                TvFreshEntry.FRESH_KEY_THEN_DEFAULT -> listOfNotNull(freshEntryKey, defaultKey)
                TvFreshEntry.REMEMBERED -> emptyList()
            }

            val declared = candidates.firstOrNull()
            val automaticAct = event == TvEntryEvent.LANDING
            val firstApplication = if (automaticAct) {
                freshApplied.add(entryToken to declared)
            } else {

                explicitActApplied.add(entryToken)
            }
            if (!firstApplication) return@LaunchedEffect
            for (candidate in candidates) {
                if (entry.registry.request(candidate)) {
                    entry.disarm()
                    settled.value = true
                    return@LaunchedEffect
                }
                if (entry.canMaterialize(candidate)) {
                    entry.arm(candidate)
                    return@LaunchedEffect
                }
            }

            if (declared != null) {
                entry.arm(declared)
            } else if (anchorKey != null) {
                entry.registry.request(anchorKey)
            }
            return@LaunchedEffect
        }

        val remembered = rememberedKey
        if (remembered != null) {
            if (entry.registry.request(remembered)) {
                entry.disarm()
                settled.value = true
                return@LaunchedEffect
            }
            if (entry.canMaterialize(remembered)) {
                entry.arm(remembered)
                return@LaunchedEffect
            }

            if (event == TvEntryEvent.CHILD_RETURN) {

                if (!entry.contentDataAvailable() || entry.isOriginRowArriving(rememberedSlot)) {
                    entry.arm(remembered)
                    return@LaunchedEffect
                }
                val survivor = entry.nearbySurvivor(rememberedSlot)
                if (survivor != null) {
                    if (entry.registry.request(survivor)) {
                        entry.disarm()
                        settled.value = true
                        return@LaunchedEffect
                    }
                    if (entry.canMaterialize(survivor)) {
                        entry.arm(survivor)
                        return@LaunchedEffect
                    }
                }
            }
        }

        val fallbackDefault = defaultKey
        if (fallbackDefault != null) {
            if (entry.registry.request(fallbackDefault)) {
                entry.disarm()
                settled.value = true
                return@LaunchedEffect
            }
            if (entry.canMaterialize(fallbackDefault)) {
                entry.arm(fallbackDefault)
                return@LaunchedEffect
            }
        }

        val fallback = remembered ?: fallbackDefault
        if (fallback != null) {
            entry.arm(fallback)
        } else if (anchorKey != null) {
            entry.registry.request(anchorKey)
        }
    }
}
