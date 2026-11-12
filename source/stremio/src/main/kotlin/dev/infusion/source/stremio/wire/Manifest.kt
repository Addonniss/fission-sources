package dev.infusion.source.stremio.wire

import dev.infusion.core.network.DiagnosticReason
import dev.infusion.source.stremio.policy.StremioContentType
import dev.infusion.source.stremio.policy.StremioId
import dev.infusion.source.stremio.route.StremioRoutes
import dev.infusion.source.stremio.wire.WireJson.array
import dev.infusion.source.stremio.wire.WireJson.bool
import dev.infusion.source.stremio.wire.WireJson.has
import dev.infusion.source.stremio.wire.WireJson.intOrNull
import dev.infusion.source.stremio.wire.WireJson.obj
import dev.infusion.source.stremio.wire.WireJson.requiredArray
import dev.infusion.source.stremio.wire.WireJson.requiredString
import dev.infusion.source.stremio.wire.WireJson.string
import dev.infusion.source.stremio.wire.WireJson.stringArray

enum class Resource(val wire: String) {
    CATALOG("catalog"),
    META("meta"),
    STREAM("stream"),
    SUBTITLES("subtitles"),

    ADDON_CATALOG("addon_catalog"),
    ;

    companion object {
        fun parse(value: String): Resource? = entries.firstOrNull { it.wire == value }
    }
}

data class StremioResource(
    val name: Resource,
    val types: Set<StremioContentType>,
    val idPrefixes: List<String>,

    val extraSupported: List<String>,
)

data class StremioCatalog(
    val type: StremioContentType,
    val id: String,
    val name: String,
    val extras: List<StremioExtra>,
)

data class StremioExtra(
    val name: String,
    val isRequired: Boolean,
    val options: List<String>,
    val optionsLimit: Int,
)

data class StremioBehaviorHints(
    val adult: Boolean?,
    val configurable: Boolean?,
    val configurationRequired: Boolean?,
)

data class StremioAddonsConfig(
    val catalogId: String?,
    val addons: List<String>,
)

typealias StremioManifest = StremioManifestDto

data class StremioManifestDto(
    val raw: JsonValue.Obj,
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val resources: List<StremioResource>,
    val types: Set<StremioContentType>,
    val catalogs: List<StremioCatalog>,
    val idPrefixes: List<String>,
    val logo: String?,
    val background: String?,
    val behaviorHints: StremioBehaviorHints?,
    val stremioAddonsConfig: StremioAddonsConfig?,
    val language: String?,

    val configVersion: Long?,
    val timestamp: String?,
) {

    fun supports(resource: Resource, type: StremioContentType): Boolean {
        if (!type.isDeclaredContentType) return false
        val entry = resources.firstOrNull { it.name == resource } ?: return false
        return type in entry.types
    }

    fun allowedCatalogExtras(catalogId: String): Set<String> {
        val declared = catalogs.firstOrNull { it.id == catalogId }?.extras?.map { it.name }?.toSet() ?: emptySet()
        return StremioRoutes.DEFAULT_EXTRA_KEYS + declared
    }

    companion object {
        const val MAX_RESOURCES = 64
        const val MAX_CATALOGS = 256

        const val MAX_EXTRA_OPTIONS = 64

        const val MAX_EXTRA_OPTION_VALUES = 512
        const val MAX_ID_PREFIXES = 32
        const val MAX_ID_PREFIX_BYTES = 128
        const val MAX_DESCRIPTION_BYTES = 8192
        const val MAX_VERSION_BYTES = 64
        const val MAX_EXTRA_OPTION_BYTES = 256
        const val MAX_EXTRA_NAME_BYTES = 64

        const val MAX_EXTRA_OPTIONS_LIMIT = 1024
        const val MAX_LOGO_BACKGROUND_BYTES = 4096
        const val MAX_LANGUAGE_BYTES = 64

        const val MAX_CONFIG_VERSION = Long.MAX_VALUE
        const val MAX_TIMESTAMP_BYTES = 128
        const val MAX_ADDONS_CONFIG_CATALOG_BYTES = 512
        const val MAX_ADDONS_CONFIG_ADDONS = 64
        const val MAX_ADDONS_CONFIG_ADDON_BYTES = 512
    }
}

object ManifestParser {

