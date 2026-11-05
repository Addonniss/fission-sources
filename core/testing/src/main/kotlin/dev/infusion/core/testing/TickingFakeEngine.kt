package dev.infusion.core.testing

import dev.infusion.core.playback.api.DeviceRouteSnapshot
import dev.infusion.core.playback.api.PlaybackEngine
import dev.infusion.core.playback.api.PlaybackSession
import dev.infusion.core.playback.api.PlaybackState
import dev.infusion.core.playback.api.SessionLineage
import dev.infusion.core.playback.api.testing.FakePlaybackEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TickingFakeEngine(
    private val delegate: FakePlaybackEngine = FakePlaybackEngine(),
    private val scope: CoroutineScope,
    private val tickMs: Long = 250,
    private val advanceMs: Long = 1_000,
) : PlaybackEngine by delegate {

    override fun createSession(lineage: SessionLineage, route: DeviceRouteSnapshot?): PlaybackSession {
        val session = delegate.createSession(lineage, route) as dev.infusion.core.playback.api.testing.FakePlaybackSession
        scope.launch {
            while (isActive && session.currentState() != PlaybackState.RELEASED) {
                delay(tickMs)
                if (session.currentState() == PlaybackState.PLAYING) {
                    session.advanceClock(advanceMs)
                }
            }
        }
        return session
    }
}
