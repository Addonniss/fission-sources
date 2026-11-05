package dev.infusion.core.playback.api

enum class SourceAutoplayPolicy(val storageKey: String) {
    OFF("off"),
    FIRST_RESPONDING_ADDON("first_responding_addon"),
    FIRST_COMBINED_ADDON_RESULT("first_combined_addon_result"),
    ;

    val enabled: Boolean get() = this != OFF

    companion object {

        val DEFAULT: SourceAutoplayPolicy = OFF

        fun resolve(stored: String?): SourceAutoplayPolicy {
            val key = stored?.trim()?.lowercase()
            if (key.isNullOrEmpty()) return DEFAULT
            return entries.firstOrNull { it.storageKey == key } ?: DEFAULT
        }
    }
}

data class SourceAutoplayPreferences(
    val policy: SourceAutoplayPolicy = SourceAutoplayPolicy.DEFAULT,
    val preferJellyfin: Boolean = true,
) {
    fun withPolicy(value: SourceAutoplayPolicy): SourceAutoplayPreferences = copy(policy = value)

    fun withPreferJellyfin(value: Boolean): SourceAutoplayPreferences = copy(preferJellyfin = value)

    companion object {

        val DEFAULT: SourceAutoplayPreferences = SourceAutoplayPreferences()
    }
}