    fun parse(root: JsonValue.Obj): StremioManifestDto {
        val raw = root
        val id = requiredString(root, "id")
        val name = requiredString(root, "name")

        val description = string(root, "description").orEmpty()
        val version = requiredString(root, "version")

        StremioId.validateOrThrow(id)
        StremioId.validateBoundedString(name, StremioId.MAX_NAME_BYTES, "name")
        if (description.isNotEmpty()) {
            StremioId.validateBoundedString(description, StremioManifestDto.MAX_DESCRIPTION_BYTES, "description")
        }
        validateVersion(version)

        val types = parseTypes(root)
        val idPrefixes = parseIdPrefixes(root)
        val resources = parseResources(root, types, idPrefixes)
        val catalogs = parseCatalogs(root, types)

        return StremioManifestDto(
            raw = raw,
            id = id,
            name = name,
            description = description,
            version = version,
            resources = resources,
            types = types,
            catalogs = catalogs,
            idPrefixes = idPrefixes,
            logo = optionalBoundedString(root, "logo", StremioManifestDto.MAX_LOGO_BACKGROUND_BYTES, "logo"),
            background = optionalBoundedString(root, "background", StremioManifestDto.MAX_LOGO_BACKGROUND_BYTES, "background"),
            behaviorHints = parseBehaviorHints(root),
            stremioAddonsConfig = parseAddonsConfig(root),
            language = optionalBoundedString(root, "language", StremioManifestDto.MAX_LANGUAGE_BYTES, "language"),
            configVersion = intOrNull(root, "configVersion", max = StremioManifestDto.MAX_CONFIG_VERSION),
            timestamp = optionalBoundedString(root, "timestamp", StremioManifestDto.MAX_TIMESTAMP_BYTES, "timestamp"),
        )
    }

    private fun parseTypes(root: JsonValue.Obj): Set<StremioContentType> {

        val entries = array(root, "types")?.map { v ->
            (v as? JsonValue.Str)?.value
                ?: throw WireException(DiagnosticReason.SCHEMA_INVALID, "types must be an array of strings")
        } ?: return emptySet()
        return entries.map { t -> StremioContentType.parseOrUnknown(t) }.toSet()
    }

    private fun parseIdPrefixes(root: JsonValue.Obj): List<String> {
        val prefixes = stringArray(root, "idPrefixes", maxElements = StremioManifestDto.MAX_ID_PREFIXES) ?: return emptyList()
        for (p in prefixes) {
            if (p.toByteArray(Charsets.UTF_8).size > StremioManifestDto.MAX_ID_PREFIX_BYTES) {
                throw WireException(DiagnosticReason.SCHEMA_INVALID, "id prefix too long")
            }
            for (c in p) {
                if (c.code < 0x20 || c.code == 0x7F) {
                    throw WireException(DiagnosticReason.SCHEMA_INVALID, "control character in id prefix")
                }
            }
        }
        return prefixes
    }

    private fun parseResources(
        root: JsonValue.Obj,
        manifestTypes: Set<StremioContentType>,
        manifestPrefixes: List<String>,
    ): List<StremioResource> {

        val entries = array(root, "resources") ?: return emptyList()
        if (entries.size > StremioManifestDto.MAX_RESOURCES) {
            throw WireException(DiagnosticReason.LIMIT_EXCEEDED, "too many resources")
        }
        val seen = HashSet<String>()
        return entries.map { v ->
            when (v) {
                is JsonValue.Str -> {
                    val name = Resource.parse(v.value)
                        ?: throw WireException(DiagnosticReason.SCHEMA_INVALID, "unsupported resource")
                    if (!seen.add(name.wire)) throw WireException(DiagnosticReason.SCHEMA_INVALID, "duplicate resource")

                    StremioResource(name, resourceTypes(name, manifestTypes), manifestPrefixes, emptyList())
                }
                is JsonValue.Obj -> parseResourceObject(v, manifestTypes, manifestPrefixes, seen)
                else -> throw WireException(DiagnosticReason.SCHEMA_INVALID, "resources must be strings or objects")
            }
        }
    }

    private fun resourceTypes(name: Resource, manifestTypes: Set<StremioContentType>): Set<StremioContentType> =
        if (name == Resource.ADDON_CATALOG) emptySet() else manifestTypes

    private fun parseResourceObject(
        o: JsonValue.Obj,
        manifestTypes: Set<StremioContentType>,
        manifestPrefixes: List<String>,
        seen: MutableSet<String>,
    ): StremioResource {
        val nameStr = requiredString(o, "name")
        val name = Resource.parse(nameStr)
            ?: throw WireException(DiagnosticReason.SCHEMA_INVALID, "unsupported resource")
        if (!seen.add(name.wire)) throw WireException(DiagnosticReason.SCHEMA_INVALID, "duplicate resource")

        val types = if (has(o, "types")) {
            val list = requiredArray(o, "types").map { v ->
                (v as? JsonValue.Str)?.value
                    ?: throw WireException(DiagnosticReason.SCHEMA_INVALID, "resource types must be strings")
            }

            list.map { t -> StremioContentType.parseOrUnknown(t) }.toSet()
        } else {
            manifestTypes
        }
        val effectiveTypes = if (name == Resource.ADDON_CATALOG) emptySet() else types
        val prefixes = if (has(o, "idPrefixes")) {
            stringArray(o, "idPrefixes", maxElements = StremioManifestDto.MAX_ID_PREFIXES) ?: emptyList()
        } else {
            manifestPrefixes
        }
        val extraSupported = if (name == Resource.CATALOG && has(o, "extraSupported")) {
            stringArray(o, "extraSupported", maxElements = StremioManifestDto.MAX_EXTRA_OPTIONS, maxElementBytes = StremioManifestDto.MAX_EXTRA_NAME_BYTES) ?: emptyList()
        } else {
            emptyList()
        }
        return StremioResource(name, effectiveTypes, prefixes, extraSupported)
    }

