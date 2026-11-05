package dev.infusion.tv.updater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import dev.infusion.core.update.ApkIdentityVerdict
import dev.infusion.core.update.DigestPolicy
import dev.infusion.core.update.DigestVerdict
import dev.infusion.core.update.DownloadOutcome
import dev.infusion.core.update.UpdateCandidate
import dev.infusion.core.update.UpdateDownloader
import dev.infusion.core.update.UpdateFailureKind
import dev.infusion.core.update.UpdateStorage
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidUpdateInstaller(
    private val context: Context,
    private val downloader: UpdateDownloader,
    private val verifier: AndroidApkVerifier,
    private val storage: UpdateStorage,
) : UpdateInstaller {

    override fun observeResults(listener: (InstallOutcome) -> Unit) {
        UpdateInstallResults.attach(listener)
    }

    override suspend fun install(
        candidate: UpdateCandidate,
        onEvent: (UpdateInstallEvent) -> Unit,
    ): InstallOutcome = withContext(Dispatchers.IO) {

        val checksumsAttempted = candidate.checksumsUrl != null
        val checksums = candidate.checksumsUrl?.let { downloader.fetchChecksums(it) }
        val expectedDigest = when (val verdict = DigestPolicy.resolve(candidate, checksums, checksumsAttempted)) {
            is DigestVerdict.Expected -> verdict.sha256
            DigestVerdict.Unavailable,
            DigestVerdict.ChecksumsUnreadable,
            DigestVerdict.ChecksumsMissingEntry,
            -> return@withContext InstallOutcome.Failed(UpdateFailureKind.CHECKSUMS_UNAVAILABLE)
            DigestVerdict.ChecksumsDisagree ->
                return@withContext InstallOutcome.Failed(UpdateFailureKind.HASH_MISMATCH)
        }

        onEvent(UpdateInstallEvent.Downloading(bytesRead = 0L, totalBytes = candidate.sizeBytes))
        val download = downloader.download(
            candidate = candidate,
            expectedSha256 = expectedDigest,
            storage = storage,
            onProgress = { read, total -> onEvent(UpdateInstallEvent.Downloading(read, total)) },
        )
        val file: File = when (download) {
            is DownloadOutcome.Downloaded -> download.file
            is DownloadOutcome.Failed -> return@withContext InstallOutcome.Failed(download.kind)
        }

        onEvent(UpdateInstallEvent.Verifying)
        when (verifier.verify(file, candidate)) {
            ApkIdentityVerdict.Ok -> Unit
            is ApkIdentityVerdict.PackageMismatch ->
                return@withContext InstallOutcome.Failed(UpdateFailureKind.PACKAGE_MISMATCH)
            is ApkIdentityVerdict.VersionMismatch ->
                return@withContext InstallOutcome.Failed(UpdateFailureKind.VERSION_MISMATCH)
            ApkIdentityVerdict.DebuggableRefused ->
                return@withContext InstallOutcome.Failed(UpdateFailureKind.DEBUGGABLE_REFUSED)
            ApkIdentityVerdict.SignerMismatch,
            ApkIdentityVerdict.SignerUnavailable,
            -> return@withContext InstallOutcome.Failed(UpdateFailureKind.SIGNER_MISMATCH)
        }

        if (!context.packageManager.canRequestPackageInstalls()) {
            return@withContext InstallOutcome.PermissionMissing
        }
        commitSession(file)
    }

    private fun commitSession(file: File): InstallOutcome {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)

            setInstallLocation(PackageInfo.INSTALL_LOCATION_AUTO)

        }
        val sessionId = try {
            installer.createSession(params)
        } catch (_: Exception) {
            return InstallOutcome.Failed(UpdateFailureKind.INSTALL_FAILED)
        }
        try {
            installer.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite(SESSION_APK_NAME, 0L, file.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                session.commit(UpdateInstallResults.pendingIntent(context, sessionId).intentSender)
            }
        } catch (e: CancellationException) {
            abandon(installer, sessionId)
            throw e
        } catch (_: Exception) {
            abandon(installer, sessionId)
            return InstallOutcome.Failed(UpdateFailureKind.INSTALL_FAILED)
        }
        return InstallOutcome.AwaitingUserConfirmation
    }

    private fun abandon(installer: PackageInstaller, sessionId: Int) {
        runCatching { installer.abandonSession(sessionId) }
    }

    private companion object {
        const val SESSION_APK_NAME = "base.apk"
    }
}

fun openUnknownSourcesSettings(context: Context): Boolean {
    val intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching {
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}
