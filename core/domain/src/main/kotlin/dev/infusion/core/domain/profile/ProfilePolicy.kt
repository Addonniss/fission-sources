package dev.infusion.core.domain.profile

object ProfilePolicy {

    const val MAX_PROFILES: Int = 6

    const val PRIMARY_PROFILE_INDEX: Int = 1

    const val MAX_PIN_ATTEMPTS: Int = 5

    const val PIN_LOCKOUT_MS: Long = 30_000L

    const val DEFAULT_PROFILE_ID: String = "local-default"

    const val DEFAULT_PROFILE_NAME: String = "Local profile"
}
