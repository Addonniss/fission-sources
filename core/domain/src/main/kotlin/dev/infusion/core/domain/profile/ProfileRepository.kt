package dev.infusion.core.domain.profile

import dev.infusion.core.model.profile.Profile

interface ProfileRepository {

    fun ensurePrimaryProfile(): Profile

    fun create(name: String, pin: String? = null): CreateProfileResult

    fun rename(profileId: String, newName: String): Profile

    fun list(): List<Profile>

    fun primary(): Profile

    fun switchActive(profileId: String): Profile

    suspend fun delete(profileId: String, disposition: ProfileLedgerDisposition): DeleteProfileResult

    fun setPin(profileId: String, pin: String)

    fun clearPin(profileId: String)

    fun verifyPin(profileId: String, pin: String): PinVerificationResult
}

enum class ProfileLedgerDisposition {

    DELETE,

    PRESERVE,
}

sealed interface CreateProfileResult {
    data class Ok(val profile: Profile) : CreateProfileResult

    data object CapExceeded : CreateProfileResult
}

sealed interface DeleteProfileResult {

    data class Ok(val outcome: ProfileDeletionOutcome) : DeleteProfileResult

    data object PrimaryProtected : DeleteProfileResult

    data object NotFound : DeleteProfileResult
}

data class ProfileDeletionOutcome(
    val deletedProfileId: String,
    val disposition: ProfileLedgerDisposition,

    val preservedWatchKeys: Int,

    val deletedSourceIds: List<String>,
    val deletedCheckpointConnectors: List<String>,
    val deletedOutboxEntries: Int,
)

sealed interface PinVerificationResult {
    data object Verified : PinVerificationResult

    data class WrongPin(val remainingAttempts: Int) : PinVerificationResult

    data class Locked(val lockedUntilMs: Long) : PinVerificationResult
}
