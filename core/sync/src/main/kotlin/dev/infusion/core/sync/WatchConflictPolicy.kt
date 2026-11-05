package dev.infusion.core.sync

object WatchConflictPolicy {

    const val MIN_MATERIAL_REGRESSION_MS = 60_000L

    const val MAX_CONFLICTS = 8

    fun isMaterialRegression(localPositionMs: Long, remotePositionMs: Long): Boolean =
        localPositionMs - remotePositionMs > MIN_MATERIAL_REGRESSION_MS
}
