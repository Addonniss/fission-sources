package dev.infusion.core.domain.profile

interface ActiveProfileStore {

    fun load(): String?

    fun save(profileId: String?)
}
