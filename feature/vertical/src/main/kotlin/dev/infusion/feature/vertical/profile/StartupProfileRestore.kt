package dev.infusion.feature.vertical.profile

import dev.infusion.core.domain.profile.ActiveProfileStore
import dev.infusion.core.domain.profile.ProfileRepository
import dev.infusion.core.model.profile.Profile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ProfileRestoreResult(

    val activeProfile: Profile,

    val recoveredPersisted: Boolean,
)

class StartupProfileRestore(
    private val repository: ProfileRepository,
    private val activeProfileStore: ActiveProfileStore,
    private val retargetScope: (profileId: String) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun restore(): ProfileRestoreResult = withContext(ioDispatcher) {
        repository.ensurePrimaryProfile()
        val persisted = activeProfileStore.load()
        val recovered = if (persisted != null && repository.list().any { it.profileId == persisted }) {
            persisted
        } else {
            repository.primary().profileId
        }

        retargetScope(recovered)
        val profile = repository.list().first { it.profileId == recovered }
        ProfileRestoreResult(
            activeProfile = profile,
            recoveredPersisted = persisted != null && persisted == recovered,
        )
    }
}
