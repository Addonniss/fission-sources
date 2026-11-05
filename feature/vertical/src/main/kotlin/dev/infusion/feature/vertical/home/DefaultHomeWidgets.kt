package dev.infusion.feature.vertical.home

import dev.infusion.core.domain.home.HomeWidgetStore

data class DefaultHomeWidget(
    val providerId: String,
    val owner: String?,
    val listId: String,
    val displayTitle: String,
)

object DefaultHomeWidgets {

    val LATEST_STREAMING_MOVIES: DefaultHomeWidget = DefaultHomeWidget(
        providerId = "mdblist",
        owner = "snoak",
        listId = "snoak/latest-movies-digital-release",
        displayTitle = "Latest Streaming Movies",
    )

    val LATEST_SHOWS: DefaultHomeWidget = DefaultHomeWidget(
        providerId = "mdblist",
        owner = "snoak",
        listId = "snoak/latest-tv-shows",
        displayTitle = "Latest Shows",
    )

    val FOR_NEW_PROFILE: List<DefaultHomeWidget> = listOf(
        LATEST_STREAMING_MOVIES,
        LATEST_SHOWS,
    )
}

enum class DefaultHomeWidgetSeedOutcome {

    SEEDED,

    ALREADY_CONFIGURED,

    REFUSED,
}

class DefaultHomeWidgetSeeder(
    private val store: HomeWidgetStore,
    private val newWidgetId: () -> String = { newHomeWidgetId() },
    private val destinationId: String = WidgetDestinations.HOME.id,
    private val defaults: List<DefaultHomeWidget> = DefaultHomeWidgets.FOR_NEW_PROFILE,
) {

    init {
        require(defaults.isNotEmpty()) { "the default Home widget set must not be empty" }
    }

    fun seedNewProfile(profileId: String): DefaultHomeWidgetSeedOutcome {

        if (store.read(profileId).configured) return DefaultHomeWidgetSeedOutcome.ALREADY_CONFIGURED
        var created = 0
        defaults.forEach { widget ->
            val outcome = PublicListWidgetCreation.add(
                store = store,
                profileId = profileId,
                destinationId = destinationId,
                providerId = widget.providerId,
                listId = widget.listId,
                owner = widget.owner,
                displayTitle = widget.displayTitle,
                newWidgetId = newWidgetId,

                destinationAvailable = { id -> id == destinationId },

                providerAvailable = { true },
            )
            if (outcome == WidgetAddOutcome.ADDED) created += 1
        }
        return if (created == defaults.size) {
            DefaultHomeWidgetSeedOutcome.SEEDED
        } else {
            DefaultHomeWidgetSeedOutcome.REFUSED
        }
    }
}
