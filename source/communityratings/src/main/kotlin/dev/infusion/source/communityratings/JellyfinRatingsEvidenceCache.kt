package dev.infusion.source.communityratings

import dev.infusion.core.domain.metadata.MetadataCacheStore
import dev.infusion.core.domain.ratings.JellyfinRatingsEvidence
import dev.infusion.core.domain.ratings.JellyfinRatingsEvidenceStore
import org.json.JSONObject

class JellyfinRatingsEvidenceCache(
    private val store: MetadataCacheStore,
) : JellyfinRatingsEvidenceStore {

    override suspend fun record(identityKey: String, evidence: JellyfinRatingsEvidence) {
        if (identityKey.isBlank()) return
        if (evidence.isEmpty) {

            runCatching { store.remove(NAMESPACE, identityKey) }
            return
        }
        val payload = JSONObject()
            .put("community", evidence.community ?: JSONObject.NULL)
            .put("critics", evidence.critics ?: JSONObject.NULL)
            .toString()
        runCatching { store.store(NAMESPACE, identityKey, payload, now()) }
    }

    override suspend fun load(identityKey: String): JellyfinRatingsEvidence? {
        if (identityKey.isBlank()) return null
        val payload = runCatching { store.load(NAMESPACE, identityKey) }.getOrNull() ?: return null
        val document = runCatching { JSONObject(payload.payload) }.getOrNull() ?: return null
        val evidence = JellyfinRatingsEvidence(
            community = document.optDoubleOrNull("community"),
            critics = document.optDoubleOrNull("critics"),
        )
        return evidence.takeIf { !it.isEmpty }
    }

    private fun now(): Long = System.currentTimeMillis()

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val value = optDouble(key, Double.NaN)
        return value.takeIf { it.isFinite() }
    }

    companion object {

        const val NAMESPACE: String = "jellyfin.ratings.v1"
    }
}
