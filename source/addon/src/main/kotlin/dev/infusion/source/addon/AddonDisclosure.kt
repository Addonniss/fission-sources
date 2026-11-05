package dev.infusion.source.addon

import org.json.JSONObject

data class AddonDisclosure(
    val endpoint: String,
    val requestedResourceTypes: List<String>,
    val catalogVisibility: List<AddonManifestSnapshot.CatalogVisibility>,
    val networkDisclosure: String,
    val credentialUse: String,
    val lanGranted: Boolean,
    val disclosedAtMs: Long,
) {
    companion object {
        const val CREDENTIAL_USE_NONE = "none"
    }
}

object AddonDisclosureCodec {

    const val MAX_DISCLOSURE_BYTES = 16 * 1024

    fun encode(disclosure: AddonDisclosure): String {
        val json = JSONObject()
            .put("endpoint", disclosure.endpoint)
            .put("requestedResourceTypes", org.json.JSONArray(disclosure.requestedResourceTypes))
            .put(
                "catalogVisibility",
                org.json.JSONArray().apply {
                    for (c in disclosure.catalogVisibility) {
                        put(
                            JSONObject()
                                .put("type", c.type.wire)
                                .put("id", c.id)
                                .put("name", c.name),
                        )
                    }
                },
            )
            .put("networkDisclosure", disclosure.networkDisclosure)
            .put("credentialUse", disclosure.credentialUse)
            .put("lanGranted", disclosure.lanGranted)
            .put("disclosedAtMs", disclosure.disclosedAtMs)
        val raw = json.toString()
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_DISCLOSURE_BYTES) {
            "disclosure exceeds the $MAX_DISCLOSURE_BYTES byte cap"
        }
        return raw
    }

    fun decode(raw: String): AddonDisclosure {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_DISCLOSURE_BYTES) { "disclosure exceeds the size cap" }
        val o = JSONObject(raw)
        val catalogVisibility = o.getJSONArray("catalogVisibility").let { arr ->
            (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                AddonManifestSnapshot.CatalogVisibility(
                    type = dev.infusion.source.stremio.policy.StremioContentType.parse(c.getString("type"))
                        ?: error("unknown type"),
                    id = c.getString("id"),
                    name = c.getString("name"),
                )
            }
        }
        val requested = o.getJSONArray("requestedResourceTypes").let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }
        return AddonDisclosure(
            endpoint = o.getString("endpoint"),
            requestedResourceTypes = requested,
            catalogVisibility = catalogVisibility,
            networkDisclosure = o.getString("networkDisclosure"),
            credentialUse = o.getString("credentialUse"),
            lanGranted = o.getBoolean("lanGranted"),
            disclosedAtMs = o.getLong("disclosedAtMs"),
        )
    }
}
