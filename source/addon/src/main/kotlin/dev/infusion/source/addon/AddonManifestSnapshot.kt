package dev.infusion.source.addon

import dev.infusion.source.addon.model.AddonBehaviorHints
import dev.infusion.source.addon.model.AddonCatalog
import dev.infusion.source.addon.model.AddonCatalogExtra
import dev.infusion.source.addon.model.AddonManifest
import dev.infusion.source.addon.model.AddonResource
import dev.infusion.source.addon.model.AddonStremioAddonsConfig
import dev.infusion.source.stremio.policy.StremioContentType
import dev.infusion.source.stremio.wire.StremioCatalog
import dev.infusion.source.stremio.wire.StremioExtra
import dev.infusion.source.stremio.wire.StremioManifestDto
import dev.infusion.source.stremio.wire.StremioResource
import org.json.JSONArray
import org.json.JSONObject

data class AddonManifestSnapshot(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val resources: List<SnapshotResource>,
    val types: Set<StremioContentType>,
    val idPrefixes: List<String>,
    val catalogs: List<SnapshotCatalog>,
    val logo: String?,
    val background: String?,
    val behaviorHints: SnapshotBehaviorHints?,
    val stremioAddonsConfig: SnapshotAddonsConfig?,
    val language: String?,
    val configVersion: Int?,
    val timestamp: String?,
) {
    data class SnapshotResource(
        val name: String,
        val types: Set<StremioContentType>,
        val idPrefixes: List<String>,
    )

    data class SnapshotCatalog(
        val type: StremioContentType,
        val id: String,
        val name: String,
        val extras: List<SnapshotExtra>,
    ) {
        data class SnapshotExtra(
            val name: String,
            val isRequired: Boolean,
            val options: List<String>,
            val optionsLimit: Int,
        )
    }

    data class SnapshotBehaviorHints(
        val adult: Boolean?,
        val configurable: Boolean?,
        val configurationRequired: Boolean?,
    )

    data class SnapshotAddonsConfig(
        val catalogId: String?,
        val addons: List<String>,
    )

    val catalogVisibility: List<CatalogVisibility> get() = catalogs.map { CatalogVisibility(it.type, it.id, it.name) }

    data class CatalogVisibility(val type: StremioContentType, val id: String, val name: String)

    val requestedResourceTypes: List<String>
        get() = resources.flatMap { r -> r.types.map { t -> "${r.name}:${t.wire}" } }.sorted()
}

object AddonManifestSnapshotCodec {

    const val MAX_SNAPSHOT_BYTES = 64 * 1024

