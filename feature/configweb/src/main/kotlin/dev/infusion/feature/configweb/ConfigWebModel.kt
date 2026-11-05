package dev.infusion.feature.configweb

import dev.infusion.core.pairing.PairingRedaction

enum class ConfigFamily(val wireName: String, val label: String) {
    GENERAL("general", "General"),
    PLAYBACK("playback", "Playback"),
    RATINGS("ratings", "Ratings"),
    ADDONS("addons", "Add-ons"),
    HOME("home", "Home"),
    COLLECTIONS("collections", "Collections"),
    ;

    companion object {
        fun fromWire(name: String): ConfigFamily? = entries.firstOrNull { it.wireName == name }
    }
}

enum class ConfigOperationKind(val wireName: String) {

    SET_ROW("set_row"),

    ADD("add"),

    REMOVE("remove"),

    ENABLE("enable"),

    DISABLE("disable"),

    REORDER("reorder"),

    IMPORT("import"),

    REFRESH("refresh"),
    ;

    val requiresValue: Boolean
        get() = this == SET_ROW || this == ADD || this == REORDER || this == IMPORT

    val requiresTargetId: Boolean
        get() = this == REMOVE || this == ENABLE || this == DISABLE || this == REFRESH

    companion object {
        fun fromWire(name: String): ConfigOperationKind? = entries.firstOrNull { it.wireName == name }
    }
}

sealed interface ConfigValue {

    data class Flag(val value: Boolean) : ConfigValue {
        override fun toString(): String = "Flag($value)"
    }

    data class Count(val value: Long) : ConfigValue {
        override fun toString(): String = "Count($value)"
    }

    data class Text(val value: String) : ConfigValue {
        override fun toString(): String = "Text([REDACTED])"
    }

    data class Order(val ids: List<String>) : ConfigValue {
        override fun toString(): String = "Order([REDACTED], size=${ids.size})"
    }
}

class ConfigOperation internal constructor(
    val family: ConfigFamily,
    val kind: ConfigOperationKind,
    val rowKey: String,
    val targetId: String? = null,
    val value: ConfigValue? = null,
) {

    fun redactionSecrets(): List<String> {
        val secrets = ArrayList<String>(4)
        targetId?.let { if (it.isNotEmpty()) secrets += it }
        when (val payload = value) {
            is ConfigValue.Text -> if (payload.value.isNotEmpty()) secrets += payload.value
            is ConfigValue.Order -> secrets += payload.ids.filter { it.isNotEmpty() }
            else -> Unit
        }
        return secrets
    }

    internal fun identityKey(): String = "${family.wireName}|${kind.wireName}|$rowKey|${targetId ?: ""}"

    override fun toString(): String =
        "ConfigOperation(family=${family.wireName}, kind=${kind.wireName}, rowKey=$rowKey)" +
            if (targetId == null && value == null) "" else ", payload=[REDACTED]"
}

data class ConfigValidationError(val field: String, val reason: String)

sealed interface ConfigApplyResult {

    data object Applied : ConfigApplyResult

    data class Failed(val reason: String) : ConfigApplyResult
}

interface ConfigOwnerPort {
    fun activeProfileId(): String
    fun revisionFingerprint(): String
    fun labelOf(operation: ConfigOperation): String
    fun validate(operation: ConfigOperation): ConfigValidationError?
    fun apply(operation: ConfigOperation): ConfigApplyResult
}

enum class NotAttemptedReason(val wireName: String) {

    STALE("stale"),

    SESSION_ENDED("session_ended"),

    CANCELLED("cancelled"),
    ;

    companion object {
        fun fromWire(name: String): NotAttemptedReason = entries.first { it.wireName == name }
    }
}

sealed interface OperationOutcome {

    data object Pending : OperationOutcome

    data object Applied : OperationOutcome

    data class Failed(val reason: String) : OperationOutcome

    data class NotAttempted(val reason: NotAttemptedReason) : OperationOutcome

    companion object {

        fun wireName(outcome: OperationOutcome): String = when (outcome) {
            Pending -> "pending"
            Applied -> "applied"
            is Failed -> "failed"
            is NotAttempted -> "not_attempted"
        }
    }
}

internal object ConfigWebText {

    private const val MARKER = "[REDACTED]"

    fun sanitize(text: String, secrets: List<String>, limit: Int): String {
        var value = PairingRedaction.redact(text, secrets)
        value = buildString {
            value.forEach { ch ->
                append(if (ch.code < 0x20 || ch.code == 0x7f) ' ' else ch)
            }
        }
        value = value.replace(Regex("\\s+"), " ").trim()
        if (value.length > limit) value = value.substring(0, limit) + "…"
        return value
    }

    fun originOnly(url: String): String = runCatching { PairingRedaction.url(url) }.getOrDefault(MARKER)
}
