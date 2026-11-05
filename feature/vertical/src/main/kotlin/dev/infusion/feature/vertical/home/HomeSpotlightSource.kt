package dev.infusion.feature.vertical.home

import dev.infusion.core.domain.home.HomeWidgetSourceRef
import dev.infusion.core.domain.publist.PublicListProvider
import java.net.URLDecoder
import java.net.URLEncoder

internal const val MAX_SPOTLIGHT_SOURCE_ID_LENGTH: Int = 160

sealed interface HomeSpotlightSource {

    data object Automatic : HomeSpotlightSource

    data class Row(val rowId: HomeSpotlightRowId) : HomeSpotlightSource
}

sealed interface HomeSpotlightRowId {

    data class Widget(val widgetId: String) : HomeSpotlightRowId

    data class AddonCatalog(
        val addonId: String,
        val catalogType: String,
        val catalogId: String,
    ) : HomeSpotlightRowId
}

enum class HomeSpotlightSourceArm {

    EXTERNAL_CATALOG,

    PUBLIC_LIST,
}

data class HomeSpotlightSourceOption(
    val rowId: HomeSpotlightRowId,

    val label: String,

    val context: String? = null,
    val arm: HomeSpotlightSourceArm,
)

fun homeSpotlightRowKey(rowId: HomeSpotlightRowId): String = when (rowId) {
    is HomeSpotlightRowId.Widget -> homeWidgetRowKey(rowId.widgetId)
    is HomeSpotlightRowId.AddonCatalog -> HomeCatalogKey(
        addonId = rowId.addonId,
        catalogType = rowId.catalogType,
        catalogId = rowId.catalogId,
    ).shelfKey
}

fun HomeSpotlightSource.rowKeyOrNull(): String? =
    (this as? HomeSpotlightSource.Row)?.let { row -> homeSpotlightRowKey(row.rowId) }

object HomeSpotlightSourceCodec {

    const val TOKEN_AUTOMATIC: String = "auto"
    const val TOKEN_WIDGET: String = "widget"
    const val TOKEN_CATALOG: String = "catalog"

    private const val FIELD_SEPARATOR = '|'

    fun encode(source: HomeSpotlightSource): String = when (source) {
        HomeSpotlightSource.Automatic -> TOKEN_AUTOMATIC
        is HomeSpotlightSource.Row -> when (val rowId = source.rowId) {
            is HomeSpotlightRowId.Widget -> {
                require(storable(rowId.widgetId)) { "widget id is outside the storage bounds" }
                join(TOKEN_WIDGET, rowId.widgetId)
            }
            is HomeSpotlightRowId.AddonCatalog -> {
                require(
                    storable(rowId.addonId) && storable(rowId.catalogType) && storable(rowId.catalogId),
                ) { "catalog identity is outside the storage bounds" }
                join(TOKEN_CATALOG, rowId.addonId, rowId.catalogType, rowId.catalogId)
            }
        }
    }

    fun encodeOrAutomatic(source: HomeSpotlightSource): String =
        runCatching { encode(source) }.getOrDefault(TOKEN_AUTOMATIC)

    fun decode(stored: String?): HomeSpotlightSource {
        if (stored == null) return HomeSpotlightSource.Automatic
        val fields = stored.split(FIELD_SEPARATOR)
        return when (fields.firstOrNull()) {
            TOKEN_AUTOMATIC -> HomeSpotlightSource.Automatic
            TOKEN_WIDGET -> {
                if (fields.size != 2) return HomeSpotlightSource.Automatic
                val widgetId = unescape(fields[1])?.takeIf { storable(it) }
                    ?: return HomeSpotlightSource.Automatic
                HomeSpotlightSource.Row(HomeSpotlightRowId.Widget(widgetId))
            }
            TOKEN_CATALOG -> {
                if (fields.size != 4) return HomeSpotlightSource.Automatic
                val addonId = unescape(fields[1])?.takeIf { storable(it) }
                    ?: return HomeSpotlightSource.Automatic
                val catalogType = unescape(fields[2])?.takeIf { storable(it) }
                    ?: return HomeSpotlightSource.Automatic
                val catalogId = unescape(fields[3])?.takeIf { storable(it) }
                    ?: return HomeSpotlightSource.Automatic
                HomeSpotlightSource.Row(HomeSpotlightRowId.AddonCatalog(addonId, catalogType, catalogId))
            }
            else -> HomeSpotlightSource.Automatic
        }
    }

