package dev.infusion.feature.configweb

object ConfigWebLimits {

    const val SESSION_TTL_MS: Long = 15 * 60 * 1000L

    const val TOKEN_BYTES: Int = 16

    const val TOKEN_HEX_CHARS: Int = TOKEN_BYTES * 2

    const val CODE_CHARS: Int = TOKEN_BYTES * 2

    const val MAX_JSON_DEPTH: Int = 8
    const val MAX_JSON_ARRAY_ENTRIES: Int = 512
    const val MAX_JSON_STRING_CHARS: Int = 4096
    const val MAX_JSON_OBJECT_FIELDS: Int = 256

    const val MAX_OPERATIONS_PER_DRAFT: Int = 512

    const val MAX_RETAINED_DRAFTS_PER_SESSION: Int = 8

    const val MAX_REASON_CHARS: Int = 200
    const val MAX_LABEL_CHARS: Int = 120

    const val MAX_TARGET_ID_CHARS: Int = 1024
}

data class ConfigWebBudgets(
    val windowMs: Long = 60_000L,

    val draftCreatePerWindow: Int = 20,

    val submitPerWindow: Int = 20,

    val statusReadPerWindow: Int = 120,

    val preAuthMalformedPerWindow: Int = 300,
) {
    init {
        require(windowMs > 0) { "windowMs must be positive" }
        require(draftCreatePerWindow > 0) { "draftCreatePerWindow must be positive" }
        require(submitPerWindow > 0) { "submitPerWindow must be positive" }
        require(statusReadPerWindow > 0) { "statusReadPerWindow must be positive" }
        require(preAuthMalformedPerWindow > 0) { "preAuthMalformedPerWindow must be positive" }
    }
}

data class ConfigWebConfig(
    val ttlMs: Long = ConfigWebLimits.SESSION_TTL_MS,
    val budgets: ConfigWebBudgets = ConfigWebBudgets(),
    val maxOperationsPerDraft: Int = ConfigWebLimits.MAX_OPERATIONS_PER_DRAFT,
    val maxRetainedDraftsPerSession: Int = ConfigWebLimits.MAX_RETAINED_DRAFTS_PER_SESSION,
) {
    init {
        require(ttlMs == ConfigWebLimits.SESSION_TTL_MS) {
            "the phone-management session TTL is a fixed 15-minute ABSOLUTE bound " +
                "(design correction 1); $ttlMs is not permitted"
        }
        require(maxOperationsPerDraft in 1..ConfigWebLimits.MAX_OPERATIONS_PER_DRAFT) {
            "maxOperationsPerDraft must be in 1..${ConfigWebLimits.MAX_OPERATIONS_PER_DRAFT}"
        }
        require(maxRetainedDraftsPerSession in 1..64) {
            "maxRetainedDraftsPerSession must be in 1..64"
        }
    }

    companion object {
        val DEFAULT: ConfigWebConfig = ConfigWebConfig()
    }
}
