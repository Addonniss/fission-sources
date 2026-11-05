package dev.infusion.source.addon

import dev.infusion.core.database.entity.AddonEntity
import dev.infusion.core.network.CanonicalUrl
import dev.infusion.core.network.UrlParser
import dev.infusion.core.network.TrustClass as NetworkTrustClass
import dev.infusion.source.addon.store.AddonCredentialStore
import dev.infusion.source.stremio.url.AddonUrlInputResult
import dev.infusion.source.stremio.url.AddonUrlNormalizer
import dev.infusion.source.stremio.url.ConfiguredAddonUrl

data class InstalledAddon(
    val addonId: String,
    val configured: ConfiguredAddonUrl,
    val snapshot: AddonManifestSnapshot,
    val disclosure: AddonDisclosure,
    val lanConsented: Boolean,
    val lanHost: String?,
    val enabled: Boolean,
    val sortOrder: Int,
    val ownerProfileId: String,
    val installedAtMs: Long,
    val updatedAtMs: Long,
    val manifestFetchedAtMs: Long,

    val displayNameOverride: String? = null,

    val liveLanHost: String? = null,
) {

    val lanTrustHost: String?
        get() = if (lanConsented) lanHost else liveLanHost

    val trust: NetworkTrustClass
        get() = if (lanTrustHost != null) NetworkTrustClass.LAN_TRUSTED else NetworkTrustClass.PUBLIC

    fun withLiveLanTrust(registry: dev.infusion.core.network.LanTrustRegistry): InstalledAddon {
        if (lanTrustHost != null) return this
        val host = configured.host
        if (host.isBlank() || !registry.isApproved(host)) return this
        return copy(liveLanHost = host)
    }

    val origin: String get() = configured.origin

    val manifestUrl: CanonicalUrl get() = configured.manifestUrl

    val displayName: String
        get() = displayNameOverride?.trim()?.takeIf { it.isNotEmpty() } ?: snapshot.name
}

object AddonMapper {

    fun toDomain(entity: AddonEntity, credentialStore: AddonCredentialStore): InstalledAddon {
        val publicBase = UrlParser.parse(entity.publicBaseUrl)
        val querySecret = entity.querySecretRefId?.let { refId ->
            val raw = credentialStore.resolve(refId)
                ?: throw IllegalArgumentException("missing or undecodable configured query secret for $refId")
            AddonUrlNormalizer.querySecretOrNull(raw)
        }
        val configured = when (val result = AddonUrlNormalizer.normalizeInput(publicBase.toString())) {
            is AddonUrlInputResult.Valid -> result.configured.copy(querySecret = querySecret)
            is AddonUrlInputResult.Invalid -> throw IllegalArgumentException("corrupt add-on public base url")
        }
        val snapshot = AddonManifestSnapshotCodec.decode(entity.manifestSnapshotJson)
        val disclosure = AddonDisclosureCodec.decode(entity.disclosureJson)
        return InstalledAddon(
            addonId = entity.addonId,
            configured = configured,
            snapshot = snapshot,
            disclosure = disclosure,
            lanConsented = entity.lanConsented,
            lanHost = entity.lanHost,
            enabled = entity.enabled,
            sortOrder = entity.sortOrder,
            ownerProfileId = entity.ownerProfileId,
            installedAtMs = entity.installedAtMs,
            updatedAtMs = entity.updatedAtMs,
            manifestFetchedAtMs = entity.manifestFetchedAtMs,
            displayNameOverride = entity.displayNameOverride,
        )
    }

    fun toEntity(
        addon: InstalledAddon,
        querySecretRefId: String?,
        manifestFetchedAtMs: Long = addon.manifestFetchedAtMs,
        removedAtMs: Long? = null,
    ): AddonEntity = AddonEntity(
        addonId = addon.addonId,
        publicBaseUrl = addon.configured.publicBase.toString(),
        querySecretRefId = querySecretRefId,
        manifestId = addon.snapshot.id,
        manifestName = addon.snapshot.name,
        manifestVersion = addon.snapshot.version,
        manifestDescription = addon.snapshot.description,
        manifestSnapshotJson = AddonManifestSnapshotCodec.encode(addon.snapshot),
        manifestFetchedAtMs = manifestFetchedAtMs,
        displayNameOverride = addon.displayNameOverride,
        disclosureJson = AddonDisclosureCodec.encode(addon.disclosure),
        lanConsented = addon.lanConsented,
        lanHost = addon.lanHost,
        enabled = addon.enabled,
        sortOrder = addon.sortOrder,
        ownerProfileId = addon.ownerProfileId,
        installedAtMs = addon.installedAtMs,
        updatedAtMs = addon.updatedAtMs,
        removedAtMs = removedAtMs,
    )
}
