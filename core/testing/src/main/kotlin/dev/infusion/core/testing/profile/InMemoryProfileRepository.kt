package dev.infusion.core.testing.profile

import dev.infusion.core.domain.profile.CreateProfileResult
import dev.infusion.core.domain.profile.DeleteProfileResult
import dev.infusion.core.domain.profile.PinVerificationResult
import dev.infusion.core.domain.profile.ProfileLedgerDisposition
import dev.infusion.core.domain.profile.ProfilePinHasher
import dev.infusion.core.domain.profile.ProfilePolicy
import dev.infusion.core.domain.profile.ProfileRepository
import dev.infusion.core.domain.profile.ProfileDeletionOutcome
import dev.infusion.core.model.profile.Profile
import java.util.UUID

class InMemoryProfileRepository(
    val activeProfileStore: InMemoryActiveProfileStore = InMemoryActiveProfileStore(),
    val scope: FakeProfileScope = FakeProfileScope(ProfilePolicy.DEFAULT_PROFILE_ID),
    private val pinHasher: ProfilePinHasher = FakePinHasher,
    private val monotonicNowMs: () -> Long = { 0L },
) : ProfileRepository {

    private class Row(
        var profileId: String,
        var name: String,
        var isDefault: Boolean,
        var profileIndex: Int,
        var pinHash: String?,
        var failedPinAttempts: Int,
        var pinLockedUntilMs: Long?,
        val createdAtMs: Long,
        var usesPrimarySources: Boolean,
    )

    private val rows = linkedMapOf<String, Row>()

    private val watchKeysByProfile = mutableMapOf<String, MutableSet<String>>()

    private val sourcesByProfile = mutableMapOf<String, MutableSet<String>>()

    private val checkpointsByProfile = mutableMapOf<String, MutableSet<String>>()

    private val outboxEntriesByProfile = mutableMapOf<String, Int>()

    fun seedWatchKey(profileId: String, key: String) {
        watchKeysByProfile.getOrPut(profileId) { mutableSetOf() }.add(key)
    }

    fun seedSource(profileId: String, sourceId: String) {
        sourcesByProfile.getOrPut(profileId) { mutableSetOf() }.add(sourceId)
    }

    fun seedCheckpoint(profileId: String, connectorId: String) {
        checkpointsByProfile.getOrPut(profileId) { mutableSetOf() }.add(connectorId)
    }

    fun seedOutboxEntry(profileId: String) {
        outboxEntriesByProfile[profileId] = (outboxEntriesByProfile[profileId] ?: 0) + 1
    }

    fun watchKeys(profileId: String): Set<String> = watchKeysByProfile[profileId].orEmpty()

    fun sources(profileId: String): Set<String> = sourcesByProfile[profileId].orEmpty()

    fun persistedActiveId(): String? = activeProfileStore.load()

    fun scopePointsAt(profileId: String): Boolean = scope.activeProfileId == profileId

    override fun ensurePrimaryProfile(): Profile {
        val existing = rows.values.firstOrNull { it.isDefault }
            ?: rows.values.firstOrNull { it.profileIndex == ProfilePolicy.PRIMARY_PROFILE_INDEX }
        if (existing != null) {
            if (!existing.isDefault && existing.profileIndex == ProfilePolicy.PRIMARY_PROFILE_INDEX) {
                existing.isDefault = true
            }
            return existing.toDomain()
        }
        if (rows.isNotEmpty()) {
            val earliest = rows.values.minByOrNull { it.createdAtMs }!!
            earliest.isDefault = true
            return earliest.toDomain()
        }
        val primary = Row(
            profileId = ProfilePolicy.DEFAULT_PROFILE_ID,
            name = ProfilePolicy.DEFAULT_PROFILE_NAME,
            isDefault = true,
            profileIndex = ProfilePolicy.PRIMARY_PROFILE_INDEX,
            pinHash = null,
            failedPinAttempts = 0,
            pinLockedUntilMs = null,
            createdAtMs = clockNow(),
            usesPrimarySources = true,
        )
        rows[primary.profileId] = primary
        return primary.toDomain()
    }

    override fun create(name: String, pin: String?): CreateProfileResult {
        require(name.isNotBlank()) { "profile name must not be blank" }
        if (rows.size >= ProfilePolicy.MAX_PROFILES) return CreateProfileResult.CapExceeded
        val index = lowestFreeIndex() ?: return CreateProfileResult.CapExceeded
        val profile = Row(
            profileId = "profile-" + UUID.randomUUID(),
            name = name.trim(),
            isDefault = false,
            profileIndex = index,
            pinHash = pin?.let { pinHasher.hash(it) },
            failedPinAttempts = 0,
            pinLockedUntilMs = null,
            createdAtMs = clockNow(),
            usesPrimarySources = true,
        )
        rows[profile.profileId] = profile
        return CreateProfileResult.Ok(profile.toDomain())
    }

    override fun rename(profileId: String, newName: String): Profile {
        require(newName.isNotBlank()) { "profile name must not be blank" }
        val row = requireRow(profileId)
        row.name = newName.trim()
        return row.toDomain()
    }

    override fun list(): List<Profile> = rows.values.sortedBy { it.profileIndex }.map { it.toDomain() }

    override fun primary(): Profile {
        val existing = rows.values.firstOrNull { it.isDefault }
            ?: rows.values.firstOrNull { it.profileIndex == ProfilePolicy.PRIMARY_PROFILE_INDEX }
        if (existing != null) return existing.toDomain()
        return ensurePrimaryProfile()
    }

    override fun switchActive(profileId: String): Profile {
        val row = requireRow(profileId)
        activeProfileStore.save(profileId)
        scope.switchTo(profileId)
        return row.toDomain()
    }

    override suspend fun delete(profileId: String, disposition: ProfileLedgerDisposition): DeleteProfileResult {
        val row = rows[profileId] ?: return DeleteProfileResult.NotFound
        val primary = primary()
        if (row.isDefault || row.profileId == primary.profileId) {
            return DeleteProfileResult.PrimaryProtected
        }

        if (scope.activeProfileId == profileId) {
            activeProfileStore.save(null)
            scope.switchTo(primary.profileId)
        }
        val sourceIds = sourcesByProfile.remove(profileId).orEmpty().toList()
        val checkpointConnectors = checkpointsByProfile.remove(profileId).orEmpty().toList()
        val deletedOutboxEntries = outboxEntriesByProfile.remove(profileId) ?: 0

        val preservedWatchKeys = when (disposition) {
            ProfileLedgerDisposition.DELETE -> {
                watchKeysByProfile.remove(profileId)
                0
            }
            ProfileLedgerDisposition.PRESERVE -> {

                val deletedKeys = watchKeysByProfile.remove(profileId).orEmpty()
                val primaryKeys = watchKeysByProfile.getOrPut(primary.profileId) { mutableSetOf() }
                var moved = 0
                deletedKeys.forEach { key ->
                    if (primaryKeys.add(key)) moved++
                }
                moved
            }
        }

        rows.remove(profileId)
        return DeleteProfileResult.Ok(
            ProfileDeletionOutcome(
                deletedProfileId = profileId,
                disposition = disposition,
                preservedWatchKeys = preservedWatchKeys,
                deletedSourceIds = sourceIds,
                deletedCheckpointConnectors = checkpointConnectors,
                deletedOutboxEntries = deletedOutboxEntries,
            ),
        )
    }

    override fun setPin(profileId: String, pin: String) {
        requireRow(profileId)
        rows[profileId]!!.pinHash = pinHasher.hash(pin)
        rows[profileId]!!.failedPinAttempts = 0
        rows[profileId]!!.pinLockedUntilMs = null
    }

    override fun clearPin(profileId: String) {
        requireRow(profileId)
        rows[profileId]!!.pinHash = null
        rows[profileId]!!.failedPinAttempts = 0
        rows[profileId]!!.pinLockedUntilMs = null
    }

    override fun verifyPin(profileId: String, pin: String): PinVerificationResult {
        val row = requireRow(profileId)
        val hash = row.pinHash
        if (hash == null) {

            return PinVerificationResult.Verified
        }
        val lockedUntilMs = row.pinLockedUntilMs
        if (lockedUntilMs != null) {
            val nowMs = monotonicNowMs()

            val effective = minOf(lockedUntilMs, nowMs + ProfilePolicy.PIN_LOCKOUT_MS)
            if (effective != lockedUntilMs) {
                row.pinLockedUntilMs = effective
            }
            if (nowMs < effective) {
                return PinVerificationResult.Locked(effective)
            }
        }
        if (pinHasher.verify(pin, hash)) {
            row.failedPinAttempts = 0
            return PinVerificationResult.Verified
        }
        val attempts = row.failedPinAttempts + 1
        if (attempts >= ProfilePolicy.MAX_PIN_ATTEMPTS) {
            val lockUntil = monotonicNowMs() + ProfilePolicy.PIN_LOCKOUT_MS
            row.failedPinAttempts = attempts
            row.pinLockedUntilMs = lockUntil
            return PinVerificationResult.Locked(lockUntil)
        }
        row.failedPinAttempts = attempts
        return PinVerificationResult.WrongPin(remainingAttempts = ProfilePolicy.MAX_PIN_ATTEMPTS - attempts)
    }

    private fun clockNow(): Long = monotonicNowMs()

    private fun lowestFreeIndex(): Int? {
        val used = rows.values.map { it.profileIndex }.toSet()
        for (index in 1..ProfilePolicy.MAX_PROFILES) {
            if (index !in used) return index
        }
        return null
    }

    private fun requireRow(profileId: String): Row =
        rows[profileId] ?: throw IllegalArgumentException("unknown profile: $profileId")

    private fun Row.toDomain(): Profile = Profile(
        profileId = profileId,
        name = name,
        isDefault = isDefault,
        profileIndex = profileIndex,
        usesPrimarySources = usesPrimarySources,

        pinProtected = pinHash != null,
        createdAtMs = createdAtMs,
    )

    private object FakePinHasher : ProfilePinHasher {
        override fun hash(pin: String): String {
            require(pin.length in 4..64) { "PIN must be 4..64 characters" }
            return "test-hash:${pin.hashCode()}"
        }

        override fun verify(pin: String, encodedHash: String): Boolean =
            encodedHash == "test-hash:${pin.hashCode()}"
    }
}
