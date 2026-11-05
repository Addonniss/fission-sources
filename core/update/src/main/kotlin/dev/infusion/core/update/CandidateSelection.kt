package dev.infusion.core.update

data class UpdateCandidate(
    val tagName: String,
    val versionName: String,
    val versionCode: Long,
    val assetName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val notesUrl: String?,
    val expectedSha256: String?,
    val checksumsUrl: String?,
)

enum class RejectionReason {

    DRAFT,

    NOT_ALPHA_CHANNEL,

    BURNED_REHEARSAL_TAG,

    BURNED_REHEARSAL_ASSET,

    BURNED_REHEARSAL_VERSION_CODE,

    MALFORMED_VERSION,

    MISSING_TV_ASSET,

    AMBIGUOUS_ASSET,

    ASSET_VERSION_INCONSISTENT,

    VERSION_CODE_INVALID,

    ASSET_SIZE_INVALID,

    DIGEST_UNAVAILABLE,

    NOT_STRICTLY_NEWER,
}

sealed interface ReleaseEvaluation {
    data class Eligible(val candidate: UpdateCandidate) : ReleaseEvaluation
    data class Rejected(val reason: RejectionReason) : ReleaseEvaluation
}

data class EvaluatedRelease(val tagName: String?, val evaluation: ReleaseEvaluation)

data class SelectionOutcome(
    val selected: UpdateCandidate?,
    val evaluations: List<EvaluatedRelease>,
) {

    val rejections: List<RejectionReason>
        get() = evaluations.mapNotNull { (it.evaluation as? ReleaseEvaluation.Rejected)?.reason }
}

object AlphaChannelSelector {

    const val BURNED_REHEARSAL_TAG = "v0.0.0-rehearsal"

    const val BURNED_REHEARSAL_VERSION_CODE = 9099L

    private const val REHEARSAL_MARKER = "rehearsal"

    const val CHECKSUMS_ASSET_NAME = "SHA256SUMS"

    fun select(releases: List<RemoteRelease>, installedVersionCode: Long): SelectionOutcome {
        val evaluations = releases.map { release ->
            EvaluatedRelease(release.tagName, evaluate(release, installedVersionCode))
        }
        val selected = evaluations
            .mapNotNull { (it.evaluation as? ReleaseEvaluation.Eligible)?.candidate }
            .maxByOrNull(UpdateCandidate::versionCode)
        return SelectionOutcome(selected = selected, evaluations = evaluations)
    }

    fun evaluate(release: RemoteRelease, installedVersionCode: Long): ReleaseEvaluation {

        if (release.draft) return ReleaseEvaluation.Rejected(RejectionReason.DRAFT)

        if (!release.prerelease) return ReleaseEvaluation.Rejected(RejectionReason.NOT_ALPHA_CHANNEL)

        val tag = release.tagName?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ReleaseEvaluation.Rejected(RejectionReason.MALFORMED_VERSION)
        if (tag.equals(BURNED_REHEARSAL_TAG, ignoreCase = true)) {
            return ReleaseEvaluation.Rejected(RejectionReason.BURNED_REHEARSAL_TAG)
        }
        if (release.assets.any { it.name.contains(REHEARSAL_MARKER, ignoreCase = true) }) {
            return ReleaseEvaluation.Rejected(RejectionReason.BURNED_REHEARSAL_ASSET)
        }

        val versionName = VersionName.parse(tag)
            ?: return ReleaseEvaluation.Rejected(RejectionReason.MALFORMED_VERSION)

        val tvAssets = release.assets.filter { TvAssetName.isTvAsset(it.name) }
        if (tvAssets.isEmpty()) return ReleaseEvaluation.Rejected(RejectionReason.MISSING_TV_ASSET)
        if (tvAssets.size > 1) return ReleaseEvaluation.Rejected(RejectionReason.AMBIGUOUS_ASSET)
        val asset = tvAssets.single()
        val parsed = TvAssetName.parse(asset.name)
            ?: return ReleaseEvaluation.Rejected(RejectionReason.MISSING_TV_ASSET)

        if (parsed.versionName != versionName) {
            return ReleaseEvaluation.Rejected(RejectionReason.ASSET_VERSION_INCONSISTENT)
        }

        if (parsed.versionCode <= 0L) return ReleaseEvaluation.Rejected(RejectionReason.VERSION_CODE_INVALID)
        if (parsed.versionCode == BURNED_REHEARSAL_VERSION_CODE) {
            return ReleaseEvaluation.Rejected(RejectionReason.BURNED_REHEARSAL_VERSION_CODE)
        }

        if (asset.sizeBytes <= 0L) return ReleaseEvaluation.Rejected(RejectionReason.ASSET_SIZE_INVALID)

        val declaredDigest = Sha256.normalize(asset.digest)
        val checksumsUrl = release.assets
            .firstOrNull { it.name == CHECKSUMS_ASSET_NAME }
            ?.downloadUrl
            ?.takeIf { it.isNotEmpty() }
        if (declaredDigest == null && checksumsUrl == null) {
            return ReleaseEvaluation.Rejected(RejectionReason.DIGEST_UNAVAILABLE)
        }

        if (parsed.versionCode <= installedVersionCode) {
            return ReleaseEvaluation.Rejected(RejectionReason.NOT_STRICTLY_NEWER)
        }

        return ReleaseEvaluation.Eligible(
            UpdateCandidate(
                tagName = tag,
                versionName = versionName.text,
                versionCode = parsed.versionCode,
                assetName = asset.name,
                downloadUrl = asset.downloadUrl,
                sizeBytes = asset.sizeBytes,
                notesUrl = release.notesUrl?.takeIf { it.isNotEmpty() },
                expectedSha256 = declaredDigest,
                checksumsUrl = checksumsUrl,
            ),
        )
    }
}
