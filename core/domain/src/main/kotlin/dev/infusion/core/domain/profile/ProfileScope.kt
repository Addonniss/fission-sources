package dev.infusion.core.domain.profile

import kotlinx.coroutines.flow.Flow

interface ProfileScope {

    val activeProfileId: String

    val activeProfileFlow: Flow<String>
}
