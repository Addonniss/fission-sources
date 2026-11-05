package dev.infusion.core.update

data class ApkIdentity(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val debuggable: Boolean,

    val signerSha256: String?,
)

sealed interface ApkIdentityVerdict {

    data object Ok : ApkIdentityVerdict

    data class PackageMismatch(val actualPackageName: String) : ApkIdentityVerdict

    data class VersionMismatch(
        val actualVersionCode: Long,
        val actualVersionName: String?,
    ) : ApkIdentityVerdict

    data object DebuggableRefused : ApkIdentityVerdict

    data object SignerMismatch : ApkIdentityVerdict

    data object SignerUnavailable : ApkIdentityVerdict
}

object CertificateDigest {

    fun normalize(declared: String?): String? {
        val trimmed = declared?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val hex = trimmed.replace(":", "").replace(" ", "").lowercase()
        if (hex.length != 64 || hex.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return hex
    }

    fun matches(installed: String?, candidateFile: String?): Boolean {
        val left = normalize(installed) ?: return false
        val right = normalize(candidateFile) ?: return false
        return left == right
    }
}

object ApkIdentityVerifier {

    fun verify(
        identity: ApkIdentity,
        expectedPackageName: String,
        expectedVersionCode: Long,
        expectedVersionName: String,
        installedSignerSha256: String?,
    ): ApkIdentityVerdict {
        if (identity.packageName != expectedPackageName) {
            return ApkIdentityVerdict.PackageMismatch(identity.packageName)
        }
        if (identity.versionCode != expectedVersionCode || identity.versionName != expectedVersionName) {
            return ApkIdentityVerdict.VersionMismatch(
                actualVersionCode = identity.versionCode,
                actualVersionName = identity.versionName,
            )
        }
        if (identity.debuggable) return ApkIdentityVerdict.DebuggableRefused
        val candidateSigner = CertificateDigest.normalize(identity.signerSha256)
            ?: return ApkIdentityVerdict.SignerUnavailable
        if (CertificateDigest.normalize(installedSignerSha256) == null) {

            return ApkIdentityVerdict.SignerUnavailable
        }
        if (!CertificateDigest.matches(installedSignerSha256, candidateSigner)) {
            return ApkIdentityVerdict.SignerMismatch
        }
        return ApkIdentityVerdict.Ok
    }
}
