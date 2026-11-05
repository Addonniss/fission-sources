package dev.infusion.core.testing

import dev.infusion.core.domain.metadata.MetadataLocale
import dev.infusion.core.domain.metadata.MetadataProvider
import dev.infusion.core.model.metadata.MediaDetails
import dev.infusion.core.model.metadata.MediaIdentity

class InertMetadataProvider(
    override val providerId: String = "inert",
) : MetadataProvider {

    override suspend fun details(identity: MediaIdentity, locale: MetadataLocale): MediaDetails? = null
}
