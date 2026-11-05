package dev.infusion.source.addon.resource

import dev.infusion.source.addon.InstalledAddon
import dev.infusion.source.addon.repository.AddonRepository
import dev.infusion.source.addon.repository.AddonRepositoryState
import dev.infusion.source.addon.repository.InstalledAddonRuntime

interface AddonResourceRuntimeAccessor {

    fun enabledInstalled(): List<InstalledAddon>

    fun runtimeFor(addonId: String): InstalledAddonRuntime?
}

class RepositoryAddonResourceRuntimeAccessor(
    private val repository: AddonRepository,
) : AddonResourceRuntimeAccessor {

    override fun enabledInstalled(): List<InstalledAddon> {
        val state = repository.installed.value
        if (state !is AddonRepositoryState.Ready) return emptyList()

        return state.installed.filter { it.enabled && repository.runtimeFor(it.addonId)?.providerState?.isEnabled == true }
    }

    override fun runtimeFor(addonId: String): InstalledAddonRuntime? = repository.runtimeFor(addonId)
}