    private fun join(vararg fields: String): String = fields.joinToString(FIELD_SEPARATOR.toString()) { field ->
        URLEncoder.encode(field, Charsets.UTF_8.name())
    }

    private fun unescape(value: String): String? =
        runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrNull()

    private fun storable(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_SPOTLIGHT_SOURCE_ID_LENGTH
}

const val SpotlightSourceAutomaticLabel: String = "Automatic"

fun homeWidgetSpotlightSourceOptions(entries: List<HomeWidgetEntry>): List<HomeSpotlightSourceOption> =
    entries.mapNotNull { entry ->
        if (entry.destinationId != WidgetDestinations.HOME.id) return@mapNotNull null
        if (!entry.declaresRow) return@mapNotNull null
        when (val source = entry.record.source) {
            is HomeWidgetSourceRef.ExternalCatalog -> HomeSpotlightSourceOption(
                rowId = HomeSpotlightRowId.Widget(entry.widgetId),
                label = entry.displayTitle,
                context = listOf(
                    "${source.catalogType}/${source.catalogId}",
                    entry.addonDisplayName ?: source.addonId,
                ).joinToString(" · "),
                arm = HomeSpotlightSourceArm.EXTERNAL_CATALOG,
            )
            is HomeWidgetSourceRef.PublicList -> HomeSpotlightSourceOption(
                rowId = HomeSpotlightRowId.Widget(entry.widgetId),
                label = entry.displayTitle,
                context = publicListSourceContext(entry, source),
                arm = HomeSpotlightSourceArm.PUBLIC_LIST,
            )

            is HomeWidgetSourceRef.CollectionGroup -> null
        }
    }

fun homeCatalogSpotlightSourceOptions(
    options: List<HomeCatalogOption>,
): List<HomeSpotlightSourceOption> = options.map { option ->
    HomeSpotlightSourceOption(
        rowId = HomeSpotlightRowId.AddonCatalog(
            addonId = option.key.addonId,
            catalogType = option.key.catalogType,
            catalogId = option.key.catalogId,
        ),
        label = option.displayName,
        context = "${option.key.catalogType}/${option.key.catalogId} · ${option.addonDisplayName}",
        arm = HomeSpotlightSourceArm.EXTERNAL_CATALOG,
    )
}

fun homeSpotlightSourceLabel(
    source: HomeSpotlightSource,
    options: List<HomeSpotlightSourceOption>,
): String = when (source) {
    HomeSpotlightSource.Automatic -> SpotlightSourceAutomaticLabel
    is HomeSpotlightSource.Row -> options.firstOrNull { it.rowId == source.rowId }?.label
        ?: SpotlightSourceAutomaticLabel
}

const val SpotlightSourceRowInUseMessage: String =
    "This row is used by Spotlight. Choose Automatic or another source in Settings → Home → " +
        "Spotlight source before deleting it."

fun widgetIsSpotlightSource(widgetId: String, source: HomeSpotlightSource?): Boolean =
    source is HomeSpotlightSource.Row && source.rowId == HomeSpotlightRowId.Widget(widgetId)

fun homeSpotlightSourceConfirmedGone(
    source: HomeSpotlightSource,
    configuredRowKeys: Set<String>?,
): Boolean {
    val rowKey = (source as? HomeSpotlightSource.Row)?.let { row -> homeSpotlightRowKey(row.rowId) }
        ?: return false
    return configuredRowKeys != null && rowKey !in configuredRowKeys
}

private fun publicListSourceContext(
    entry: HomeWidgetEntry,
    source: HomeWidgetSourceRef.PublicList,
): String {
    entry.publicListSourceLabel?.takeIf { it.isNotBlank() }?.let { label -> return label }
    val provider = PublicListProvider.fromId(source.providerId)?.displayName ?: source.providerId
    val owner = entry.sourceOwnerLabel?.takeIf { it.isNotBlank() }
    return listOfNotNull(provider, owner, source.listId).joinToString(" · ")
}
