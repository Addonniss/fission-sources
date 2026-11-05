package dev.infusion.core.playback.api.testing

import dev.infusion.core.playback.api.PlaybackEvent
import dev.infusion.core.playback.api.PlaybackEventListener
import dev.infusion.core.playback.api.PlaybackFailure
import dev.infusion.core.playback.api.PlaybackState
import dev.infusion.core.playback.api.CancellationReason

class RecordingPlaybackListener : PlaybackEventListener {
    val events: List<PlaybackEvent> get() = _events
    private val _events = mutableListOf<PlaybackEvent>()

    override fun onPlaybackEvent(event: PlaybackEvent) {
        _events += event
    }

    fun clear() = _events.clear()

    fun states(): List<PlaybackState> =
        _events.filterIsInstance<PlaybackEvent.StateChanged>().map { it.state }

    fun failures(): List<PlaybackFailure> =
        _events.filterIsInstance<PlaybackEvent.Failed>().map { it.failure }

    fun cancellations(): List<CancellationReason> =
        _events.filterIsInstance<PlaybackEvent.Cancelled>().map { it.reason }

    fun timelineChanged(): List<PlaybackEvent.TimelineChanged> =
        _events.filterIsInstance<PlaybackEvent.TimelineChanged>()

    fun tracksChanged(): List<PlaybackEvent.TracksChanged> =
        _events.filterIsInstance<PlaybackEvent.TracksChanged>()

    fun capabilityObservations(): List<PlaybackEvent.CapabilityObserved> =
        _events.filterIsInstance<PlaybackEvent.CapabilityObserved>()

    fun lastEvent(): PlaybackEvent? = _events.lastOrNull()
}
