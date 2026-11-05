package dev.infusion.source.addon.resource

import dev.infusion.core.domain.catalog.CatalogImage
import dev.infusion.core.network.CanonicalUrl
import dev.infusion.core.network.HardenedRequest
import dev.infusion.core.network.NetworkLimits
import dev.infusion.core.network.NetworkPolicyException
import dev.infusion.core.network.UrlParser
import dev.infusion.source.addon.repository.InstalledAddonRuntime
import dev.infusion.source.aggregation.resource.AddonArtworkRepository
import dev.infusion.source.aggregation.resource.AddonFetchedItemArtwork
import java.io.IOException
import java.util.LinkedHashMap

class DefaultAddonArtworkRepository(
    private val runtimes: AddonResourceRuntimeAccessor,
    private val imageLimits: NetworkLimits = DEFAULT_POSTER_LIMITS,

    private val maxRetainedEntries: Int = DEFAULT_MAX_RETAINED_ENTRIES,
) : AddonArtworkRepository {

    private val lock = Any()
    private val retained = LinkedHashMap<RetainedKey, CanonicalUrl>(16, 0.75f, true)

    private val owned = LinkedHashMap<RetainedKey, OwnedUrl>(16, 0.75f)

    private class OwnedUrl(var url: CanonicalUrl) {
        val ownerKeys = mutableSetOf<String>()
    }

    private data class RetainedKey(
        val addonId: String,
        val nativeItemId: String,
        val slot: String,
    )

    override fun retain(addonId: String, nativeItemId: String, posterUrl: String?) {
        retainUrl(RetainedKey(addonId, nativeItemId, POSTER_SLOT), posterUrl)
    }

    override fun retainLandscape(addonId: String, nativeItemId: String, backgroundUrl: String?) {
        retainUrl(RetainedKey(addonId, nativeItemId, LANDSCAPE_SLOT), backgroundUrl)
    }

    private fun retainUrl(key: RetainedKey, url: String?) {
        synchronized(lock) {
            val canonical = canonicalOrNull(url)
            if (canonical == null) {
                retained.remove(key)
            } else {
                retained[key] = canonical
                trimRetained()
            }
        }
    }

    private fun canonicalOrNull(url: String?): CanonicalUrl? {
        if (url == null) return null
        return try {
            UrlParser.parse(url)
        } catch (e: NetworkPolicyException) {

            null
        }
    }

    private fun trimRetained() {
        if (retained.size > maxRetainedEntries) {
            val it = retained.entries.iterator()
            if (it.hasNext()) it.next()
            it.remove()
        }
    }

    override fun ownFetchedPage(addonId: String, ownerKey: String, items: List<AddonFetchedItemArtwork>) {
        synchronized(lock) {
            items.forEach { item ->
                item.posterUrl?.let { url ->
                    own(RetainedKey(addonId, item.nativeItemId, POSTER_SLOT), ownerKey, url)
                }
                item.landscapeUrl?.let { url ->
                    own(RetainedKey(addonId, item.nativeItemId, LANDSCAPE_SLOT), ownerKey, url)
                }
            }
        }
    }

    private fun own(key: RetainedKey, ownerKey: String, url: String) {
        val canonical = canonicalOrNull(url) ?: return
        val existing = owned[key]
        if (existing == null) {
            owned[key] = OwnedUrl(canonical).also { entry -> entry.ownerKeys += ownerKey }
        } else {

            existing.url = canonical
            existing.ownerKeys += ownerKey
        }
    }

    override fun releaseFetchedPage(addonId: String, ownerKey: String) {
        synchronized(lock) {
            val entries = owned.entries.iterator()
            while (entries.hasNext()) {
                val (key, entry) = entries.next()
                if (key.addonId != addonId) continue

                if (entry.ownerKeys.remove(ownerKey) && entry.ownerKeys.isEmpty()) entries.remove()
            }
        }
    }

    override suspend fun poster(addonId: String, nativeItemId: String): CatalogImage? =
        fetch(RetainedKey(addonId, nativeItemId, POSTER_SLOT))

    override suspend fun landscape(addonId: String, nativeItemId: String): CatalogImage? =
        fetch(RetainedKey(addonId, nativeItemId, LANDSCAPE_SLOT))

    private suspend fun fetch(key: RetainedKey): CatalogImage? {
        val url = synchronized(lock) { promotedUrlFor(key) } ?: return null
        val runtime = runtimes.runtimeFor(key.addonId) ?: return null
        if (!runtime.installed.enabled || !runtime.providerState.isEnabled) return null
        return runtime.useClient { client ->
            try {
                val response = client.hardenedTransport.execute(
                    HardenedRequest(
                        url = url,
                        limits = imageLimits,
                        cancellation = runtime.providerState.currentScope(),
                    ),
                )
                try {
                    if (response.statusCode !in 200..299) return@useClient null
                    val contentType = response.headers["content-type"].orEmpty()
                        .substringBefore(';').trim().lowercase()
                    if (!contentType.startsWith("image/")) return@useClient null
                    val bytes = readBounded(response.body, imageLimits.maxResponseBytes) ?: return@useClient null
                    if (bytes.isEmpty()) return@useClient null
                    CatalogImage(contentType = contentType.ifEmpty { null }, bytes = bytes)
                } finally {
                    response.close()
                }
            } catch (e: NetworkPolicyException) {
                null
            } catch (e: IOException) {
                null
            }
        }
    }

    private fun promotedUrlFor(key: RetainedKey): CanonicalUrl? {
        retained[key]?.let { return it }
        val ownedUrl = owned[key]?.url ?: return null
        retained[key] = ownedUrl
        trimRetained()
        return ownedUrl
    }

    override fun purgeSource(addonId: String) {
        synchronized(lock) {
            retained.keys.filter { it.addonId == addonId }.forEach(retained::remove)

            owned.keys.filter { it.addonId == addonId }.forEach(owned::remove)
        }
    }

    private fun readBounded(stream: java.io.InputStream, max: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        try {
            while (total < max) {
                val n = stream.read(buf, 0, minOf(buf.size, (max - total).toInt()))
                if (n < 0) return out.toByteArray()
                out.write(buf, 0, n)
                total += n
            }
            return null
        } catch (e: IOException) {
            return null
        }
    }

    private companion object {

        val DEFAULT_POSTER_LIMITS: NetworkLimits = NetworkLimits.DEFAULT.copy(
            maxResponseBytes = 2L * 1024 * 1024,
            maxDecompressedBytes = 4L * 1024 * 1024,
        )
        const val DEFAULT_MAX_RETAINED_ENTRIES = 512

        const val POSTER_SLOT = "poster"

        const val LANDSCAPE_SLOT = "background"
    }
}