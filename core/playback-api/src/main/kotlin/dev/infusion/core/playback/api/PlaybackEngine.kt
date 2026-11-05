package dev.infusion.core.playback.api

enum class StopReason {
    USER_EXIT,
    COMPLETED,
    ERROR_RECOVERY,
    SUPERSEDED,
    SYSTEM,
}

interface PlaybackEngine {
    val id: EngineId

    fun createSession(lineage: SessionLineage, route: DeviceRouteSnapshot? = null): PlaybackSession

    fun release()
}

interface PlaybackSession {
    val sessionId: SessionId
    val lineage: SessionLineage

    fun prepare(request: TransportRequest)

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    fun stop(reason: StopReason)

    fun selectTrack(selection: TrackSelection)

    fun snapshot(): DiagnosticsSnapshot

    fun addListener(listener: PlaybackEventListener)

    fun removeListener(listener: PlaybackEventListener)

    fun release()
}
