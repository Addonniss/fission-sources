package dev.infusion.source.addon.resource

import dev.infusion.source.addon.AddonManifestSnapshot
import dev.infusion.source.addon.model.AddonManifest
import dev.infusion.source.stremio.policy.StremioContentType
import dev.infusion.source.stremio.wire.Resource

object AddonResourceMatcher {

    fun supports(snapshot: AddonManifestSnapshot, resource: Resource, type: StremioContentType): Boolean {
        if (type == StremioContentType.UNKNOWN) return false
        if (resource == Resource.ADDON_CATALOG) return false
        val entry = snapshot.resources.firstOrNull { Resource.parse(it.name) == resource } ?: return false
        return type in effectiveTypes(snapshot, resource)
    }

    fun idMatches(snapshot: AddonManifestSnapshot, resource: Resource, id: String): Boolean {
        val effective = effectiveIdPrefixes(snapshot, resource)
        if (effective.isEmpty()) return true
        return effective.any { prefix -> id.startsWith(prefix) }
    }

    fun eligible(snapshot: AddonManifestSnapshot, resource: Resource, type: StremioContentType, id: String): Boolean =
        supports(snapshot, resource, type) && idMatches(snapshot, resource, id)

    fun effectiveTypes(snapshot: AddonManifestSnapshot, resource: Resource): Set<StremioContentType> {
        val entry = snapshot.resources.firstOrNull { Resource.parse(it.name) == resource }
        return entry?.types?.takeIf { it.isNotEmpty() } ?: snapshot.types
    }

    fun effectiveIdPrefixes(snapshot: AddonManifestSnapshot, resource: Resource): List<String> {
        val entry = snapshot.resources.firstOrNull { Resource.parse(it.name) == resource }
        return if (entry != null && entry.idPrefixes.isNotEmpty()) entry.idPrefixes else snapshot.idPrefixes
    }
}

data class ResourceCapability(
    val resource: Resource,
    val types: Set<StremioContentType>,
)
