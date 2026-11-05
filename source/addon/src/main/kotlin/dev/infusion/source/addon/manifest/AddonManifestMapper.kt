package dev.infusion.source.addon.manifest

import dev.infusion.source.addon.model.AddonBehaviorHints
import dev.infusion.source.addon.model.AddonCatalog
import dev.infusion.source.addon.model.AddonCatalogExtra
import dev.infusion.source.addon.model.AddonManifest
import dev.infusion.source.addon.model.AddonResource
import dev.infusion.source.addon.model.AddonStremioAddonsConfig
import dev.infusion.source.stremio.wire.JsonValue
import dev.infusion.source.stremio.wire.StremioBehaviorHints
import dev.infusion.source.stremio.wire.StremioManifestDto
import dev.infusion.source.stremio.wire.StremioAddonsConfig

object AddonManifestMapper {

    fun toDomain(dto: StremioManifestDto): AddonManifest =
        AddonManifest(
            id = dto.id,
            name = dto.name,
            description = dto.description,
            version = dto.version,
            logo = dto.logo,
            background = dto.background,
            catalogs = dto.catalogs.map { c ->
                AddonCatalog(
                    type = c.type,
                    id = c.id,
                    name = c.name,
                    extras = c.extras.map { e ->
                        AddonCatalogExtra(
                            name = e.name,
                            isRequired = e.isRequired,
                            options = e.options,
                            optionsLimit = e.optionsLimit,
                        )
                    },
                )
            },
            types = dto.types,

            rawTypes = (dto.raw.fields["types"] as? JsonValue.Arr)?.elements?.mapNotNull { (it as? JsonValue.Str)?.value }
                ?: emptyList(),
            resources = dto.resources.map { r ->
                AddonResource(
                    resource = r.name,
                    types = r.types,
                    idPrefixes = r.idPrefixes,
                    extraSupported = r.extraSupported,
                )
            },
            idPrefixes = dto.idPrefixes,
            behaviorHints = dto.behaviorHints?.let { b ->
                AddonBehaviorHints(
                    adult = b.adult,
                    configurable = b.configurable,
                    configurationRequired = b.configurationRequired,
                )
            },
            stremioAddonsConfig = dto.stremioAddonsConfig?.let { c ->
                AddonStremioAddonsConfig(
                    catalogId = c.catalogId,
                    addons = c.addons,
                )
            },
            language = dto.language,
            configVersion = dto.configVersion,
            timestamp = dto.timestamp,
        )
}
