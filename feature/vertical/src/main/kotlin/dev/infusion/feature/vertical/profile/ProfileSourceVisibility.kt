package dev.infusion.feature.vertical.profile

import dev.infusion.core.model.profile.Profile

object ProfileSourceVisibility {

    fun isVisible(
        sourceOwnerProfileId: String,
        viewer: Profile,
        primary: Profile,
    ): Boolean = when {
        sourceOwnerProfileId == primary.profileId ->

            viewer.isPrimary || viewer.usesPrimarySources
        else ->

            sourceOwnerProfileId == viewer.profileId
    }

    fun <T> filterVisible(
        sources: List<T>,
        ownerOf: (T) -> String,
        viewer: Profile,
        primary: Profile,
    ): List<T> = sources.filter { isVisible(ownerOf(it), viewer, primary) }

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
