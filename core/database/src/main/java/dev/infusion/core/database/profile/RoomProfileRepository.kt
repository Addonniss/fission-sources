package dev.infusion.core.database.profile

import android.os.SystemClock
import dev.infusion.core.database.Clock
import dev.infusion.core.database.dao.CheckpointDao
import dev.infusion.core.database.dao.OutboxDao
import dev.infusion.core.database.dao.ProfileDao
import dev.infusion.core.database.dao.SourceDao
import dev.infusion.core.database.dao.WatchEventDao
import dev.infusion.core.database.dao.WatchStateDao
import dev.infusion.core.database.entity.ProfileEntity
import dev.infusion.core.domain.profile.ActiveProfileStore
import dev.infusion.core.domain.profile.CreateProfileResult
import dev.infusion.core.domain.profile.DeleteProfileResult
import dev.infusion.core.domain.profile.PinVerificationResult
import dev.infusion.core.domain.profile.ProfileLedgerDisposition
import dev.infusion.core.domain.profile.ProfilePinHasher
import dev.infusion.core.domain.profile.ProfilePolicy
import dev.infusion.core.domain.profile.ProfileRepository
import dev.infusion.core.domain.profile.ProfileSourceCredentialRevoker
import dev.infusion.core.domain.profile.ProfileTrackingCredentialRevoker
import dev.infusion.core.domain.profile.ProfileDeletionOutcome
import dev.infusion.core.model.profile.Profile

