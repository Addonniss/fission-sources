package dev.infusion.core.update

sealed interface DigestVerdict {

    data class Expected(val sha256: String) : DigestVerdict

    data object Unavailable : DigestVerdict

    data object ChecksumsUnreadable : DigestVerdict

    data object ChecksumsMissingEntry : DigestVerdict

    data object ChecksumsDisagree : DigestVerdict
}

object DigestPolicy {

    fun resolve(
        candidate: UpdateCandidate,
        checksums: String?,
        checksumsAttempted: Boolean,
    ): DigestVerdict {
        val declared = Sha256.normalize(candidate.expectedSha256)
        val checksumsPublished = candidate.checksumsUrl != null

        if (!checksumsPublished) {

            return declared?.let(DigestVerdict::Expected) ?: DigestVerdict.Unavailable
        }

        if (!checksumsAttempted || checksums.isNullOrBlank()) return DigestVerdict.ChecksumsUnreadable
        val published = Sha256.digestFromSums(checksums, candidate.assetName)
            ?: return DigestVerdict.ChecksumsMissingEntry

        return when (declared) {
            null -> DigestVerdict.Expected(published)
            published -> DigestVerdict.Expected(declared)
            else -> DigestVerdict.ChecksumsDisagree
        }
    }
}
