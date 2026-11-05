package dev.infusion.core.update

import dev.infusion.core.network.CanonicalUrl
import dev.infusion.core.network.DiagnosticReason
import dev.infusion.core.network.HardenedHttpClient
import dev.infusion.core.network.HardenedRequest
import dev.infusion.core.network.NetworkLimits
import dev.infusion.core.network.NetworkPolicyException
import dev.infusion.core.network.UrlParser
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GitHubReleaseDiscovery(
    private val client: HardenedHttpClient,
    private val releasesUrl: String = RELEASES_URL,
    private val latestUrl: String = LATEST_URL,
) : UpdateCheckSource {

    override suspend fun check(installedVersionCode: Long): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            val document = read(releasesUrl, METADATA_LIMITS)
            when (document) {
                is Read.Failure -> UpdateCheckResult.NoUpdate(document.reason)
                is Read.Success -> interpret(document, installedVersionCode)
            }
        }

    private suspend fun interpret(
        document: Read.Success,
        installedVersionCode: Long,
    ): UpdateCheckResult {

        when (document.statusCode) {
            200 -> Unit

            403 -> return UpdateCheckResult.NoUpdate(
                if (document.headers["x-ratelimit-remaining"] == "0") {
                    SilentSkipReason.RATE_LIMITED
                } else {
                    SilentSkipReason.API_UNAVAILABLE
                },
            )
            429 -> return UpdateCheckResult.NoUpdate(SilentSkipReason.RATE_LIMITED)

            else -> return UpdateCheckResult.NoUpdate(SilentSkipReason.API_UNAVAILABLE)
        }

        val releases = ReleaseJson.parseList(document.body)
            ?: return UpdateCheckResult.NoUpdate(SilentSkipReason.MALFORMED_RESPONSE)
        if (releases.isEmpty()) return UpdateCheckResult.NoUpdate(SilentSkipReason.UP_TO_DATE)

        val selection = AlphaChannelSelector.select(releases, installedVersionCode)
        val candidate = selection.selected
            ?: return UpdateCheckResult.NoUpdate(
                selection.rejections.firstOrNull()?.toSilentReason() ?: SilentSkipReason.UP_TO_DATE,
            )

        return UpdateCheckResult.Found(candidate, assertLatestConsistency(candidate))
    }

    private suspend fun assertLatestConsistency(candidate: UpdateCandidate): ChannelConsistency {
        val latest = when (val read = read(latestUrl, METADATA_LIMITS)) {
            is Read.Failure -> return ChannelConsistency.Unreadable
            is Read.Success -> read
        }
        if (latest.statusCode == 404) return ChannelConsistency.NoLatestRelease
        if (latest.statusCode != 200) return ChannelConsistency.Unreadable
        val release = ReleaseJson.parseSingle(latest.body) ?: return ChannelConsistency.Unreadable
        val latestTag = release.tagName?.trim()
        return if (latestTag != null && latestTag == candidate.tagName) {
            ChannelConsistency.Contradicts(latestTag)
        } else {
            ChannelConsistency.Agrees
        }
    }

    private sealed interface Read {
        data class Success(val statusCode: Int, val body: String, val headers: Map<String, String>) : Read
        data class Failure(val reason: SilentSkipReason) : Read
    }

    private fun read(url: String, limits: NetworkLimits): Read {
        val canonical: CanonicalUrl = try {
            UrlParser.parse(url)
        } catch (_: NetworkPolicyException) {
            return Read.Failure(SilentSkipReason.API_UNAVAILABLE)
        }
        return try {
            client.execute(HardenedRequest(url = canonical, method = "GET", headers = HEADERS, limits = limits))
                .use { response ->
                    val body = runCatching {
                        response.body.readBytes().toString(StandardCharsets.UTF_8)
                    }.getOrDefault("")
                    Read.Success(statusCode = response.statusCode, body = body, headers = response.headers)
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: NetworkPolicyException) {
            Read.Failure(e.toSilentReason())
        } catch (_: Throwable) {

            Read.Failure(SilentSkipReason.NO_NETWORK)
        }
    }

    private companion object {

        const val OWNER = "Addonniss"
        const val REPO = "infusion-releases"

        const val RELEASES_URL = "https://api.github.com/repos/$OWNER/$REPO/releases?per_page=30"

        const val LATEST_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

        val METADATA_LIMITS: NetworkLimits = NetworkLimits(
            maxResponseBytes = 256L * 1024,
            maxDecompressedBytes = 512L * 1024,
            maxDecompressionRatio = 20,
            connectTimeoutMs = 10_000,
            readTimeoutMs = 20_000,
            totalTimeoutMs = 30_000,
            maxRedirects = 2,
        )

        val HEADERS: Map<String, String> = mapOf(
            "Accept" to "application/vnd.github+json",
            "X-GitHub-Api-Version" to "2022-11-28",
        )
    }
}

internal fun NetworkPolicyException.toSilentReason(): SilentSkipReason = when (reason) {
    DiagnosticReason.PRIVATE_DESTINATION,
    DiagnosticReason.SCHEME_DENIED,
    DiagnosticReason.CLEARTEXT_BLOCKED,
    DiagnosticReason.REDIRECT_POLICY,
    DiagnosticReason.INVALID_URL,
    -> SilentSkipReason.POLICY_DENIED
    else -> SilentSkipReason.NO_NETWORK
}

internal fun RejectionReason.toSilentReason(): SilentSkipReason = when (this) {
    RejectionReason.DRAFT -> SilentSkipReason.DRAFT
    RejectionReason.NOT_ALPHA_CHANNEL -> SilentSkipReason.NOT_ALPHA_CHANNEL
    RejectionReason.NOT_STRICTLY_NEWER -> SilentSkipReason.NOT_STRICTLY_NEWER
    RejectionReason.BURNED_REHEARSAL_TAG,
    RejectionReason.BURNED_REHEARSAL_ASSET,
    RejectionReason.BURNED_REHEARSAL_VERSION_CODE,
    -> SilentSkipReason.BURNED_REHEARSAL
    RejectionReason.MALFORMED_VERSION -> SilentSkipReason.MALFORMED_VERSION
    RejectionReason.ASSET_VERSION_INCONSISTENT -> SilentSkipReason.ASSET_INCONSISTENT
    RejectionReason.MISSING_TV_ASSET -> SilentSkipReason.NO_TV_ASSET
    RejectionReason.AMBIGUOUS_ASSET -> SilentSkipReason.ASSET_AMBIGUOUS
    RejectionReason.VERSION_CODE_INVALID,
    RejectionReason.ASSET_SIZE_INVALID,
    -> SilentSkipReason.MALFORMED_RESPONSE
    RejectionReason.DIGEST_UNAVAILABLE -> SilentSkipReason.DIGEST_UNAVAILABLE
}
