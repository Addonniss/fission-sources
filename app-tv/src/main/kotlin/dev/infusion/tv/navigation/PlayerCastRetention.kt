package dev.infusion.tv.navigation

import dev.infusion.core.model.PlayableCandidate
import dev.infusion.core.model.SessionId
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.feature.vertical.player.PlayerStateHolder
import dev.infusion.source.aggregation.select.FallbackCoordinator

class RetainedPlaybackAttempt(
    val watchKey: WatchKey,
    val holder: PlayerStateHolder,
    val candidate: PlayableCandidate,

    val lineage: FallbackCoordinator,
    val lineageSessionId: SessionId?,

    var castReturnPersonKey: String? = null,
)

class PlaybackRetentionRegistry {

    private var retained: RetainedPlaybackAttempt? = null

    val held: RetainedPlaybackAttempt? get() = retained

    fun retain(attempt: RetainedPlaybackAttempt) {
        releaseRetained()
        retained = attempt
    }

    fun take(watchKey: WatchKey): RetainedPlaybackAttempt? {
        val current = retained ?: return null
        if (current.watchKey != watchKey) {
            releaseRetained()
            return null
        }
        retained = null
        return current
    }

    fun releaseRetained() {
        val current = retained ?: return
        retained = null
        runCatching { current.holder.release() }
    }
}
