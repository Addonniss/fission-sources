package dev.infusion.tv.configweb

import dev.infusion.feature.configweb.ConfigApplyResult
import dev.infusion.feature.configweb.ConfigFamily
import dev.infusion.feature.configweb.ConfigFamilyState
import dev.infusion.feature.configweb.ConfigGroupState
import dev.infusion.feature.configweb.ConfigOperation
import dev.infusion.feature.configweb.ConfigOperationKind
import dev.infusion.feature.configweb.ConfigOwnerPort
import dev.infusion.feature.configweb.ConfigRevision
import dev.infusion.feature.configweb.ConfigRowControl
import dev.infusion.feature.configweb.ConfigState
import dev.infusion.feature.configweb.ConfigStatePort
import dev.infusion.feature.configweb.ConfigValidationError
import dev.infusion.feature.configweb.ConfigValue
import dev.infusion.feature.configweb.RevisionValue

class TvConfigOwnerBridge(
    private val owners: TvConfigOwners,
) : ConfigOwnerPort, ConfigStatePort {

    private val items = ConfigItemOperations(owners)

    private val rowsByFamily: Map<ConfigFamily, List<RowSpec>> = linkedMapOf(
        ConfigFamily.GENERAL to ConfigRows.general(owners),
        ConfigFamily.PLAYBACK to ConfigRows.playback(owners),
        ConfigFamily.RATINGS to ConfigRows.ratings(owners),
        ConfigFamily.HOME to ConfigRows.home(owners),
    )

    private val rowsByKey: Map<Pair<ConfigFamily, String>, RowSpec> =
        rowsByFamily.flatMap { (family, rows) -> rows.map { family to it.key to it } }.toMap()

    override fun activeProfileId(): String = owners.activeProfileId()

    override fun revisionFingerprint(): String = ConfigRevision.fingerprint(revisionValues())

    private fun revisionValues(): List<RevisionValue> = buildList {
        rowsByFamily.forEach { (family, rows) ->
            rows.forEach { row ->
                add(RevisionValue(family, row.key, row.read()))
            }
        }
        addAll(items.revisionValues())
    }

    override fun labelOf(operation: ConfigOperation): String {
        items.labelOf(operation)?.let { return it }
        val row = rowsByKey[operation.family to operation.rowKey]
        if (row != null) {
            return if (row.parentKey != null && !row.active()) {
                "${row.label} (kept while ${parentLabel(row.parentKey)} is off)"
            } else {
                row.label
            }
        }
        return when (operation.kind) {
            ConfigOperationKind.ADD -> "Add ${operation.family.label}"
            ConfigOperationKind.REMOVE -> "Remove from ${operation.family.label}"
            ConfigOperationKind.ENABLE -> "Show in ${operation.family.label}"
            ConfigOperationKind.DISABLE -> "Hide in ${operation.family.label}"
            ConfigOperationKind.REORDER -> "Reorder ${operation.family.label}"
            ConfigOperationKind.IMPORT -> "Import into ${operation.family.label}"
            ConfigOperationKind.REFRESH -> "Refresh ${operation.family.label}"
            ConfigOperationKind.SET_ROW -> "Change ${operation.family.label}"
        }
    }

    private fun parentLabel(parentKey: String): String =
        rowsByFamily.values.flatten().firstOrNull { it.key == parentKey }?.label ?: "its parent setting"

    override fun validate(operation: ConfigOperation): ConfigValidationError? {
        val rows = rowsByFamily[operation.family]
        if (operation.kind != ConfigOperationKind.SET_ROW || rows == null) {
            if (rows == null) return items.validate(operation)
            return when (operation.kind) {
                ConfigOperationKind.SET_ROW -> ConfigValidationError(
                    "rowKey",
                    "this configuration family has no settings rows",
                )
                else -> items.validate(operation)
            }
        }
        val row = rowsByKey[operation.family to operation.rowKey]
            ?: return ConfigValidationError("rowKey", "this is not a setting this build offers")
        val token = when (row.control) {
            ConfigRowControl.FLAG -> (operation.value as? ConfigValue.Flag)?.let { flag(it.value) }
            ConfigRowControl.NUMBER -> (operation.value as? ConfigValue.Count)?.let { number(it.value.toInt()) }
            ConfigRowControl.CHOICE -> (operation.value as? ConfigValue.Text)?.value
        } ?: return ConfigValidationError("value", valueExpectation(row.control))
        return row.validateValue(token)
    }

    private fun valueExpectation(control: ConfigRowControl): String = when (control) {
        ConfigRowControl.FLAG -> "expected true or false for this setting"
        ConfigRowControl.NUMBER -> "expected a whole number for this setting"
        ConfigRowControl.CHOICE -> "expected one of this setting's choices"
    }

    override fun apply(operation: ConfigOperation): ConfigApplyResult {
        val rows = rowsByFamily[operation.family]
        if (operation.kind != ConfigOperationKind.SET_ROW || rows == null) {
            return items.apply(operation)
        }
        val row = rowsByKey[operation.family to operation.rowKey]
            ?: return ConfigApplyResult.Failed("this is not a setting this build offers")
        val token = when (row.control) {
            ConfigRowControl.FLAG -> (operation.value as? ConfigValue.Flag)?.let { flag(it.value) }
            ConfigRowControl.NUMBER -> (operation.value as? ConfigValue.Count)?.let { number(it.value.toInt()) }
            ConfigRowControl.CHOICE -> (operation.value as? ConfigValue.Text)?.value
        } ?: return ConfigApplyResult.Failed(valueExpectation(row.control))
        return row.apply(token)
    }

    override fun state(): ConfigState = ConfigState(
        revision = revisionFingerprint(),
        families = rowsByFamily.map { (family, rows) -> familyState(family, rows) },
        addons = items.addonsState(),
        homeCatalogs = items.homeCatalogsState(),
        widgets = items.widgetsState(),
        widgetLimit = dev.infusion.core.domain.home.HomeWidgetLimits.MAX_WIDGETS_PER_PROFILE,
        omdbKeyConfigured = runCatching { owners.ratingsCredentials.hasOmdbKey() }.getOrDefault(false),
        collectionPacks = items.packsState(),
        collectionPages = items.pagesState(),

        widgetDestinations = items.widgetDestinationsState(),
        widgetCatalogs = items.widgetCatalogsState(),
        widgetCollectionGroups = items.widgetCollectionGroupsState(),
    )

    private fun familyState(family: ConfigFamily, rows: List<RowSpec>): ConfigFamilyState =
        ConfigFamilyState(
            family = family,
            groups = rows.groupBy { it.group }.map { (group, groupRows) ->
                ConfigGroupState(group, groupRows.map { it.state() })
            },
        )
}
