package dev.infusion.feature.vertical.home

import dev.infusion.core.domain.home.HomeWidgetLimits
import dev.infusion.core.domain.home.HomeWidgetRecord
import dev.infusion.core.domain.home.HomeWidgetSourceRef
import dev.infusion.core.domain.home.HomeWidgetStore
import dev.infusion.core.domain.home.addressesSameSource
import dev.infusion.core.domain.publist.PublicListProvider

fun newHomeWidgetId(): String = "w" + java.util.UUID.randomUUID().toString().replace("-", "")

object PublicListWidgetCreation {

    fun add(
        store: HomeWidgetStore,
        profileId: String,
        destinationId: String,
        providerId: String,
        listId: String,
        owner: String?,
        displayTitle: String?,
        newWidgetId: () -> String,
        destinationAvailable: (String) -> Boolean,
        providerAvailable: (PublicListProvider) -> Boolean,
    ): WidgetAddOutcome {
        if (!destinationAvailable(destinationId)) return WidgetAddOutcome.UNKNOWN_DESTINATION
        val provider = PublicListProvider.fromId(providerId) ?: return WidgetAddOutcome.UNKNOWN_PROVIDER
        if (!providerAvailable(provider)) return WidgetAddOutcome.UNKNOWN_PROVIDER
        if (listId.isBlank()) return WidgetAddOutcome.UNKNOWN_PUBLIC_LIST
        val existing = store.read(profileId)
        if (existing.records.size >= HomeWidgetLimits.MAX_WIDGETS_PER_PROFILE) {
            return WidgetAddOutcome.LIMIT_REACHED
        }

        val source = HomeWidgetSourceRef.PublicList(
            providerId = provider.id,
            listId = listId,
            owner = owner?.takeIf { it.isNotBlank() },
        )
        val duplicate = existing.records.any { record ->
            record.destinationId == destinationId &&
                record.source.addressesSameSource(source)
        }
        if (duplicate) return WidgetAddOutcome.DUPLICATE
        val record = HomeWidgetRecord(
            widgetId = newWidgetId(),
            destinationId = destinationId,
            source = source,

            displayTitle = HomeWidgetRecord.storableTitle(displayTitle),
        )

        store.write(profileId, existing.records + record)
        return WidgetAddOutcome.ADDED
    }
}
