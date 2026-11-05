package dev.infusion.source.addon

import dev.infusion.core.model.profile.Profile

object AddonProfileVisibility {

    fun isVisible(
        ownerProfileId: String,
        viewer: Profile,
        primary: Profile,
    ): Boolean = when {
        ownerProfileId == primary.profileId ->
            viewer.isPrimary || viewer.usesPrimarySources
        else ->
            ownerProfileId == viewer.profileId
    }

    fun <T> filterVisible(
        addons: List<T>,
        ownerOf: (T) -> String,
        viewer: Profile,
        primary: Profile,
    ): List<T> = addons.filter { isVisible(ownerOf(it), viewer, primary) }

    fun visibleOwnerIds(
        viewer: Profile,
        primary: Profile,
    ): Set<String> = buildSet {
        add(viewer.profileId)
        if (viewer.isPrimary || viewer.usesPrimarySources) {
            add(primary.profileId)
        }
    }
}
