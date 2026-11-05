package dev.infusion.core.database.mapper

import dev.infusion.core.model.CandidateFacts
import dev.infusion.core.model.FactConfidence
import org.json.JSONArray
import org.json.JSONObject

object CandidateFactsCodec {

    fun encode(facts: CandidateFacts): String = JSONObject()
        .putOpt("container", facts.container)
        .putOpt("videoCodec", facts.videoCodec)
        .putOpt("videoProfile", facts.videoProfile)
        .putOpt("resolution", facts.resolution)
        .putOpt("dynamicRange", facts.dynamicRange)
        .put("frameRate", facts.frameRate)
        .putOpt("audioCodec", facts.audioCodec)
        .put("audioChannels", facts.audioChannels)
        .put("subtitleFormats", JSONArray(facts.subtitleFormats.toList()))
        .putOpt("language", facts.language)
        .put("sizeBytes", facts.sizeBytes)
        .put("bitrateBps", facts.bitrateBps)
        .put("confidence", facts.confidence.name)
        .toString()

    fun decode(raw: String): CandidateFacts {
        val o = JSONObject(raw)
        val formats = JSONArray(o.optString("subtitleFormats", "[]").let {
            if (it.startsWith("[")) it else JSONArray().put(it).toString()
        })
        val subtitleFormats = buildSet {
            for (i in 0 until formats.length()) add(formats.getString(i))
        }
        return CandidateFacts(
            container = o.optString("container").takeIf { it.isNotEmpty() },
            videoCodec = o.optString("videoCodec").takeIf { it.isNotEmpty() },
            videoProfile = o.optString("videoProfile").takeIf { it.isNotEmpty() },
            resolution = o.optString("resolution").takeIf { it.isNotEmpty() },
            dynamicRange = o.optString("dynamicRange").takeIf { it.isNotEmpty() },
            frameRate = if (o.isNull("frameRate")) null else o.optDouble("frameRate", -1.0).let { if (it < 0) null else it },
            audioCodec = o.optString("audioCodec").takeIf { it.isNotEmpty() },
            audioChannels = if (o.isNull("audioChannels")) null else o.optInt("audioChannels", -1).let { if (it < 0) null else it },
            subtitleFormats = subtitleFormats,
            language = o.optString("language").takeIf { it.isNotEmpty() },
            sizeBytes = if (o.isNull("sizeBytes")) null else o.optLong("sizeBytes", -1L).let { if (it < 0) null else it },
            bitrateBps = if (o.isNull("bitrateBps")) null else o.optLong("bitrateBps", -1L).let { if (it < 0) null else it },
            confidence = runCatching { FactConfidence.valueOf(o.getString("confidence")) }.getOrDefault(FactConfidence.UNKNOWN),
        )
    }
}
