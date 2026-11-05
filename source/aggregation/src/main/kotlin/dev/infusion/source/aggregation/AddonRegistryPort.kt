package dev.infusion.source.aggregation

import dev.infusion.core.model.profile.Profile

interface AddonRegistryPort {

    fun runtimesFor(viewer: Profile, primary: Profile): List<AddonRuntimeDescriptor>
}
