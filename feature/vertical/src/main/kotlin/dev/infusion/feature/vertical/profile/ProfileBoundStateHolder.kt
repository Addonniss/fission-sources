package dev.infusion.feature.vertical.profile

import dev.infusion.core.domain.profile.ProfileScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class ProfileBoundStateHolder<H : Any>(
    private val profileScope: ProfileScope,
    private val scope: CoroutineScope,
    private val create: (profileId: String, holderScope: CoroutineScope) -> H,
    private val holderScopeFactory: () -> CoroutineScope = {

        CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Default)
    },
) {

    private val _current = MutableStateFlow<H?>(null)
    val current: StateFlow<H?> = _current.asStateFlow()

    private var holderJob: Job? = null
    private var boundProfileId: String? = null

    init {
        rebind(profileScope.activeProfileId)
        scope.launch {
            profileScope.activeProfileFlow
                .distinctUntilChanged()
                .collect { profileId ->

                    if (profileId != boundProfileId) rebind(profileId)
                }
        }
    }

    private fun rebind(profileId: String) {
        boundProfileId = profileId

        holderJob?.cancel()
        val holderScope = holderScopeFactory()
        holderJob = holderScope.coroutineContext[Job]
        _current.value = create(profileId, holderScope)
    }
}
