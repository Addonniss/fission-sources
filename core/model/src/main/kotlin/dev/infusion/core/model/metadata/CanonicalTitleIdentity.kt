package dev.infusion.core.model.metadata

import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TypedExternalId
import java.util.Locale
import java.util.UUID

object CanonicalTitleIdentity {

    private const val NAMESPACE = "infusion:canonical:materialized:v1"

    private const val KIND_SCOPED_NAMESPACE = "infusion:canonical:kind-scoped:v1"

    fun of(external: TypedExternalId): TitleId = TitleId(
        UUID.nameUUIDFromBytes("$NAMESPACE:${external.authority}:${external.value}".toByteArray()),
    )

    fun of(identity: MediaIdentity): TitleId? =
        identity.externalIds.typed().firstOrNull()?.let(::of)

    fun kindScoped(external: TypedExternalId, type: MediaType): TitleId = TitleId(
        UUID.nameUUIDFromBytes(
            (
                "$KIND_SCOPED_NAMESPACE:${type.name.lowercase(Locale.ROOT)}:" +
                    "${external.authority}:${external.value}"
                ).toByteArray(),
        ),
    )
}
