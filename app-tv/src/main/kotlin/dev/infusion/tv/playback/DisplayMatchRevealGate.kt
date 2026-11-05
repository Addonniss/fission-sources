package dev.infusion.tv.playback

import dev.infusion.core.playback.api.DisplayMatchingPolicy
import kotlinx.coroutines.delay

internal fun displayMatchRevealSettled(
    policy: DisplayMatchingPolicy,
    hasVideoTracks: Boolean,
    targetKnown: Boolean,
    requestInForce: Boolean,
): Boolean = when {
    policy == DisplayMatchingPolicy.OFF -> true
    !hasVideoTracks -> true
    targetKnown -> requestInForce
    else -> false
}

internal const val DisplayModeApplyCeilingMs: Long = 2_500L

internal const val DisplayModeApplyPollMs: Long = 80L

internal suspend fun awaitDisplayModeApplication(coordinator: DisplayMatchingCoordinator) {
    var waitedMs = 0L
    while (!coordinator.confirmApplication() && waitedMs < DisplayModeApplyCeilingMs) {
        delay(DisplayModeApplyPollMs)
        waitedMs += DisplayModeApplyPollMs
    }
}
