package dev.infusion.core.playback.api

import java.util.Locale

object AudioLanguageTag {

    const val UNDETERMINED: String = "und"

    fun canonicalize(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val normalized = trimmed.replace('_', '-').lowercase(Locale.ROOT)
        val primary = normalized.substringBefore('-')
        if (primary.isEmpty()) return null
        if (primary == UNDETERMINED) return null
        val base = baseOf(primary)
        if (base.isEmpty() || base == UNDETERMINED) return null
        return base + normalized.substring(primary.length)
    }

    fun base(tag: String?): String? = canonicalize(tag)?.substringBefore('-')

    fun exactEquals(a: String?, b: String?): Boolean {
        val ca = canonicalize(a) ?: return false
        val cb = canonicalize(b) ?: return false
        return ca == cb
    }

    fun baseEquals(a: String?, b: String?): Boolean {
        val ca = canonicalize(a) ?: return false
        val cb = canonicalize(b) ?: return false
        return ca.substringBefore('-') == cb.substringBefore('-')
    }

    val isoBaseLanguages: List<String> by lazy {
        Locale.getISOLanguages()
            .mapNotNull { code -> canonicalize(code)?.substringBefore('-') }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
    }

    private val LEGACY_AND_BIBLIOGRAPHIC: Map<String, String> = mapOf(

        "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "chi" to "zh",
        "cze" to "cs", "dut" to "nl", "fre" to "fr", "geo" to "ka", "ger" to "de",
        "gre" to "el", "ice" to "is", "mac" to "mk", "mao" to "mi", "may" to "ms",
        "per" to "fa", "rum" to "ro", "slo" to "sk", "tib" to "bo", "wel" to "cy",
        "scc" to "sr", "scr" to "hr", "mol" to "ro",

        "iw" to "he", "in" to "id", "ji" to "yi",
    )

    private val iso3ToIso1: Map<String, String> by lazy {
        buildMap {
            for (code in Locale.getISOLanguages()) {
                val iso3 = runCatching { Locale(code).getISO3Language() }.getOrNull()
                if (iso3.isNullOrBlank()) continue
                putIfAbsent(iso3.lowercase(Locale.ROOT), code.lowercase(Locale.ROOT))
            }
        }
    }

    private fun baseOf(primary: String): String = when (primary.length) {
        2 -> LEGACY_AND_BIBLIOGRAPHIC[primary] ?: primary
        3 -> iso3ToIso1[primary] ?: LEGACY_AND_BIBLIOGRAPHIC[primary] ?: primary
        else -> primary
    }
}
