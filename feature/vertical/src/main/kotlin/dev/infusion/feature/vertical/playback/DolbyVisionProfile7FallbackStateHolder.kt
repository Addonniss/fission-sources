package dev.infusion.feature.vertical.playback

import dev.infusion.core.playback.api.DolbyVisionProfile7Fallback
import dev.infusion.core.playback.api.DolbyVisionProfile7FallbackPreferenceStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DolbyVisionProfile7FallbackStateHolder(
    private val store: DolbyVisionProfile7FallbackPreferenceStore,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val _fallback = MutableStateFlow(store.fallback())

    val fallback: StateFlow<DolbyVisionProfile7Fallback> = _fallback.asStateFlow()

    fun setFallback(fallback: DolbyVisionProfile7Fallback) {
        _fallback.value = fallback
        scope.launch(ioDispatcher) { store.setFallback(fallback) }
    }
}
