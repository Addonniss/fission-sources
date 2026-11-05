package dev.infusion.feature.vertical.profile

import dev.infusion.core.domain.profile.CreateProfileResult
import dev.infusion.core.domain.profile.DeleteProfileResult
import dev.infusion.core.domain.profile.ProfileDeletionOutcome
import dev.infusion.core.domain.profile.ProfileLedgerDisposition
import dev.infusion.core.domain.profile.ProfileRepository
import dev.infusion.core.domain.profile.ProfileScope
import dev.infusion.core.model.profile.Profile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ProfileManagementFeedback {

    data object PrimaryProtected : ProfileManagementFeedback

    data object CapExceeded : ProfileManagementFeedback

    data class Deleted(val outcome: ProfileDeletionOutcome) : ProfileManagementFeedback

    data object NotFound : ProfileManagementFeedback
}

data class PendingDeleteConfirmation(
    val profile: Profile,
    val disposition: ProfileLedgerDisposition = ProfileLedgerDisposition.PRESERVE,
)

class ProfileManagementStateHolder(
    private val repository: ProfileRepository,
    profileScope: ProfileScope,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val _state = MutableStateFlow(ProfileManagementUiState())
    val state: StateFlow<ProfileManagementUiState> = _state.asStateFlow()

    init {

        scope.launch {
            profileScope.activeProfileFlow
                .distinctUntilChanged()
                .collect { activeId -> _state.update { it.copy(activeProfileId = activeId) } }
        }
        refresh()
    }

    fun refresh() {
        scope.launch {
            val profiles = withContext(ioDispatcher) { repository.list() }
            _state.update { it.copy(profiles = profiles, busy = false) }
        }
    }

    fun create(name: String, pin: String? = null) {
        if (name.isBlank()) return
        scope.launch {
            _state.update { it.copy(busy = true) }
            when (val result = withContext(ioDispatcher) { repository.create(name, pin) }) {
                is CreateProfileResult.Ok -> _state.update {
                    it.copy(busy = false, profiles = (it.profiles + result.profile).sortedBy { p -> p.profileIndex })
                }
                CreateProfileResult.CapExceeded -> _state.update {
                    it.copy(busy = false, feedback = ProfileManagementFeedback.CapExceeded)
                }
            }
        }
    }

    fun rename(profileId: String, newName: String) {
        if (newName.isBlank()) return
        scope.launch {
            _state.update { it.copy(busy = true) }
            val renamed = withContext(ioDispatcher) { repository.rename(profileId, newName) }
            _state.update { s ->
                s.copy(
                    busy = false,
                    profiles = s.profiles.map { if (it.profileId == profileId) renamed else it },
                )
            }
        }
    }

    fun requestDelete(profileId: String) {
        val profile = _state.value.profiles.firstOrNull { it.profileId == profileId }
            ?: run {
                _state.update { it.copy(feedback = ProfileManagementFeedback.NotFound) }
                return
            }
        if (profile.isPrimary) {
            _state.update { it.copy(feedback = ProfileManagementFeedback.PrimaryProtected) }
            return
        }
        _state.update { it.copy(pendingDelete = PendingDeleteConfirmation(profile)) }
    }

    fun selectDisposition(disposition: ProfileLedgerDisposition) {
        _state.update { it.copy(pendingDelete = it.pendingDelete?.copy(disposition = disposition)) }
    }

    fun confirmDelete() {
        val pending = _state.value.pendingDelete ?: return
        scope.launch {
            _state.update { it.copy(busy = true, pendingDelete = null) }
            when (val result = withContext(ioDispatcher) {
                repository.delete(pending.profile.profileId, pending.disposition)
            }) {
                is DeleteProfileResult.Ok -> _state.update { s ->
                    s.copy(
                        busy = false,
                        profiles = s.profiles.filterNot { it.profileId == pending.profile.profileId },
                        feedback = ProfileManagementFeedback.Deleted(result.outcome),
                    )
                }
                DeleteProfileResult.PrimaryProtected -> _state.update {
                    it.copy(busy = false, feedback = ProfileManagementFeedback.PrimaryProtected)
                }
                DeleteProfileResult.NotFound -> _state.update {
                    it.copy(busy = false, feedback = ProfileManagementFeedback.NotFound)
                }
            }
        }
    }

    fun cancelDelete() {
        _state.update { it.copy(pendingDelete = null) }
    }

    fun switchTo(profileId: String) {
        scope.launch {
            _state.update { it.copy(busy = true) }
            val switched = withContext(ioDispatcher) { repository.switchActive(profileId) }
            _state.update { it.copy(busy = false, activeProfileId = switched.profileId) }
        }
    }

    fun setPin(profileId: String, pin: String) {
        scope.launch {
            withContext(ioDispatcher) { repository.setPin(profileId, pin) }
            refresh()
        }
    }

    fun clearPin(profileId: String) {
        scope.launch {
            withContext(ioDispatcher) { repository.clearPin(profileId) }
            refresh()
        }
    }

    fun consumeFeedback() {
        _state.update { it.copy(feedback = null) }
    }
}

data class ProfileManagementUiState(
    val profiles: List<Profile> = emptyList(),
    val activeProfileId: String? = null,
    val busy: Boolean = false,
    val pendingDelete: PendingDeleteConfirmation? = null,
    val feedback: ProfileManagementFeedback? = null,
)
