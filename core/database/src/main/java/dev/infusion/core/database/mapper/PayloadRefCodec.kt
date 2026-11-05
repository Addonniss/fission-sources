package dev.infusion.core.database.mapper

import dev.infusion.core.model.PayloadRef
import org.json.JSONObject

object PayloadRefCodec {

    fun encode(ref: PayloadRef?): String? = ref?.let {
        JSONObject()
            .put("payloadType", it.payloadType)
            .put("payloadHash", it.payloadHash)
            .put("payloadVersion", it.payloadVersion)
            .toString()
    }

    fun decode(raw: String?): PayloadRef? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(raw)
            val type = o.optString("payloadType").takeIf { it.isNotEmpty() } ?: return null
            val hash = o.optString("payloadHash").takeIf { it.isNotEmpty() } ?: return null
            PayloadRef(
                payloadType = type,
                payloadHash = hash,
                payloadVersion = o.optInt("payloadVersion", 1),
            )
        }.getOrNull()
    }
}
