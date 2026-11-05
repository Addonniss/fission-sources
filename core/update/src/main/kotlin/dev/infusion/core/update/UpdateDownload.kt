package dev.infusion.core.update

import dev.infusion.core.network.CanonicalUrl
import dev.infusion.core.network.DiagnosticReason
import dev.infusion.core.network.HardenedHttpClient
import dev.infusion.core.network.HardenedRequest
import dev.infusion.core.network.NetworkLimits
import dev.infusion.core.network.NetworkPolicyException
import dev.infusion.core.network.RequestCancellation
import dev.infusion.core.network.UrlParser
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface UpdateStorage {

    fun partFile(versionCode: Long): File

    fun verifiedFile(versionCode: Long): File

    fun cleanup(): Int

    fun hasRoomFor(bytes: Long): Boolean = true
}

class DirectoryUpdateStorage(private val directory: File) : UpdateStorage {

    override fun partFile(versionCode: Long): File = File(directory, "$versionCode.apk.part")

    override fun verifiedFile(versionCode: Long): File = File(directory, "$versionCode.apk")

    override fun cleanup(): Int {
        val files = directory.listFiles() ?: return 0
        var removed = 0
        for (file in files) {
            if (!file.isFile) continue
            if (file.name.endsWith(".apk") || file.name.endsWith(".apk.part")) {
                if (file.delete()) removed++
            }
        }
        return removed
    }

    override fun hasRoomFor(bytes: Long): Boolean {
        val probe = when {
            directory.exists() -> directory
            else -> directory.parentFile ?: return true
        }
        val usable = probe.usableSpace

        if (usable <= 0L) return true

        return usable > bytes + (2L * 1024 * 1024)
    }
}

sealed interface DownloadOutcome {

    data class Downloaded(val file: File, val sha256: String) : DownloadOutcome

    data class Failed(val kind: UpdateFailureKind) : DownloadOutcome
}

class UpdateDownloader(private val client: HardenedHttpClient) {

    suspend fun download(
        candidate: UpdateCandidate,
        expectedSha256: String?,
        storage: UpdateStorage,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> },
        cancellation: RequestCancellation? = null,
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        val expected = Sha256.normalize(expectedSha256)
            ?: return@withContext DownloadOutcome.Failed(UpdateFailureKind.CHECKSUMS_UNAVAILABLE)
        if (!storage.hasRoomFor(candidate.sizeBytes)) {
            return@withContext DownloadOutcome.Failed(UpdateFailureKind.INSUFFICIENT_STORAGE)
        }

        val part = storage.partFile(candidate.versionCode)
        part.parentFile?.mkdirs()
        if (part.exists() && !part.delete()) {
            return@withContext DownloadOutcome.Failed(UpdateFailureKind.DOWNLOAD_FAILED)
        }

        val canonical: CanonicalUrl = try {
            UrlParser.parse(candidate.downloadUrl)
        } catch (_: NetworkPolicyException) {
            return@withContext DownloadOutcome.Failed(UpdateFailureKind.DOWNLOAD_FAILED)
        }

        var outcome: DownloadOutcome = DownloadOutcome.Failed(UpdateFailureKind.DOWNLOAD_FAILED)
        try {
            client.execute(
                HardenedRequest(
                    url = canonical,
                    method = "GET",
                    limits = limitsFor(candidate.sizeBytes),
                    cancellation = cancellation,
                ),
            ).use { response ->
                if (response.statusCode != 200) {
                    return@use
                }
                val digest = try {
                    part.outputStream().use { sink ->
                        Sha256.copyAndHash(
                            source = response.body,
                            sink = sink,
                            maxBytes = candidate.sizeBytes,
                        ) { written ->
                            onProgress(written, candidate.sizeBytes)
                        }
                    }
                } catch (_: SizeMismatchException) {
                    outcome = DownloadOutcome.Failed(UpdateFailureKind.DOWNLOAD_FAILED)
                    return@use
                }
                if (!digest.equals(expected, ignoreCase = true)) {
                    outcome = DownloadOutcome.Failed(UpdateFailureKind.HASH_MISMATCH)
                    return@use
                }
                val verified = storage.verifiedFile(candidate.versionCode)
                if (verified.exists() && !verified.delete()) {
                    outcome = DownloadOutcome.Failed(UpdateFailureKind.DOWNLOAD_FAILED)
                    return@use
                }
                if (!part.renameTo(verified)) {
                    outcome = DownloadOutcome.Failed(UpdateFailureKind.DOWNLOAD_FAILED)
                    return@use
                }
                outcome = DownloadOutcome.Downloaded(file = verified, sha256 = digest)
            }
        } catch (e: CancellationException) {
            part.delete()
            throw e
        } catch (e: NetworkPolicyException) {
            outcome = DownloadOutcome.Failed(
                if (e.reason == DiagnosticReason.TIMEOUT) {
                    UpdateFailureKind.DOWNLOAD_INTERRUPTED
                } else {
                    UpdateFailureKind.DOWNLOAD_FAILED
                },
            )
        } catch (e: java.io.IOException) {
            outcome = DownloadOutcome.Failed(UpdateFailureKind.DOWNLOAD_INTERRUPTED)
        } catch (_: Throwable) {
            outcome = DownloadOutcome.Failed(UpdateFailureKind.DOWNLOAD_FAILED)
        }

        if (outcome !is DownloadOutcome.Downloaded) {
            part.delete()
        }
        outcome
    }

    suspend fun fetchChecksums(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val canonical = UrlParser.parse(url)
            client.execute(HardenedRequest(url = canonical, method = "GET", limits = CHECKSUM_LIMITS))
                .use { response ->
                    if (response.statusCode != 200) return@use null
                    response.body.readBytes().toString(StandardCharsets.UTF_8)
                }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    private fun limitsFor(sizeBytes: Long): NetworkLimits = NetworkLimits(

        maxResponseBytes = sizeBytes,
        maxDecompressedBytes = sizeBytes,
        maxDecompressionRatio = 20,
        connectTimeoutMs = 10_000,
        readTimeoutMs = 30_000,

        totalTimeoutMs = 0,
        maxRedirects = 3,
    )

    private companion object {

        val CHECKSUM_LIMITS: NetworkLimits = NetworkLimits(
            maxResponseBytes = 64L * 1024,
            maxDecompressedBytes = 128L * 1024,
            maxDecompressionRatio = 20,
            connectTimeoutMs = 10_000,
            readTimeoutMs = 20_000,
            totalTimeoutMs = 30_000,
            maxRedirects = 3,
        )
    }
}
