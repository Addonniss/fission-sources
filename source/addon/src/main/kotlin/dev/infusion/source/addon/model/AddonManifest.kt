package dev.infusion.source.addon.model

import dev.infusion.source.stremio.policy.StremioContentType
import dev.infusion.source.stremio.wire.Resource

data class AddonResource(
    val resource: Resource,
    val types: Set<StremioContentType>,
    val idPrefixes: List<String>,

    val extraSupported: List<String>,
)

data class AddonCatalog(
    val type: StremioContentType,
    val id: String,
    val name: String,
    val extras: List<AddonCatalogExtra>,
)

data class AddonCatalogExtra(
    val name: String,
    val isRequired: Boolean,
    val options: List<String>,
    val optionsLimit: Int,
)

data class AddonBehaviorHints(
    val adult: Boolean?,
    val configurable: Boolean?,
    val configurationRequired: Boolean?,
)

data class AddonStremioAddonsConfig(
    val catalogId: String?,
    val addons: List<String>,
)

data class AddonManifest(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val logo: String?,
    val background: String?,
    val catalogs: List<AddonCatalog>,
    val types: Set<StremioContentType>,
    val rawTypes: List<String>,
    val resources: List<AddonResource>,
    val idPrefixes: List<String>,
    val behaviorHints: AddonBehaviorHints?,
    val stremioAddonsConfig: AddonStremioAddonsConfig?,
    val language: String?,
    val configVersion: Int?,
    val timestamp: String?,
) {

    val displayName: String get() = name

    fun supports(resource: Resource, type: StremioContentType): Boolean {
        if (type == StremioContentType.UNKNOWN) return false
        val entry = resources.firstOrNull { it.resource == resource } ?: return false
        return type in entry.types
    }

    fun allowedCatalogExtras(catalogId: String): Set<String> {
        val declared = catalogs.firstOrNull { it.id == catalogId }?.extras?.map { it.name }?.toSet() ?: emptySet()
        return setOf("search", "skip") + declared
    }

    companion object {

        val DEFAULT_EXTRA_KEYS: Set<String> = setOf("search", "skip")
    }
}
