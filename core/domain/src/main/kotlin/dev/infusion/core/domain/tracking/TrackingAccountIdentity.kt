package dev.infusion.core.domain.tracking

data class TrackingAccountIdentity(
    val verifiedId: String? = null,
    val label: String? = null,
) {

    val binding: String? get() = verifiedId ?: label

    val isResolved: Boolean get() = verifiedId != null

    val names: Set<String> get() = setOfNotNull(verifiedId, label)

    fun matches(boundRef: String?): Boolean =
        boundRef != null && (boundRef == verifiedId || boundRef == label)

    fun ownsBinding(boundRef: String?): Boolean = boundRef == binding || matches(boundRef)

    fun provesRemoteWrite(boundRef: String?): Boolean = when {
        boundRef == null -> false
        boundRef == verifiedId -> true
        boundRef == label -> verifiedId == null
        else -> false
    }

    companion object {

        val Unresolved: TrackingAccountIdentity = TrackingAccountIdentity()

        fun of(credentials: StoredTrackingCredentials?): TrackingAccountIdentity =
            if (credentials == null) {
                Unresolved
            } else {
                TrackingAccountIdentity(
                    verifiedId = credentials.accountId.clean(),
                    label = credentials.accountLabel.clean(),
                )
            }

        fun ofVerifiedId(verifiedId: String?): TrackingAccountIdentity =
            TrackingAccountIdentity(verifiedId = verifiedId.clean())

        private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
    }
}
