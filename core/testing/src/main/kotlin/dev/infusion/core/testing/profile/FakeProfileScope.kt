package dev.infusion.core.testing.profile

import dev.infusion.core.domain.profile.ProfileScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeProfileScope(initialProfileId: String) : ProfileScope {

    private val state = MutableStateFlow(initialProfileId)

    override val activeProfileId: String get() = state.value

    override val activeProfileFlow: Flow<String> get() = state

    fun switchTo(profileId: String) {
        state.value = profileId
    }
}
