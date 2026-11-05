package dev.infusion.core.database.mapper

import dev.infusion.core.model.CandidateFamilyId
import dev.infusion.core.model.CandidateId
import dev.infusion.core.model.CandidateFacts
import dev.infusion.core.model.CapabilityAssessment
import dev.infusion.core.model.CapabilityState
import dev.infusion.core.model.EditionId
import dev.infusion.core.model.EmbeddedSubtitle
import dev.infusion.core.model.ExpectedMode
import dev.infusion.core.model.NativeItemId
import dev.infusion.core.model.PlayableCandidate
import dev.infusion.core.model.Reachability
import dev.infusion.core.model.SourceId
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TransportDescriptor
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

object CandidateSnapshotCodec {

    fun encode(candidate: PlayableCandidate): String = JSONObject()
        .put("candidateId", candidate.candidateId.value.toString())
        .put("titleId", candidate.titleId.value.toString())
        .putOpt("editionId", candidate.editionId?.value?.toString())
        .put("sourceId", candidate.sourceId.value.toString())
        .put("nativeSourceId", candidate.nativeItemId.sourceId.value.toString())
        .put("nativeId", candidate.nativeItemId.nativeId)
        .put("familyId", candidate.familyId.digest)
        .put("providerName", candidate.providerName)
        .put("originalLabel", candidate.originalLabel)
        .put("facts", JSONObject(CandidateFactsCodec.encode(candidate.facts)))
        .put("expectedMode", candidate.expectedMode.name)
        .putOpt("capability", candidate.capability?.let { cap ->
            JSONObject()
                .put("state", cap.state.name)
                .put("reasons", JSONArray(cap.reasons))
                .put("assessedAt", cap.assessedAt.toEpochMilli())
        })
        .putOpt("expiresAt", candidate.expiresAt?.toEpochMilli())
        .put("reachability", candidate.reachability.name)
        .putOpt("rankExplanation", candidate.rankExplanation)
        .putOpt("createdAt", candidate.createdAt.toEpochMilli())

        .putOpt("durationMs", candidate.durationMs)
        .put(
            "embeddedSubtitles",
            JSONArray(candidate.embeddedSubtitles.map { sub ->
                JSONObject()
                    .put("id", sub.id)
                    .put("url", sub.url)
                    .putOpt("language", sub.language)
            }),
        )
        .toString()

    fun decode(raw: String, transport: TransportDescriptor): PlayableCandidate {
        val o = JSONObject(raw)
        val facts = CandidateFactsCodec.decode(o.getJSONObject("facts").toString())
        val capability = if (o.isNull("capability")) null else {
            val c = o.getJSONObject("capability")
            CapabilityAssessment(
                state = CapabilityState.valueOf(c.getString("state")),
                reasons = buildList {
                    val reasons = c.getJSONArray("reasons")
                    for (i in 0 until reasons.length()) add(reasons.getString(i))
                },
                assessedAt = Instant.ofEpochMilli(c.getLong("assessedAt")),
            )
        }
        val nativeSource = o.getString("nativeSourceId")
        val embeddedSubtitles = buildList {
            val subs = o.optJSONArray("embeddedSubtitles") ?: JSONArray()
            for (i in 0 until subs.length()) {
                val s = subs.optJSONObject(i) ?: continue
                runCatching {
                    EmbeddedSubtitle(
                        id = s.optString("id"),
                        url = s.optString("url"),
                        language = s.optString("language").takeIf { it.isNotEmpty() },
                    )
                }.onSuccess { add(it) }
            }
        }
        return PlayableCandidate(
            candidateId = CandidateId.fromString(o.getString("candidateId")),
            titleId = TitleId.fromString(o.getString("titleId")),
            editionId = o.optString("editionId").takeIf { it.isNotEmpty() }?.let { EditionId.fromString(it) },
            sourceId = SourceId.fromString(o.getString("sourceId")),
            nativeItemId = NativeItemId(SourceId.fromString(nativeSource), o.getString("nativeId")),
            familyId = CandidateFamilyId(o.getString("familyId")),
            providerName = o.getString("providerName"),
            originalLabel = o.getString("originalLabel"),
            transport = transport,
            facts = facts,
            expectedMode = ExpectedMode.valueOf(o.getString("expectedMode")),
            capability = capability,
            expiresAt = if (o.isNull("expiresAt")) null else Instant.ofEpochMilli(o.getLong("expiresAt")),
            reachability = Reachability.valueOf(o.getString("reachability")),
            rankExplanation = o.optString("rankExplanation").takeIf { it.isNotEmpty() },
            createdAt = Instant.ofEpochMilli(o.getLong("createdAt")),
            embeddedSubtitles = embeddedSubtitles,
            durationMs = if (o.isNull("durationMs")) null else o.optLong("durationMs"),
        )
    }
}