    fun encode(snapshot: AddonManifestSnapshot): String {
        val json = JSONObject()
            .put("id", snapshot.id)
            .put("name", snapshot.name)
            .put("description", snapshot.description)
            .put("version", snapshot.version)
            .put(
                "resources",
                JSONArray().apply {
                    for (r in snapshot.resources) {
                        put(
                            JSONObject()
                                .put("name", r.name)
                                .put("types", JSONArray(r.types.map { it.wire }))
                                .put("idPrefixes", JSONArray(r.idPrefixes)),
                        )
                    }
                },
            )
            .put("types", JSONArray(snapshot.types.map { it.wire }))
            .put("idPrefixes", JSONArray(snapshot.idPrefixes))
            .put(
                "catalogs",
                JSONArray().apply {
                    for (c in snapshot.catalogs) {
                        put(
                            JSONObject()
                                .put("type", c.type.wire)
                                .put("id", c.id)
                                .put("name", c.name)
                                .put(
                                    "extras",
                                    JSONArray().apply {
                                        for (e in c.extras) {
                                            put(
                                                JSONObject()
                                                    .put("name", e.name)
                                                    .put("isRequired", e.isRequired)
                                                    .put("options", JSONArray(e.options))
                                                    .put("optionsLimit", e.optionsLimit),
                                            )
                                        }
                                    },
                                ),
                        )
                    }
                },
            )
            .put("logo", snapshot.logo ?: JSONObject.NULL)
            .put("background", snapshot.background ?: JSONObject.NULL)
            .put("behaviorHints", encodeBehaviorHints(snapshot.behaviorHints))
            .put("stremioAddonsConfig", encodeAddonsConfig(snapshot.stremioAddonsConfig))
            .put("language", snapshot.language ?: JSONObject.NULL)
            .put("configVersion", snapshot.configVersion ?: JSONObject.NULL)
            .put("timestamp", snapshot.timestamp ?: JSONObject.NULL)
        val raw = json.toString()
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_SNAPSHOT_BYTES) {
            "manifest snapshot exceeds the $MAX_SNAPSHOT_BYTES byte cap"
        }
        return raw
    }

    private fun encodeBehaviorHints(hints: AddonManifestSnapshot.SnapshotBehaviorHints?): JSONObject? {
        if (hints == null) return null
        return JSONObject()
            .put("adult", hints.adult ?: JSONObject.NULL)
            .put("configurable", hints.configurable ?: JSONObject.NULL)
            .put("configurationRequired", hints.configurationRequired ?: JSONObject.NULL)
    }

    private fun encodeAddonsConfig(config: AddonManifestSnapshot.SnapshotAddonsConfig?): JSONObject? {
        if (config == null) return null
        return JSONObject()
            .put("catalogId", config.catalogId ?: JSONObject.NULL)
            .put("addons", JSONArray(config.addons))
    }

    fun fromManifest(manifest: StremioManifestDto): AddonManifestSnapshot =
        AddonManifestSnapshot(
            id = manifest.id,
            name = manifest.name,
            description = manifest.description,
            version = manifest.version,
            resources = manifest.resources.map { r ->
                AddonManifestSnapshot.SnapshotResource(r.name.wire, r.types, r.idPrefixes)
            },
            types = manifest.types,
            idPrefixes = manifest.idPrefixes,
            catalogs = manifest.catalogs.map { c ->
                AddonManifestSnapshot.SnapshotCatalog(
                    type = c.type,
                    id = c.id,
                    name = c.name,
                    extras = c.extras.map { e ->
                        AddonManifestSnapshot.SnapshotCatalog.SnapshotExtra(
                            e.name, e.isRequired, e.options, e.optionsLimit,
                        )
                    },
                )
            },
            logo = manifest.logo,
            background = manifest.background,
            behaviorHints = manifest.behaviorHints?.let { b ->
                AddonManifestSnapshot.SnapshotBehaviorHints(
                    adult = b.adult,
                    configurable = b.configurable,
                    configurationRequired = b.configurationRequired,
                )
            },
            stremioAddonsConfig = manifest.stremioAddonsConfig?.let { c ->
                AddonManifestSnapshot.SnapshotAddonsConfig(
                    catalogId = c.catalogId,
                    addons = c.addons,
                )
            },
            language = manifest.language,
            configVersion = manifest.configVersion,
            timestamp = manifest.timestamp,
        )

    fun fromDomain(manifest: AddonManifest): AddonManifestSnapshot =
        AddonManifestSnapshot(
            id = manifest.id,
            name = manifest.name,
            description = manifest.description,
            version = manifest.version,
            resources = manifest.resources.map { r ->
                AddonManifestSnapshot.SnapshotResource(r.resource.wire, r.types, r.idPrefixes)
            },
            types = manifest.types,
            idPrefixes = manifest.idPrefixes,
            catalogs = manifest.catalogs.map { c ->
                AddonManifestSnapshot.SnapshotCatalog(
                    type = c.type,
                    id = c.id,
                    name = c.name,
                    extras = c.extras.map { e ->
                        AddonManifestSnapshot.SnapshotCatalog.SnapshotExtra(
                            e.name, e.isRequired, e.options, e.optionsLimit,
                        )
                    },
                )
            },
            logo = manifest.logo,
            background = manifest.background,
            behaviorHints = manifest.behaviorHints?.let { b ->
                AddonManifestSnapshot.SnapshotBehaviorHints(
                    adult = b.adult,
                    configurable = b.configurable,
                    configurationRequired = b.configurationRequired,
                )
            },
            stremioAddonsConfig = manifest.stremioAddonsConfig?.let { c ->
                AddonManifestSnapshot.SnapshotAddonsConfig(
                    catalogId = c.catalogId,
                    addons = c.addons,
                )
            },
            language = manifest.language,
            configVersion = manifest.configVersion,
            timestamp = manifest.timestamp,
        )

    fun decode(raw: String): AddonManifestSnapshot {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_SNAPSHOT_BYTES) { "manifest snapshot exceeds the size cap" }
        val o = JSONObject(raw)
        val resources = o.getJSONArray("resources").mapElements { r ->
            AddonManifestSnapshot.SnapshotResource(
                name = r.getString("name"),
                types = r.getJSONArray("types").mapStrings { it.toContentType() }.toSet(),
                idPrefixes = r.getJSONArray("idPrefixes").mapStrings { it },
            )
        }
        val catalogs = o.getJSONArray("catalogs").mapElements { c ->
            AddonManifestSnapshot.SnapshotCatalog(
                type = c.getString("type").toContentType(),
                id = c.getString("id"),
                name = c.getString("name"),
                extras = c.getJSONArray("extras").mapElements { e ->
                    AddonManifestSnapshot.SnapshotCatalog.SnapshotExtra(
                        name = e.getString("name"),
                        isRequired = e.getBoolean("isRequired"),
                        options = e.getJSONArray("options").mapStrings { it },
                        optionsLimit = e.getInt("optionsLimit"),
                    )
                },
            )
        }
        return AddonManifestSnapshot(
            id = o.getString("id"),
            name = o.getString("name"),
            description = o.getString("description"),
            version = o.getString("version"),
            resources = resources,
            types = o.getJSONArray("types").mapStrings { it.toContentType() }.toSet(),
            idPrefixes = o.getJSONArray("idPrefixes").mapStrings { it },
            catalogs = catalogs,
            logo = o.optString("logo").takeIf { it.isNotEmpty() },
            background = o.optString("background").takeIf { it.isNotEmpty() },
            behaviorHints = o.optJSONObject("behaviorHints")?.let { h ->
                AddonManifestSnapshot.SnapshotBehaviorHints(
                    adult = if (h.isNull("adult")) null else h.optBoolean("adult"),
                    configurable = if (h.isNull("configurable")) null else h.optBoolean("configurable"),
                    configurationRequired = if (h.isNull("configurationRequired")) null else h.optBoolean("configurationRequired"),
                )
            },
            stremioAddonsConfig = o.optJSONObject("stremioAddonsConfig")?.let { c ->
                AddonManifestSnapshot.SnapshotAddonsConfig(
                    catalogId = if (c.isNull("catalogId")) null else c.optString("catalogId"),
                    addons = c.optJSONArray("addons")?.mapStrings { it } ?: emptyList(),
                )
            },
            language = o.optString("language").takeIf { it.isNotEmpty() },
            configVersion = if (o.isNull("configVersion") || !o.has("configVersion")) null else o.optInt("configVersion"),
            timestamp = o.optString("timestamp").takeIf { it.isNotEmpty() },
        )
    }

    private fun String.toContentType(): StremioContentType = StremioContentType.parse(this) ?: StremioContentType.UNKNOWN

    private inline fun <T> JSONArray.mapElements(transform: (JSONObject) -> T): List<T> {
        val out = ArrayList<T>(length())
        for (i in 0 until length()) out.add(transform(getJSONObject(i)))
        return out
    }

    private inline fun <T> JSONArray.mapStrings(transform: (String) -> T): List<T> {
        val out = ArrayList<T>(length())
        for (i in 0 until length()) out.add(transform(getString(i)))
        return out
    }
}
