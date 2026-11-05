package dev.infusion.core.testing.profile

import dev.infusion.core.domain.profile.ActiveProfileStore

class InMemoryActiveProfileStore(
    initial: String? = null,
) : ActiveProfileStore {

    private var stored: String? = initial

    override fun load(): String? = stored

    override fun save(profileId: String?) {
        stored = profileId
    }
}
