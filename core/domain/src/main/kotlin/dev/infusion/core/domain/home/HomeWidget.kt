package dev.infusion.core.domain.home

object HomeWidgetLimits {

    const val MAX_WIDGETS_PER_PROFILE: Int = 64

    const val MAX_ID_LENGTH: Int = 96

    const val MAX_SOURCE_TOKEN_LENGTH: Int = 160

    const val MAX_DISPLAY_TITLE_LENGTH: Int = 200
}

sealed interface HomeWidgetSourceRef {

    val token: String

    data class ExternalCatalog(
        val addonId: String,
        val catalogType: String,
        val catalogId: String,
    ) : HomeWidgetSourceRef {
        override val token: String get() = TOKEN

        companion object {
            const val TOKEN: String = "external_catalog"
        }
    }

    data class PublicList(
        val providerId: String,
        val listId: String,

        val owner: String? = null,
    ) : HomeWidgetSourceRef {
        override val token: String get() = TOKEN

        companion object {
            const val TOKEN: String = "public_list"

            const val ABSENT_OWNER: String = ""
        }
    }

    data class CollectionGroup(
        val packId: String,
        val groupKey: String,
    ) : HomeWidgetSourceRef {
        override val token: String get() = TOKEN

        companion object {
            const val TOKEN: String = "collection_group"
        }
    }

    companion object {

        fun fromToken(token: String): String? =
            when (token) {
                ExternalCatalog.TOKEN -> ExternalCatalog.TOKEN
                PublicList.TOKEN -> PublicList.TOKEN
                CollectionGroup.TOKEN -> CollectionGroup.TOKEN
                else -> null
            }
    }
}

fun HomeWidgetSourceRef.addressesSameSource(other: HomeWidgetSourceRef): Boolean = when {
    this is HomeWidgetSourceRef.ExternalCatalog && other is HomeWidgetSourceRef.ExternalCatalog ->
        addonId == other.addonId && catalogType == other.catalogType && catalogId == other.catalogId
    this is HomeWidgetSourceRef.PublicList && other is HomeWidgetSourceRef.PublicList ->
        providerId == other.providerId &&
            listId == other.listId &&
            (owner ?: HomeWidgetSourceRef.PublicList.ABSENT_OWNER) ==
            (other.owner ?: HomeWidgetSourceRef.PublicList.ABSENT_OWNER)

    this is HomeWidgetSourceRef.CollectionGroup && other is HomeWidgetSourceRef.CollectionGroup ->
        packId == other.packId && groupKey == other.groupKey
    else -> false
}

data class HomeWidgetRecord(
    val widgetId: String,
    val destinationId: String,
    val source: HomeWidgetSourceRef,

    val displayTitle: String? = null,
    val recordVersion: Int = CURRENT_RECORD_VERSION,
) {
    companion object {

        const val CURRENT_RECORD_VERSION: Int = 1

        fun storableTitle(title: String?): String? {
            val trimmed = title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return trimmed.takeIf { it.length <= HomeWidgetLimits.MAX_DISPLAY_TITLE_LENGTH }
        }
    }
}

data class HomeWidgetConfiguration(
    val configured: Boolean,
    val records: List<HomeWidgetRecord> = emptyList(),

    val unreadableRecords: List<String> = emptyList(),

    val unreadablePayload: String? = null,
) {
    companion object {

        val Absent: HomeWidgetConfiguration = HomeWidgetConfiguration(configured = false)
    }
}
