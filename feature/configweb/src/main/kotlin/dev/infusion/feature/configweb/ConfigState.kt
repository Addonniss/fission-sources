package dev.infusion.feature.configweb

enum class ConfigRowControl(val wireName: String) {

    FLAG("flag"),

    CHOICE("choice"),

    NUMBER("number"),
}

enum class ConfigRowScope(val wireName: String) {

    PROFILE("profile"),

    DEVICE("device"),
}

data class ConfigChoiceState(val token: String, val label: String)

data class ConfigRowState(
    val key: String,
    val label: String,
    val group: String,
    val control: ConfigRowControl,
    val value: String,
    val choices: List<ConfigChoiceState> = emptyList(),
    val scope: ConfigRowScope = ConfigRowScope.PROFILE,
    val parentKey: String? = null,
    val active: Boolean = true,
    val min: Int? = null,
    val max: Int? = null,
    val help: String? = null,
)

data class ConfigGroupState(val name: String, val rows: List<ConfigRowState>)

data class ConfigFamilyState(val family: ConfigFamily, val groups: List<ConfigGroupState>) {

    fun rows(): List<ConfigRowState> = groups.flatMap { it.rows }
}

data class ConfigAddonCatalogState(
    val catalogType: String,
    val catalogId: String,
    val name: String,
    val position: Int,
    val visibleInDiscover: Boolean,
)

data class ConfigAddonState(
    val addonId: String,
    val name: String,
    val enabled: Boolean,
    val position: Int,
    val origin: String,
    val managed: Boolean,
    val catalogs: List<ConfigAddonCatalogState> = emptyList(),
)

data class ConfigHomeCatalogState(
    val addonId: String,
    val catalogType: String,
    val catalogId: String,
    val name: String,
    val selected: Boolean,
    val hiddenFromHome: Boolean,
    val position: Int,
)

data class ConfigWidgetState(
    val widgetId: String,
    val label: String,
    val position: Int,
)

data class ConfigWidgetDestinationState(val id: String, val label: String)

data class ConfigWidgetCatalogState(
    val addonId: String,
    val catalogType: String,
    val catalogId: String,
    val name: String,
)

data class ConfigWidgetCollectionGroupState(
    val packId: String,
    val groupKey: String,
    val name: String,
)

data class ConfigCollectionPackState(
    val packId: String,
    val name: String,
    val origin: String,
    val groupCount: Int,
    val tileCount: Int,
    val placementCount: Int,
)

data class ConfigCollectionRowState(
    val rowKey: String,
    val title: String,
    val enabled: Boolean,
    val position: Int,
)

data class ConfigCollectionPageState(
    val packId: String,
    val groupKey: String,
    val tileKey: String,
    val title: String,
    val rows: List<ConfigCollectionRowState> = emptyList(),

    val truncated: Boolean = false,
)

data class ConfigState(
    val revision: String,
    val families: List<ConfigFamilyState>,
    val addons: List<ConfigAddonState> = emptyList(),
    val homeCatalogs: List<ConfigHomeCatalogState> = emptyList(),
    val widgets: List<ConfigWidgetState> = emptyList(),
    val widgetLimit: Int = 0,
    val omdbKeyConfigured: Boolean = false,
    val collectionPacks: List<ConfigCollectionPackState> = emptyList(),
    val collectionPages: List<ConfigCollectionPageState> = emptyList(),
    val widgetDestinations: List<ConfigWidgetDestinationState> = emptyList(),
    val widgetCatalogs: List<ConfigWidgetCatalogState> = emptyList(),
    val widgetCollectionGroups: List<ConfigWidgetCollectionGroupState> = emptyList(),
) {

    fun family(family: ConfigFamily): ConfigFamilyState? = families.firstOrNull { it.family == family }
}

fun interface ConfigStatePort {
    fun state(): ConfigState

    companion object {

        val Unavailable: ConfigStatePort = ConfigStatePort {
            ConfigState(revision = "", families = emptyList())
        }
    }
}
