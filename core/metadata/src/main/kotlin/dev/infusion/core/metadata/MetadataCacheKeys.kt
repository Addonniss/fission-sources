package dev.infusion.core.metadata

import dev.infusion.core.domain.metadata.MetadataLocale
import dev.infusion.core.model.metadata.MediaIdentity

object MetadataCacheKeys {

    fun details(identity: MediaIdentity, locale: MetadataLocale): String? =
        identity.cacheKey()?.let { key -> "$key#${locale.tag}" }

    fun season(identity: MediaIdentity, locale: MetadataLocale, seasonNumber: Int): String? =
        details(identity, locale)?.let { key -> "$key#s$seasonNumber" }
}
