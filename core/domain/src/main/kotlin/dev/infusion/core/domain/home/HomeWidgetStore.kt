package dev.infusion.core.domain.home

import kotlinx.coroutines.flow.Flow

interface HomeWidgetStore {

    fun read(profileId: String): HomeWidgetConfiguration

    fun write(profileId: String, records: List<HomeWidgetRecord>)

    fun clearProfile(profileId: String)

    fun clearAllProfiles()

    fun changes(): Flow<Unit>
}
