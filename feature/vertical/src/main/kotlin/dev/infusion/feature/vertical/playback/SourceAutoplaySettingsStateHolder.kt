package dev.infusion.feature.vertical.playback

import dev.infusion.core.domain.playback.SourceAutoplayPreferenceStore
import dev.infusion.core.domain.profile.ProfileScope
import dev.infusion.core.playback.api.SourceAutoplayPolicy
import dev.infusion.core.playback.api.SourceAutoplayPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SourceAutoplaySettingsStateHolder(
    private val store: SourceAutoplayPreferenceStore,
    private val profileScope: ProfileScope,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private var publishedProfileId: String = profileScope.activeProfileId

    private val _preferences = MutableStateFlow(readFor(publishedProfileId))

    val preferences: StateFlow<SourceAutoplayPreferences> = _preferences.asStateFlow()

    init {
        scope.launch(ioDispatcher) {
            profileScope.activeProfileFlow.collect { profileId ->
                if (profileId == publishedProfileId) return@collect
                publishedProfileId = profileId
                _preferences.value = readFor(profileId)
            }
        }
    }

    fun setPolicy(value: SourceAutoplayPolicy) {
        val current = _preferences.value
        if (current.policy == value) return
        publish(current.withPolicy(value)) { profileId -> store.setPolicy(profileId, value.storageKey) }
    }

    fun setPreferJellyfin(value: Boolean) {
        val current = _preferences.value
        if (current.preferJellyfin == value) return
        publish(current.withPreferJellyfin(value)) { profileId ->
            store.setPreferJellyfin(profileId, value.toString())
        }
    }

    private fun publish(
        updated: SourceAutoplayPreferences,
        persist: (profileId: String) -> Unit,
    ) {
        val profileId = profileScope.activeProfileId
        _preferences.value = updated
        scope.launch(ioDispatcher) { persist(profileId) }
    }

    private fun readFor(profileId: String): SourceAutoplayPreferences = SourceAutoplayPreferences(
        policy = SourceAutoplayPolicy.resolve(store.policy(profileId)),
        preferJellyfin = parseBoolean(
            store.preferJellyfin(profileId),
            default = SourceAutoplayPreferences.DEFAULT.preferJellyfin,
        ),
    )

    private fun parseBoolean(stored: String?, default: Boolean): Boolean = when (stored?.trim()?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> default
    }
}
