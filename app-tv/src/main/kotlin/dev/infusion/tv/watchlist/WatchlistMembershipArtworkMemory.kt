package dev.infusion.tv.watchlist

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import dev.infusion.core.model.MediaArtworkRef

class WatchlistMembershipArtworkMemory internal constructor() {

    private val artwork = mutableStateMapOf<String, MediaArtworkRef>()

    val resolved: SnapshotStateMap<String, MediaArtworkRef> get() = artwork

    fun retainOnly(entryIds: Collection<String>) {
        if (artwork.isEmpty()) return
        val keep = entryIds.toSet()
        for (entryId in artwork.keys.toList()) {
            if (entryId !in keep) artwork.remove(entryId)
        }
    }

    fun clear() {
        artwork.clear()
    }
}