    private fun parseCatalogs(root: JsonValue.Obj, manifestTypes: Set<StremioContentType>): List<StremioCatalog> {

        val entries = array(root, "catalogs") ?: return emptyList()
        if (entries.size > StremioManifestDto.MAX_CATALOGS) {
            throw WireException(DiagnosticReason.LIMIT_EXCEEDED, "too many catalogs")
        }

        val seen = HashSet<String>()
        return entries.map { v ->
            val o = v as? JsonValue.Obj
                ?: throw WireException(DiagnosticReason.SCHEMA_INVALID, "catalogs must be objects")

            val type = StremioContentType.parseOrUnknown(requiredString(o, "type"))
            if (type != StremioContentType.UNKNOWN && type !in manifestTypes) {
                throw WireException(DiagnosticReason.SCHEMA_INVALID, "catalog type not declared in types")
            }
            val id = requiredString(o, "id")
            StremioId.validateOrThrow(id)

            if (!seen.add("${type.wire}\u0000$id")) {
                throw WireException(DiagnosticReason.SCHEMA_INVALID, "duplicate catalog id")
            }

            val name = string(o, "name").orEmpty().ifEmpty { id }
            StremioId.validateBoundedString(name, StremioId.MAX_NAME_BYTES, "catalog name")
            val extras = parseExtras(o)
            StremioCatalog(type, id, name, extras)
        }
    }

    private fun parseExtras(o: JsonValue.Obj): List<StremioExtra> {
        val entries = WireJson.array(o, "extra") ?: return emptyList()
        if (entries.size > StremioManifestDto.MAX_EXTRA_OPTIONS) {
            throw WireException(DiagnosticReason.LIMIT_EXCEEDED, "too many catalog extras")
        }
        val seen = HashSet<String>()
        return entries.map { v ->
            val e = v as? JsonValue.Obj
                ?: throw WireException(DiagnosticReason.SCHEMA_INVALID, "extra must be an object")
            val name = requiredString(e, "name")
            if (name.toByteArray(Charsets.UTF_8).size > StremioManifestDto.MAX_EXTRA_NAME_BYTES ||
                !Regex("^[a-zA-Z0-9_-]+$").matches(name)
            ) {
                throw WireException(DiagnosticReason.SCHEMA_INVALID, "invalid extra name")
            }
            if (!seen.add(name)) throw WireException(DiagnosticReason.SCHEMA_INVALID, "duplicate extra name")
            val isRequired = bool(e, "isRequired") ?: false

            val options = stringArray(e, "options", maxElements = StremioManifestDto.MAX_EXTRA_OPTION_VALUES, maxElementBytes = StremioManifestDto.MAX_EXTRA_OPTION_BYTES)
                ?: emptyList()
            val optionsLimit = intOrNull(e, "optionsLimit", max = StremioManifestDto.MAX_EXTRA_OPTIONS_LIMIT.toLong())?.toInt() ?: 1
            StremioExtra(name, isRequired, options, optionsLimit)
        }
    }

    private fun parseBehaviorHints(root: JsonValue.Obj): StremioBehaviorHints? {
        val o = obj(root, "behaviorHints") ?: return null
        return StremioBehaviorHints(
            adult = bool(o, "adult"),
            configurable = bool(o, "configurable"),
            configurationRequired = bool(o, "configurationRequired"),
        )
    }

    private fun parseAddonsConfig(root: JsonValue.Obj): StremioAddonsConfig? {
        val o = obj(root, "stremioAddonsConfig") ?: return null
        val catalogId = string(o, "catalogId")?.let {
            if (it.toByteArray(Charsets.UTF_8).size > StremioManifestDto.MAX_ADDONS_CONFIG_CATALOG_BYTES) {
                throw WireException(DiagnosticReason.SCHEMA_INVALID, "addons config catalogId too long")
            }
            it
        }
        val addons = stringArray(
            o,
            "addons",
            maxElements = StremioManifestDto.MAX_ADDONS_CONFIG_ADDONS,
            maxElementBytes = StremioManifestDto.MAX_ADDONS_CONFIG_ADDON_BYTES,
        ) ?: emptyList()
        return StremioAddonsConfig(catalogId = catalogId, addons = addons)
    }

    private fun optionalBoundedString(o: JsonValue.Obj, key: String, maxBytes: Int, what: String): String? =
        string(o, key)?.let {
            StremioId.validateBoundedString(it, maxBytes, what)
            it
        }

    private fun validateVersion(version: String) {
        if (version.toByteArray(Charsets.UTF_8).size > StremioManifestDto.MAX_VERSION_BYTES) {
            throw WireException(DiagnosticReason.SCHEMA_INVALID, "version too long")
        }
        if (version.any { !(it.isLetterOrDigit() || it in ".+-") }) {
            throw WireException(DiagnosticReason.SCHEMA_INVALID, "invalid version characters")
        }
    }
}
