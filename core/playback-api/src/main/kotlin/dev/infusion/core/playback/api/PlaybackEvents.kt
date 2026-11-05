package dev.infusion.core.playback.api

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

fun PlaybackSession.events(): Flow<PlaybackEvent> = callbackFlow {
    val listener = PlaybackEventListener { event -> trySend(event) }
    addListener(listener)
    awaitClose { removeListener(listener) }
}