class RoomProfileRepository(
    private val profileDao: ProfileDao,
    private val watchEventDao: WatchEventDao,
    private val watchStateDao: WatchStateDao,
    private val sourceDao: SourceDao,
    private val checkpointDao: CheckpointDao,
    private val outboxDao: OutboxDao,
    private val activeProfileStore: ActiveProfileStore,
    private val scope: MutableProfileScope,
    private val pinHasher: ProfilePinHasher,
    private val clock: Clock = Clock.SYSTEM,

    private val monotonicNowMs: () -> Long = { SystemClock.elapsedRealtime() },

    private val transaction: (block: () -> ProfileDeletionOutcome) -> ProfileDeletionOutcome = { it() },

    private val credentialRevoker: ProfileSourceCredentialRevoker = ProfileSourceCredentialRevoker {},

    private val trackingCredentialRevoker: ProfileTrackingCredentialRevoker = ProfileTrackingCredentialRevoker {},

    private val onProfileCreated: (profileId: String) -> Unit = {},
) : ProfileRepository {

    override fun ensurePrimaryProfile(): Profile {
        val existing = profileDao.getDefault() ?: profileDao.getPrimaryByIndex()
        if (existing != null) {
            if (!existing.isDefault && existing.profileIndex == 1) {

                profileDao.promoteToDefault(existing.profileId)
            }
            return existing.toDomain().copy(isDefault = true)
        }
        if (profileDao.count() > 0) {

            val earliest = profileDao.list().minByOrNull { it.createdAtMs }!!
            profileDao.promoteToDefault(earliest.profileId)
            return earliest.toDomain().copy(isDefault = true)
        }

        val primary = ProfileEntity(
            profileId = ProfilePolicy.DEFAULT_PROFILE_ID,
            name = ProfilePolicy.DEFAULT_PROFILE_NAME,
            isDefault = true,
            createdAtMs = clock.nowMs(),
            profileIndex = ProfilePolicy.PRIMARY_PROFILE_INDEX,
        )
        profileDao.upsert(primary)

        onProfileCreated(primary.profileId)
        return ProfileMapper.toDomain(primary)
    }

    override fun create(name: String, pin: String?): CreateProfileResult {
        require(name.isNotBlank()) { "profile name must not be blank" }
        if (profileDao.count() >= ProfilePolicy.MAX_PROFILES) return CreateProfileResult.CapExceeded
        val index = lowestFreeIndex() ?: return CreateProfileResult.CapExceeded
        val profile = ProfileEntity(
            profileId = "profile-" + java.util.UUID.randomUUID(),
            name = name.trim(),
            isDefault = false,
            createdAtMs = clock.nowMs(),
            profileIndex = index,
            pinHash = pin?.let { pinHasher.hash(it) },
        )
        profileDao.upsert(profile)

        onProfileCreated(profile.profileId)
        return CreateProfileResult.Ok(ProfileMapper.toDomain(profile))
    }

    override fun rename(profileId: String, newName: String): Profile {
        require(newName.isNotBlank()) { "profile name must not be blank" }
        val entity = requireProfile(profileId)
        profileDao.rename(profileId, newName.trim())
        return entity.copy(name = newName.trim()).toDomain()
    }

    override fun list(): List<Profile> = profileDao.list().map(ProfileMapper::toDomain)

    override fun primary(): Profile {
        val existing = profileDao.getDefault() ?: profileDao.getPrimaryByIndex()
        if (existing != null) return existing.toDomain()
        return ensurePrimaryProfile()
    }

    override fun switchActive(profileId: String): Profile {
        val entity = requireProfile(profileId)
        activeProfileStore.save(profileId)
        scope.switchTo(profileId)
        return entity.toDomain()
    }

    override suspend fun delete(profileId: String, disposition: ProfileLedgerDisposition): DeleteProfileResult {
        val entity = profileDao.getById(profileId) ?: return DeleteProfileResult.NotFound
        val primary = primary()
        if (entity.isDefault || entity.profileId == primary.profileId) {
            return DeleteProfileResult.PrimaryProtected
        }

        if (scope.activeProfileId == profileId) {
            activeProfileStore.save(null)
            scope.switchTo(primary.profileId)
        }

        val sourceIds = sourceDao.listIdsByOwner(profileId)
        try {
            credentialRevoker.revoke(sourceIds)
        } catch (_: Exception) {

        }

        try {
            trackingCredentialRevoker.revoke(profileId)
        } catch (_: Exception) {

        }
        val outcome = transaction {
            val checkpointConnectors = checkpointDao.listConnectorIds(profileId)

            val preservedWatchKeys = when (disposition) {
                ProfileLedgerDisposition.DELETE -> {
                    watchEventDao.deleteByProfile(profileId)
                    watchStateDao.deleteAllByProfile(profileId)
                    0
                }
                ProfileLedgerDisposition.PRESERVE -> {

                    watchStateDao.deleteCollidingStates(profileId, primary.profileId)
                    val movedEvents = watchEventDao.moveToProfile(profileId, primary.profileId)
                    watchStateDao.moveToProfile(profileId, primary.profileId)
                    movedEvents
                }
            }

            val deletedOutboxEntries = outboxDao.deleteByProfile(profileId)
            sourceDao.hardDeleteByOwner(profileId)
            checkpointDao.deleteByProfile(profileId)
            profileDao.delete(profileId)

            ProfileDeletionOutcome(
                deletedProfileId = profileId,
                disposition = disposition,
                preservedWatchKeys = preservedWatchKeys,
                deletedSourceIds = sourceIds,
                deletedCheckpointConnectors = checkpointConnectors,
                deletedOutboxEntries = deletedOutboxEntries,
            )
        }
        return DeleteProfileResult.Ok(outcome)
    }

    override fun setPin(profileId: String, pin: String) {
        requireProfile(profileId)
        profileDao.setPin(profileId, pinHasher.hash(pin))
    }

    override fun clearPin(profileId: String) {
        requireProfile(profileId)
        profileDao.clearPin(profileId)
    }

    override fun verifyPin(profileId: String, pin: String): PinVerificationResult {
        val entity = requireProfile(profileId)
        val hash = entity.pinHash
        if (hash == null) {

            return PinVerificationResult.Verified
        }
        val lockedUntilMs = entity.pinLockedUntilMs
        if (lockedUntilMs != null) {
            val nowMs = monotonicNowMs()

            val effectiveLockUntilMs = minOf(lockedUntilMs, nowMs + ProfilePolicy.PIN_LOCKOUT_MS)
            if (effectiveLockUntilMs != lockedUntilMs) {

                profileDao.clampPinLockout(profileId, effectiveLockUntilMs)
            }
            if (nowMs < effectiveLockUntilMs) {
                return PinVerificationResult.Locked(effectiveLockUntilMs)
            }
        }
        if (pinHasher.verify(pin, hash)) {
            profileDao.resetFailedPinAttempts(profileId)
            return PinVerificationResult.Verified
        }
        val attempts = entity.failedPinAttempts + 1
        if (attempts >= ProfilePolicy.MAX_PIN_ATTEMPTS) {
            val lockUntil = monotonicNowMs() + ProfilePolicy.PIN_LOCKOUT_MS
            profileDao.recordFailedPinAttempt(profileId, attempts, lockUntil)
            return PinVerificationResult.Locked(lockUntil)
        }
        profileDao.recordFailedPinAttempt(profileId, attempts, null)
        return PinVerificationResult.WrongPin(remainingAttempts = ProfilePolicy.MAX_PIN_ATTEMPTS - attempts)
    }

    private fun lowestFreeIndex(): Int? {
        val used = profileDao.list().map { it.profileIndex }.toSet()
        for (index in 1..ProfilePolicy.MAX_PROFILES) {
            if (index !in used) return index
        }
        return null
    }

    private fun requireProfile(profileId: String): ProfileEntity =
        profileDao.getById(profileId)
            ?: throw IllegalArgumentException("unknown profile: $profileId")
}
