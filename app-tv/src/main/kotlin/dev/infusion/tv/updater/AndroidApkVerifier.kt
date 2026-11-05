package dev.infusion.tv.updater

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import dev.infusion.core.update.ApkIdentity
import dev.infusion.core.update.ApkIdentityVerdict
import dev.infusion.core.update.ApkIdentityVerifier
import dev.infusion.core.update.Sha256
import dev.infusion.core.update.UpdateCandidate
import java.io.File
import java.security.MessageDigest

class AndroidApkVerifier(private val context: Context) {

    fun verify(file: File, candidate: UpdateCandidate): ApkIdentityVerdict {
        val identity = readIdentity(file)

            ?: return ApkIdentityVerdict.PackageMismatch(UNREADABLE_ARCHIVE)
        return ApkIdentityVerifier.verify(
            identity = identity,
            expectedPackageName = context.packageName,
            expectedVersionCode = candidate.versionCode,
            expectedVersionName = candidate.versionName,
            installedSignerSha256 = installedSignerSha256(),
        )
    }

    private fun readIdentity(file: File): ApkIdentity? {
        val info = try {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, signingFlags()) ?: return null
        } catch (_: Exception) {
            return null
        }
        val applicationInfo = info.applicationInfo ?: return null
        return ApkIdentity(
            packageName = info.packageName,
            versionCode = versionCodeOf(info),
            versionName = info.versionName,
            debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
            signerSha256 = signerSha256Of(info),
        )
    }

    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signerSha256Of(info: PackageInfo): String? {
        val bytes: ByteArray? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {

            info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            info.signatures?.firstOrNull()?.toByteArray()
        }
        val der = bytes ?: return null
        return Sha256.hex(MessageDigest.getInstance("SHA-256").digest(der))
    }

    private fun installedSignerSha256(): String? {
        val info = try {
            context.packageManager.getPackageInfo(context.packageName, signingFlags())
        } catch (_: Exception) {
            return null
        }
        return signerSha256Of(info)
    }

    private companion object {

        const val UNREADABLE_ARCHIVE = "<unreadable-apk>"
    }
}
