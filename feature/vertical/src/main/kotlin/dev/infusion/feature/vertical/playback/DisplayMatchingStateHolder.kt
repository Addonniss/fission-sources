package dev.infusion.feature.vertical.playback

import dev.infusion.core.playback.api.DisplayMatchingPolicy
import dev.infusion.core.playback.api.DisplayMatchingPreferenceStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DisplayMatchingStateHolder(
    private val store: DisplayMatchingPreferenceStore,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val _policy = MutableStateFlow(store.policy())

    val policy: StateFlow<DisplayMatchingPolicy> = _policy.asStateFlow()

    fun setPolicy(policy: DisplayMatchingPolicy) {
        _policy.value = policy
        scope.launch(ioDispatcher) { store.setPolicy(policy) }
    }
}
