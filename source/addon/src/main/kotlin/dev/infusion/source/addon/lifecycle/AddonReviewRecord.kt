package dev.infusion.source.addon.lifecycle

import dev.infusion.core.network.CanonicalUrl
import dev.infusion.core.network.TrustClass
import dev.infusion.source.addon.AddonDisclosure
import dev.infusion.source.addon.AddonManifestSnapshot
import dev.infusion.source.stremio.url.ConfiguredAddonUrl
import dev.infusion.source.stremio.wire.StremioManifest

data class AddonReviewRecord(

    val configured: ConfiguredAddonUrl,
    val trust: TrustClass,

    val manifest: StremioManifest,
    val snapshot: AddonManifestSnapshot,
    val lanRequested: Boolean,
    val disclosure: AddonDisclosure,
) {

    val manifestUrl: CanonicalUrl get() = configured.manifestUrl
}
