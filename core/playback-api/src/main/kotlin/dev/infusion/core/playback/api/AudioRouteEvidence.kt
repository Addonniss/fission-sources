package dev.infusion.core.playback.api

object AudioRouteEvidence {

    fun of(
        mimeType: String?,
        codecs: String?,
        channelCount: Int?,
        sampleRate: Int?,
        language: String?,
        engineSupported: Boolean?,
        platformPassthrough: Boolean?,
    ): String {
        val attributes = buildList {
            mimeType?.takeIf { it.isNotBlank() }?.let { add(it) }
            codecs?.takeIf { it.isNotBlank() }?.let { add("($it)") }
            channelCount?.takeIf { it > 0 }?.let { add("${it}ch") }
            sampleRate?.takeIf { it > 0 }?.let { add("${it}Hz") }
            language?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString(" ")
        val verdicts = buildList {
            engineSupported?.let { add("engineSupported=$it") }
            platformPassthrough?.let { add("platformPassthrough=$it") }
        }.joinToString(" ")
        val head = listOf(attributes, verdicts).filter { it.isNotEmpty() }.joinToString(" ")
        return if (head.isEmpty()) NO_ASSESSMENT else "$head $NO_ASSESSMENT"
    }

    private const val NO_ASSESSMENT = "observed; no assessment"
}
