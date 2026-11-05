package dev.infusion.tv.configweb

import dev.infusion.core.domain.collections.CollectionTile
import dev.infusion.core.domain.home.HomeWidgetLimits
import dev.infusion.core.domain.home.HomeWidgetRecord

import dev.infusion.feature.configweb.ConfigAddonCatalogState
import dev.infusion.feature.configweb.ConfigAddonState
import dev.infusion.feature.configweb.ConfigApplyResult
import dev.infusion.feature.configweb.ConfigCollectionPackState
import dev.infusion.feature.configweb.ConfigCollectionPageState
import dev.infusion.feature.configweb.ConfigCollectionRowState
import dev.infusion.feature.configweb.ConfigFamily
import dev.infusion.feature.configweb.ConfigHomeCatalogState
import dev.infusion.feature.configweb.ConfigOperation
import dev.infusion.feature.configweb.ConfigOperationKind
import dev.infusion.feature.configweb.ConfigValidationError
import dev.infusion.feature.configweb.ConfigValue
import dev.infusion.feature.configweb.ConfigWidgetCatalogState
import dev.infusion.feature.configweb.ConfigWidgetCollectionGroupState
import dev.infusion.feature.configweb.ConfigWidgetDestinationState
import dev.infusion.feature.configweb.ConfigWidgetState
import dev.infusion.feature.configweb.RevisionValue
import dev.infusion.feature.vertical.addon.AddonCatalogEntryUi
import dev.infusion.feature.vertical.addon.AddonConsentInput
import dev.infusion.feature.vertical.addon.AddonInstallOutcome
import dev.infusion.feature.vertical.addon.AddonManagementItemUi
import dev.infusion.feature.vertical.addon.AddonRefreshOutcome
import dev.infusion.feature.vertical.addon.AddonReviewOutcome
import dev.infusion.feature.vertical.addon.AddonReviewUi
import dev.infusion.feature.vertical.home.HomeCatalogKey
import dev.infusion.feature.vertical.home.HomeCatalogOption
import dev.infusion.feature.vertical.home.HomeProviderHomePreference
import dev.infusion.feature.vertical.home.WidgetAddOutcome
import kotlinx.coroutines.runBlocking

internal class ConfigItemOperations(private val owners: TvConfigOwners) {

    fun validate(operation: ConfigOperation): ConfigValidationError? = when (operation.family) {
        ConfigFamily.ADDONS -> validateAddon(operation)
        ConfigFamily.HOME -> validateHomeItem(operation)
        ConfigFamily.COLLECTIONS -> validateCollection(operation)
        else -> ConfigValidationError("kind", "this configuration family does not accept item operations")
    }

    private fun validateAddon(operation: ConfigOperation): ConfigValidationError? {
        val viewer = owners.addons.viewerProfile()
        val primary = owners.addons.primaryProfile()
        val installed = owners.addons.listVisibleAddons(viewer, primary)
        return when (operation.kind) {
            ConfigOperationKind.ADD -> {
                val url = textOf(operation) ?: return ConfigValidationError("value", "expected a manifest URL")
                if (dev.infusion.feature.vertical.addon.AddonDeepLink.parse(url) == null) {
                    ConfigValidationError("value", "this is not an add-on URL")
                } else {
                    null
                }
            }
            ConfigOperationKind.REMOVE, ConfigOperationKind.ENABLE, ConfigOperationKind.DISABLE,
            ConfigOperationKind.REFRESH -> {
                if (operation.rowKey == ROW_CATALOG_DISCOVER) {

                    val catalog = catalogIdOf(operation.targetId)
                    return when {
                        catalog == null ->
                            ConfigValidationError("targetId", "expected a catalog identity")
                        owners.addons.catalogEntries(viewer, primary).none {
                            it.addonId == catalog.first &&
                                it.catalogType == catalog.second &&
                                it.catalogId == catalog.third
                        } -> ConfigValidationError("targetId", "that catalog is not available right now")
                        else -> null
                    }
                }
                val target = operation.targetId
                when {
                    operation.rowKey != ROW_ADDON ->
                        ConfigValidationError("rowKey", "this is not an add-on operation this build offers")
                    target.isNullOrBlank() -> ConfigValidationError("targetId", "expected an add-on identity")
                    else -> {
                        val addon = installed.firstOrNull { it.addonId == target }
                        when {
                            addon == null -> ConfigValidationError("targetId", "that add-on is not installed")
                            !addon.canManage ->
                                ConfigValidationError("targetId", "this add-on belongs to another profile")
                            else -> null
                        }
                    }
                }
            }
            ConfigOperationKind.REORDER -> when (operation.rowKey) {
                ROW_INSTALLED_ORDER -> {
                    val ids = orderOf(operation) ?: return ConfigValidationError("value", "expected an identity order")
                    val installedIds = installed.map { it.addonId }
                    when {
                        ids.isEmpty() -> ConfigValidationError("value", "expected at least one add-on")
                        ids.toSet() != installedIds.toSet() ->
                            ConfigValidationError("value", "the order must name every installed add-on exactly once")
                        else -> null
                    }
                }
                ROW_CATALOG_ORDER -> {
                    val ids = orderOf(operation) ?: return ConfigValidationError("value", "expected an identity order")
                    val decoded = ids.map { catalogIdOf(it) }
                    val catalogs = owners.addons.catalogEntries(viewer, primary)
                    val addonIds = decoded.mapNotNull { it?.first }.distinct()
                    when {
                        decoded.any { it == null } ->
                            ConfigValidationError("value", "the order must name catalog rows of this add-on")
                        addonIds.size != 1 ->
                            ConfigValidationError("value", "one order may only name the catalogs of one add-on")
                        else -> {
                            val addonId = addonIds.first()
                            val expected = catalogs.filter { it.addonId == addonId }
                                .map { "${it.addonId}|${it.catalogType}|${it.catalogId}" }
                            if (ids.toSet() != expected.toSet()) {
                                ConfigValidationError(
                                    "value",
                                    "the order must name every catalog of that add-on exactly once",
                                )
                            } else {
                                null
                            }
                        }
                    }
                }
                else -> ConfigValidationError("rowKey", "this is not an add-on order this build offers")
            }
            else -> ConfigValidationError("kind", "this add-on operation is not available")
        }
    }

    private fun validateHomeItem(operation: ConfigOperation): ConfigValidationError? {
        val profileId = owners.activeProfileId()
        val viewer = owners.widgets.viewerProfile()
        val primary = owners.widgets.primaryProfile()
        return when (operation.kind) {
            ConfigOperationKind.ADD -> when (operation.rowKey) {
                ROW_WIDGET -> {
                    val source = widgetSourceOf(textOf(operation))
                        ?: return ConfigValidationError("value", "expected a widget source")
                    when (source) {
                        is WidgetSource.ExternalCatalog -> {
                            val available = owners.widgets.availableExternalCatalogs(viewer, primary)
                            if (available.none { matches(it, source) }) {
                                ConfigValidationError("value", "that catalog is not available right now")
                            } else {
                                null
                            }
                        }
                        is WidgetSource.CollectionGroup ->
                            if (owners.widgets.availableCollectionGroups(profileId)
                                    .none { it.key.packId == source.packId && it.key.groupKey == source.groupKey }
                            ) {
                                ConfigValidationError("value", "that collection page is not imported")
                            } else {
                                null
                            }
                    }
                }
                ROW_HOME_CATALOG -> {
                    val catalog = catalogIdOf(textOf(operation))
                        ?: return ConfigValidationError("value", "expected a catalog identity")
                    val available = owners.homeCatalog.availableCatalogs(viewer, primary)
                    if (available.none { it.key.addonId == catalog.first && it.key.catalogType == catalog.second && it.key.catalogId == catalog.third }) {
                        ConfigValidationError("value", "that catalog is not available right now")
                    } else {
                        null
                    }
                }
                else -> ConfigValidationError("rowKey", "this is not a Home operation this build offers")
            }
            ConfigOperationKind.REMOVE -> when (operation.rowKey) {
                ROW_WIDGET ->
                    if (owners.widgets.widgetConfiguration(profileId).records.none { it.widgetId == operation.targetId }) {
                        ConfigValidationError("targetId", "that Home row does not exist")
                    } else {
                        null
                    }
                ROW_HOME_CATALOG ->
                    if (catalogIdOf(operation.targetId) == null) {
                        ConfigValidationError("targetId", "expected a catalog identity")
                    } else {
                        null
                    }
                else -> ConfigValidationError("rowKey", "this is not a Home operation this build offers")
            }
            ConfigOperationKind.ENABLE, ConfigOperationKind.DISABLE -> when (operation.rowKey) {
                ROW_PROVIDER_HIDDEN ->
                    if (owners.addons.listVisibleAddons(viewer, primary).none { it.addonId == operation.targetId }) {
                        ConfigValidationError("targetId", "that provider is not installed")
                    } else {
                        null
                    }
                else -> ConfigValidationError("rowKey", "this is not a Home operation this build offers")
            }
            ConfigOperationKind.REORDER -> when (operation.rowKey) {
                ROW_WIDGET_ORDER -> {
                    val ids = orderOf(operation) ?: return ConfigValidationError("value", "expected an identity order")
                    val existing = owners.widgets.widgetConfiguration(profileId).records.map { it.widgetId }
                    if (ids.toSet() != existing.toSet()) {
                        ConfigValidationError("value", "the order must name every created Home row exactly once")
                    } else {
                        null
                    }
                }
                ROW_HOME_CATALOG_ORDER -> {
                    val ids = orderOf(operation) ?: return ConfigValidationError("value", "expected an identity order")
                    val decoded = ids.map { catalogIdOf(it) }
                    if (decoded.any { it == null }) {
                        ConfigValidationError("value", "the order must name Home catalog rows")
                    } else {
                        val selected = owners.homeCatalog.selectedCatalogs(profileId)
                            .map { "${it.addonId}|${it.catalogType}|${it.catalogId}" }
                        if (ids.toSet() != selected.toSet()) {
                            ConfigValidationError("value", "the order must name every Home row exactly once")
                        } else {
                            null
                        }
                    }
                }
                else -> ConfigValidationError("rowKey", "this is not a Home order this build offers")
            }
            else -> ConfigValidationError("kind", "this Home operation is not available")
        }
    }

    private fun validateCollection(operation: ConfigOperation): ConfigValidationError? {
        val profileId = owners.activeProfileId()
        return when (operation.kind) {
            ConfigOperationKind.IMPORT -> {
                val url = textOf(operation)
                when {
                    operation.rowKey != ROW_PACK ->
                        ConfigValidationError("rowKey", "this is not a collection operation this build offers")
                    url.isNullOrBlank() -> ConfigValidationError("value", "expected a collection-pack URL")
                    !url.trim().startsWith("https://") && !url.trim().startsWith("http://") ->
                        ConfigValidationError("value", "a collection pack must be fetched over http(s)")
                    else -> null
                }
            }
            ConfigOperationKind.REMOVE ->
                if (operation.rowKey != ROW_PACK) {
                    ConfigValidationError("rowKey", "this is not a collection operation this build offers")
                } else if (owners.collectionPackStore.read(profileId).packs.none { it.packId == operation.targetId }) {
                    ConfigValidationError("targetId", "that collection pack is not imported")
                } else {
                    null
                }
            ConfigOperationKind.ENABLE, ConfigOperationKind.DISABLE -> {
                val id = collectionRowIdOf(operation.targetId)
                when {
                    operation.rowKey != ROW_COLLECTION_ROW ->
                        ConfigValidationError("rowKey", "this is not a collection operation this build offers")
                    id == null -> ConfigValidationError("targetId", "expected a collection row identity")
                    else -> {
                        val tile = tileOf(profileId, id.packId, id.groupKey, id.tileKey)
                        when {
                            tile == null -> ConfigValidationError("targetId", "that collection page does not exist")
                            tile.rows.none { it.rowKey == id.rowKey } ->
                                ConfigValidationError("targetId", "that collection row does not exist")
                            else -> null
                        }
                    }
                }
            }
            ConfigOperationKind.REORDER -> {
                val ids = orderOf(operation) ?: return ConfigValidationError("value", "expected an identity order")
                val decoded = ids.map { collectionRowIdOf(it) }
                when {
                    operation.rowKey != ROW_COLLECTION_ROW_ORDER ->
                        ConfigValidationError("rowKey", "this is not a collection order this build offers")
                    decoded.any { it == null } ->
                        ConfigValidationError("value", "the order must name collection rows")
                    else -> {
                        val first = decoded.filterNotNull().first()
                        val pageIds = decoded.filterNotNull().map { Triple(it.packId, it.groupKey, it.tileKey) }
                            .distinct()
                        if (pageIds.size != 1) {
                            ConfigValidationError("value", "one order may only name the rows of one page")
                        } else {
                            val tile = tileOf(profileId, first.packId, first.groupKey, first.tileKey)
                            if (tile == null) {
                                ConfigValidationError("value", "that collection page does not exist")
                            } else if (decoded.filterNotNull().map { it.rowKey }.toSet() !=
                                tile.rows.map { it.rowKey }.toSet()
                            ) {
                                ConfigValidationError("value", "the order must name every row of the page once")
                            } else {
                                null
                            }
                        }
                    }
                }
            }
            else -> ConfigValidationError("kind", "this collection operation is not available")
        }
    }

    fun apply(operation: ConfigOperation): ConfigApplyResult = try {
        when (operation.family) {
            ConfigFamily.ADDONS -> applyAddon(operation)
            ConfigFamily.HOME -> applyHomeItem(operation)
            ConfigFamily.COLLECTIONS -> applyCollection(operation)
            else -> ConfigApplyResult.Failed("this configuration family does not accept item operations")
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        ConfigApplyResult.Failed("the owner could not apply this operation")
    }

    private fun applyAddon(operation: ConfigOperation): ConfigApplyResult = when (operation.kind) {
        ConfigOperationKind.ADD -> installAddon(textOf(operation).orEmpty())
        ConfigOperationKind.ENABLE -> {
            if (operation.rowKey == ROW_CATALOG_DISCOVER) {
                return setCatalogDiscoverVisibility(operation.targetId.orEmpty(), visible = true)
            }
            val addonId = operation.targetId.orEmpty()
            if (runBlocking { owners.addons.enable(addonId) }) {
                ConfigApplyResult.Applied
            } else {
                ConfigApplyResult.Failed("the add-on could not be enabled")
            }
        }
        ConfigOperationKind.DISABLE -> {
            if (operation.rowKey == ROW_CATALOG_DISCOVER) {
                return setCatalogDiscoverVisibility(operation.targetId.orEmpty(), visible = false)
            }
            val addonId = operation.targetId.orEmpty()
            if (runBlocking { owners.addons.disable(addonId) }) {
                ConfigApplyResult.Applied
            } else {
                ConfigApplyResult.Failed("the add-on could not be disabled")
            }
        }
        ConfigOperationKind.REMOVE -> {
            val addonId = operation.targetId.orEmpty()
            if (runBlocking { owners.addons.remove(addonId) }) {
                ConfigApplyResult.Applied
            } else {
                ConfigApplyResult.Failed("the add-on could not be removed")
            }
        }
        ConfigOperationKind.REFRESH -> when (runBlocking { owners.addons.refresh(operation.targetId.orEmpty()) }) {
            is AddonRefreshOutcome.Refreshed -> ConfigApplyResult.Applied
            is AddonRefreshOutcome.Fresh -> ConfigApplyResult.Applied
            is AddonRefreshOutcome.Disabled -> ConfigApplyResult.Failed("the add-on is disabled; enable it to refresh")
            is AddonRefreshOutcome.Failed ->
                ConfigApplyResult.Failed("the add-on could not be refreshed; its last validated snapshot is kept")
        }
        ConfigOperationKind.REORDER -> when (operation.rowKey) {
            ROW_INSTALLED_ORDER -> {
                val ids = orderOf(operation).orEmpty()
                if (runBlocking { owners.addons.setInstalledOrder(ids) }) {
                    ConfigApplyResult.Applied
                } else {
                    ConfigApplyResult.Failed("the owner did not accept this order")
                }
            }
            ROW_CATALOG_ORDER -> reorderCatalogs(orderOf(operation).orEmpty())
            else -> ConfigApplyResult.Failed("this add-on operation is not available")
        }
        else -> ConfigApplyResult.Failed("this add-on operation is not available")
    }

    private fun installAddon(url: String): ConfigApplyResult {
        if (url.isBlank()) return ConfigApplyResult.Failed("expected a manifest URL")
        val port = owners.addons
        return runBlocking {
            var review = port.review(url, lanReviewApproved = false)
            if (review is AddonReviewOutcome.LanConsentRequired) {
                review = port.review(url, lanReviewApproved = true)
            }
            val ready = review as? AddonReviewOutcome.Ready
                ?: return@runBlocking ConfigApplyResult.Failed(reviewFailure(review))
            when (val outcome = port.install(consentFor(ready.review))) {
                is AddonInstallOutcome.Installed -> ConfigApplyResult.Applied
                is AddonInstallOutcome.AlreadyInstalled -> ConfigApplyResult.Applied
                is AddonInstallOutcome.ConsentDenied -> ConfigApplyResult.Failed("the add-on owner refused the consent")
                is AddonInstallOutcome.Invalid -> ConfigApplyResult.Failed("the add-on manifest was not valid")
                is AddonInstallOutcome.Failed -> ConfigApplyResult.Failed("the add-on could not be installed")
            }
        }
    }

    private fun reviewFailure(outcome: AddonReviewOutcome): String = when (outcome) {
        is AddonReviewOutcome.Invalid -> "the add-on manifest was not valid"
        is AddonReviewOutcome.Failed -> "the add-on manifest could not be fetched"
        is AddonReviewOutcome.LanConsentRequired -> "the add-on host needs LAN access that was not granted"
        is AddonReviewOutcome.Ready -> "the add-on manifest could not be reviewed"
    }

    private fun consentFor(review: AddonReviewUi): AddonConsentInput = AddonConsentInput(
        endpointAccepted = true,
        resourceTypesAccepted = true,
        catalogVisibilityAccepted = true,
        networkDisclosureAccepted = true,
        credentialUseNoneAccepted = true,
        lanGranted = review.lanRequested,
    )

    private fun reorderCatalogs(ids: List<String>): ConfigApplyResult {
        val decoded = ids.mapNotNull { catalogIdOf(it) }
        if (decoded.isEmpty()) return ConfigApplyResult.Failed("expected a catalog order")
        val profileId = owners.activeProfileId()
        val viewer = owners.addons.viewerProfile()
        val primary = owners.addons.primaryProfile()
        val catalogs = owners.addons.catalogEntries(viewer, primary)
        val addonId = decoded.first().first

        val slots = catalogs.filter { it.addonId == addonId }
            .mapIndexed { index, entry -> entry to index }
        val slotOrder = slots.map { it.first.sortOrder ?: it.second }.sorted()
        decoded.forEachIndexed { index, triple ->
            val entry = catalogs.firstOrNull {
                it.addonId == triple.first && it.catalogType == triple.second && it.catalogId == triple.third
            } ?: return ConfigApplyResult.Failed("that catalog is not available right now")
            owners.addons.setCatalogPreference(
                profileId = profileId,
                addonId = entry.addonId,
                catalogType = entry.catalogType,
                catalogId = entry.catalogId,
                sortOrder = slotOrder.getOrElse(index) { index },
                visibleInDiscover = entry.visibleInDiscover,
            )
        }
        return if (catalogOrderMatches(addonId, decoded)) {
            ConfigApplyResult.Applied
        } else {
            ConfigApplyResult.Failed("the owner did not keep this order")
        }
    }

    private fun catalogOrderMatches(addonId: String, decoded: List<Triple<String, String, String>>): Boolean {
        val viewer = owners.addons.viewerProfile()
        val primary = owners.addons.primaryProfile()
        val current = owners.addons.catalogEntries(viewer, primary)
            .filter { it.addonId == addonId }
            .sortedWith(compareBy({ it.sortOrder ?: Int.MAX_VALUE }, { it.catalogId }))
            .map { Triple(it.addonId, it.catalogType, it.catalogId) }
        return current == decoded
    }

    private fun setCatalogDiscoverVisibility(encoded: String, visible: Boolean): ConfigApplyResult {
        val catalog = catalogIdOf(encoded) ?: return ConfigApplyResult.Failed("expected a catalog identity")
        val viewer = owners.addons.viewerProfile()
        val primary = owners.addons.primaryProfile()
        val entries = owners.addons.catalogEntries(viewer, primary).filter { it.addonId == catalog.first }
        val entry = entries.firstOrNull {
            it.catalogType == catalog.second && it.catalogId == catalog.third
        } ?: return ConfigApplyResult.Failed("that catalog is not available right now")
        val slot = entries.sortedWith(compareBy({ it.sortOrder ?: Int.MAX_VALUE }, { it.catalogId }))
            .map { it.addonId to it.catalogId }
            .indexOf(entry.addonId to entry.catalogId)
            .coerceAtLeast(0)
        owners.addons.setCatalogPreference(
            profileId = owners.activeProfileId(),
            addonId = entry.addonId,
            catalogType = entry.catalogType,
            catalogId = entry.catalogId,
            sortOrder = entry.sortOrder ?: slot,
            visibleInDiscover = visible,
        )
        val current = owners.addons.catalogEntries(viewer, primary).firstOrNull {
            it.addonId == entry.addonId && it.catalogType == entry.catalogType && it.catalogId == entry.catalogId
        } ?: return ConfigApplyResult.Failed("that catalog is not available right now")
        return if (current.visibleInDiscover == visible) {
            ConfigApplyResult.Applied
        } else {
            ConfigApplyResult.Failed("the owner did not keep this value")
        }
    }

    private fun applyHomeItem(operation: ConfigOperation): ConfigApplyResult {
        val profileId = owners.activeProfileId()
        return when (operation.kind) {
            ConfigOperationKind.ADD -> when (operation.rowKey) {
                ROW_WIDGET -> addWidget(profileId, textOf(operation).orEmpty())
                ROW_HOME_CATALOG -> addHomeCatalog(profileId, textOf(operation).orEmpty())
                else -> ConfigApplyResult.Failed("this Home operation is not available")
            }
            ConfigOperationKind.REMOVE -> when (operation.rowKey) {
                ROW_WIDGET -> {
                    owners.widgets.deleteWidget(profileId, operation.targetId.orEmpty())
                    verifiedExists(
                        present = { owners.widgets.widgetConfiguration(profileId).records.none { it.widgetId == operation.targetId } },
                    )
                }
                ROW_HOME_CATALOG -> removeHomeCatalog(profileId, operation.targetId.orEmpty())
                else -> ConfigApplyResult.Failed("this Home operation is not available")
            }
            ConfigOperationKind.ENABLE, ConfigOperationKind.DISABLE -> when (operation.rowKey) {
                ROW_PROVIDER_HIDDEN ->
                    setProviderHidden(profileId, operation.targetId.orEmpty(), operation.kind == ConfigOperationKind.DISABLE)
                else -> ConfigApplyResult.Failed("this Home operation is not available")
            }
            ConfigOperationKind.REORDER -> when (operation.rowKey) {
                ROW_WIDGET_ORDER -> reorderWidgets(profileId, orderOf(operation).orEmpty())
                ROW_HOME_CATALOG_ORDER -> reorderHomeCatalogs(profileId, orderOf(operation).orEmpty())
                else -> ConfigApplyResult.Failed("this Home operation is not available")
            }
            else -> ConfigApplyResult.Failed("this Home operation is not available")
        }
    }

    private fun addWidget(profileId: String, encoded: String): ConfigApplyResult {
        val source = widgetSourceOf(encoded) ?: return ConfigApplyResult.Failed("expected a widget source")
        val before = owners.widgets.widgetConfiguration(profileId).records.map { it.widgetId }.toSet()
        val outcome = when (source) {
            is WidgetSource.ExternalCatalog -> owners.widgets.addExternalCatalogWidget(
                profileId = profileId,
                destinationId = source.destinationId,
                addonId = source.addonId,
                catalogType = source.catalogType,
                catalogId = source.catalogId,
            )
            is WidgetSource.CollectionGroup -> owners.widgets.addCollectionGroupWidget(
                profileId = profileId,
                destinationId = source.destinationId,
                packId = source.packId,
                groupKey = source.groupKey,
            )
        }
        val reason = when (outcome) {
            WidgetAddOutcome.ADDED -> null
            WidgetAddOutcome.UNKNOWN_DESTINATION -> "this build has no such Home destination"
            WidgetAddOutcome.UNKNOWN_CATALOG -> "that catalog is not available right now"
            WidgetAddOutcome.UNKNOWN_PROVIDER -> "that provider is not available in this build"
            WidgetAddOutcome.UNKNOWN_PUBLIC_LIST -> "that list is not available in this build"
            WidgetAddOutcome.UNKNOWN_COLLECTION_GROUP -> "that collection page is not imported"
            WidgetAddOutcome.LIMIT_REACHED ->
                "this profile already holds ${HomeWidgetLimits.MAX_WIDGETS_PER_PROFILE} Home rows"
            WidgetAddOutcome.DUPLICATE -> "that source is already a Home row"
            WidgetAddOutcome.WIDGET_UNKNOWN -> "that Home row does not exist"
        }
        if (reason != null) return ConfigApplyResult.Failed(reason)
        val after = owners.widgets.widgetConfiguration(profileId).records.map { it.widgetId }.toSet()
        return if (after.size == before.size + 1) {
            ConfigApplyResult.Applied
        } else {
            ConfigApplyResult.Failed("the owner did not create the Home row")
        }
    }

    private fun reorderWidgets(profileId: String, ids: List<String>): ConfigApplyResult {
        val records = owners.widgets.widgetConfiguration(profileId).records
        if (records.map { it.widgetId }.toSet() != ids.toSet()) {
            return ConfigApplyResult.Failed("the order must name every created Home row exactly once")
        }

        val working = records.map { it.widgetId }.toMutableList()
        ids.forEachIndexed { target, id ->
            while (true) {
                val index = working.indexOf(id)
                if (index < 0) return ConfigApplyResult.Failed("that Home row does not exist")
                if (index <= target) break
                owners.widgets.moveWidget(profileId, working[index], -1)
                working.add(target, working.removeAt(index))
            }
        }
        val current = owners.widgets.widgetConfiguration(profileId).records.map { it.widgetId }
        return if (current == ids) {
            ConfigApplyResult.Applied
        } else {
            ConfigApplyResult.Failed("the owner did not keep this order")
        }
    }

    private fun addHomeCatalog(profileId: String, encoded: String): ConfigApplyResult {
        val catalog = catalogIdOf(encoded) ?: return ConfigApplyResult.Failed("expected a catalog identity")
        val selected = owners.homeCatalog.selectedCatalogs(profileId)
        if (selected.any { it.addonId == catalog.first && it.catalogType == catalog.second && it.catalogId == catalog.third }) {
            return ConfigApplyResult.Applied
        }
        val next = selected + HomeCatalogKey(catalog.first, catalog.second, catalog.third)
        owners.homeCatalog.replaceSelection(profileId, next)
        return if (selectedCatalogsMatch(profileId, next)) {
            ConfigApplyResult.Applied
        } else {
            ConfigApplyResult.Failed("the owner did not add this Home row")
        }
    }

    private fun removeHomeCatalog(profileId: String, encoded: String): ConfigApplyResult {
        val catalog = catalogIdOf(encoded) ?: return ConfigApplyResult.Failed("expected a catalog identity")
        val selected = owners.homeCatalog.selectedCatalogs(profileId)
        val next = selected.filterNot {
            it.addonId == catalog.first && it.catalogType == catalog.second && it.catalogId == catalog.third
        }
        owners.homeCatalog.replaceSelection(profileId, next)
        return if (selectedCatalogsMatch(profileId, next)) {
            ConfigApplyResult.Applied
        } else {
            ConfigApplyResult.Failed("the owner did not remove this Home row")
        }
    }

    private fun reorderHomeCatalogs(profileId: String, ids: List<String>): ConfigApplyResult {
        val next = ids.mapNotNull { catalogIdOf(it) }.map { HomeCatalogKey(it.first, it.second, it.third) }
        owners.homeCatalog.replaceSelection(profileId, next)
        return if (selectedCatalogsMatch(profileId, next)) {
            ConfigApplyResult.Applied
        } else {
            ConfigApplyResult.Failed("the owner did not keep this order")
        }
    }

    private fun selectedCatalogsMatch(profileId: String, expected: List<HomeCatalogKey>): Boolean =
        owners.homeCatalog.selectedCatalogs(profileId) == expected

    private fun setProviderHidden(profileId: String, addonId: String, hidden: Boolean): ConfigApplyResult {
        val viewer = owners.widgets.viewerProfile()
        val known = owners.widgets.availableExternalCatalogs(viewer, owners.widgets.primaryProfile())
            .map { it.key.addonId }
            .distinct()

        val persisted = owners.homeCatalog.providerPreferences(profileId)
        val effective = dev.infusion.feature.vertical.home.homeProviderOrder(persisted, known)
        if (effective.none { it.addonId == addonId }) {
            return ConfigApplyResult.Failed("that provider is not available right now")
        }
        val next = effective.map {
            HomeProviderHomePreference(it.addonId, if (it.addonId == addonId) hidden else it.hiddenFromHome)
        }
        owners.homeCatalog.replaceProviderPreferences(profileId, next)
        val current = owners.homeCatalog.providerPreferences(profileId)
            .firstOrNull { it.addonId == addonId }?.hiddenFromHome
        return if (current == hidden) {
            ConfigApplyResult.Applied
        } else {
            ConfigApplyResult.Failed("the owner did not keep this value")
        }
    }

    private fun applyCollection(operation: ConfigOperation): ConfigApplyResult {
        val profileId = owners.activeProfileId()
        return when (operation.kind) {
            ConfigOperationKind.IMPORT -> importPack(profileId, textOf(operation).orEmpty())
            ConfigOperationKind.REMOVE -> {
                owners.collectionPacks.deletePack(operation.targetId.orEmpty())
                verifiedExists(
                    present = {
                        owners.collectionPackStore.read(profileId).packs.none { it.packId == operation.targetId }
                    },
                )
            }
            ConfigOperationKind.ENABLE, ConfigOperationKind.DISABLE -> {
                val id = collectionRowIdOf(operation.targetId)
                    ?: return ConfigApplyResult.Failed("expected a collection row identity")
                setCollectionRowVisible(profileId, id, operation.kind == ConfigOperationKind.ENABLE)
            }
            ConfigOperationKind.REORDER -> reorderCollectionRows(profileId, orderOf(operation).orEmpty())
            else -> ConfigApplyResult.Failed("this collection operation is not available")
        }
    }

    private fun importPack(profileId: String, url: String): ConfigApplyResult {
        if (url.isBlank()) return ConfigApplyResult.Failed("expected a collection-pack URL")
        val holder = owners.collectionPacks
        val before = owners.collectionPackStore.read(profileId).packs.size
        holder.setUrl(url)
        holder.submitUrl()

        val finished = awaitUntil {
            val state = holder.state.value
            !state.busy && state.status != null
        }
        if (!finished) return ConfigApplyResult.Failed("the collection import did not finish")
        val state = holder.state.value
        if (state.status.orEmpty().startsWith(IMPORT_FAILED_PREFIX)) {
            return ConfigApplyResult.Failed("the collection pack could not be imported")
        }
        val after = owners.collectionPackStore.read(profileId).packs.size
        return if (after >= before) {
            ConfigApplyResult.Applied
        } else {
            ConfigApplyResult.Failed("the collection pack was not imported")
        }
    }

    private fun setCollectionRowVisible(
        profileId: String,
        id: CollectionRowId,
        visible: Boolean,
    ): ConfigApplyResult {
        val holder = owners.collectionRows
        selectPage(holder, id)?.let { return ConfigApplyResult.Failed(it) }
        val currentlyVisible = currentRowEntry(profileId, id)?.enabled ?: return ConfigApplyResult.Failed(
            "that collection row does not exist",
        )
        if (currentlyVisible != visible) {
            holder.toggleRow(id.rowKey)
            val reached = awaitUntil { currentRowEntry(profileId, id)?.enabled == visible }
            if (!reached) return ConfigApplyResult.Failed("the owner did not keep this value")
        }
        return if (currentRowEntry(profileId, id)?.enabled == visible) {
            ConfigApplyResult.Applied
        } else {
            ConfigApplyResult.Failed("the owner did not keep this value")
        }
    }

    private fun reorderCollectionRows(profileId: String, ids: List<String>): ConfigApplyResult {
        val decoded = ids.mapNotNull { collectionRowIdOf(it) }
        val first = decoded.firstOrNull() ?: return ConfigApplyResult.Failed("expected a collection row order")
        val holder = owners.collectionRows
        selectPage(holder, first)?.let { return ConfigApplyResult.Failed(it) }
        val working = currentRows(profileId, first).map { it.rowKey }.toMutableList()
        if (working.toSet() != decoded.map { it.rowKey }.toSet()) {
            return ConfigApplyResult.Failed("the order must name every row of the page once")
        }
        decoded.forEachIndexed { target, id ->

            while (true) {
                val index = working.indexOf(id.rowKey)
                if (index < 0) return ConfigApplyResult.Failed("that collection row does not exist")
                if (index <= target) break
                holder.moveRow(working[index], -1)
                working.add(target, working.removeAt(index))
            }
        }
        val expected = decoded.map { it.rowKey }

        val applied = awaitUntil { currentRows(profileId, first).map { it.rowKey } == expected }
        return if (applied) {
            ConfigApplyResult.Applied
        } else {
            ConfigApplyResult.Failed("the owner did not keep this order")
        }
    }

    private fun selectPage(
        holder: dev.infusion.feature.vertical.collections.CollectionRowSettingsStateHolder,
        id: CollectionRowId,
    ): String? {
        holder.selectPack(id.packId)
        if (!awaitUntil { holder.state.value.selectedPackId == id.packId }) {
            return "that collection pack is not imported"
        }
        holder.selectGroup(id.groupKey)
        if (!awaitUntil { holder.state.value.selectedGroupKey == id.groupKey }) {
            return "that collection group does not exist"
        }
        holder.selectTile(id.tileKey)
        if (!awaitUntil { holder.state.value.selectedTileKey == id.tileKey }) {
            return "that collection page does not exist"
        }
        return null
    }

    private fun currentRows(profileId: String, id: CollectionRowId): List<ConfigCollectionRowState> =
        rowsOf(profileId, id.packId, id.groupKey, id.tileKey)

    private fun currentRowEntry(profileId: String, id: CollectionRowId): ConfigCollectionRowState? =
        currentRows(profileId, id).firstOrNull { it.rowKey == id.rowKey }

    fun labelOf(operation: ConfigOperation): String? = when (operation.family) {
        ConfigFamily.ADDONS -> when (operation.kind) {
            ConfigOperationKind.ADD -> "Add add-on — ${originToken(textOf(operation))} (link hidden)"
            ConfigOperationKind.ENABLE -> if (operation.rowKey == ROW_CATALOG_DISCOVER) {
                "Show catalog in Discover"
            } else {
                "Enable add-on" + addonSuffix(operation)
            }
            ConfigOperationKind.DISABLE -> if (operation.rowKey == ROW_CATALOG_DISCOVER) {
                "Hide catalog from Discover"
            } else {
                "Disable add-on" + addonSuffix(operation)
            }
            ConfigOperationKind.REMOVE -> "Remove add-on" + addonSuffix(operation)
            ConfigOperationKind.REFRESH -> "Refresh add-on" + addonSuffix(operation)
            ConfigOperationKind.REORDER -> when (operation.rowKey) {
                ROW_INSTALLED_ORDER -> "Reorder installed add-ons"
                ROW_CATALOG_ORDER -> "Reorder add-on catalogs"
                else -> null
            }
            else -> null
        }
        ConfigFamily.HOME -> when (operation.kind) {
            ConfigOperationKind.ADD -> when (operation.rowKey) {
                ROW_WIDGET -> "Add Home row"
                ROW_HOME_CATALOG -> "Add catalog to Home"
                else -> null
            }
            ConfigOperationKind.REMOVE -> when (operation.rowKey) {
                ROW_WIDGET -> "Remove Home row"
                ROW_HOME_CATALOG -> "Remove catalog from Home"
                else -> null
            }
            ConfigOperationKind.ENABLE -> if (operation.rowKey == ROW_PROVIDER_HIDDEN) "Show provider on Home" else null
            ConfigOperationKind.DISABLE -> if (operation.rowKey == ROW_PROVIDER_HIDDEN) "Hide provider from Home" else null
            ConfigOperationKind.REORDER -> when (operation.rowKey) {
                ROW_WIDGET_ORDER -> "Reorder Home rows"
                ROW_HOME_CATALOG_ORDER -> "Reorder Home catalogs"
                else -> null
            }
            else -> null
        }
        ConfigFamily.COLLECTIONS -> when (operation.kind) {
            ConfigOperationKind.IMPORT -> "Import collection pack — ${originToken(textOf(operation))} (link hidden)"
            ConfigOperationKind.REMOVE -> "Delete collection pack"
            ConfigOperationKind.ENABLE -> "Show collection row"
            ConfigOperationKind.DISABLE -> "Hide collection row"
            ConfigOperationKind.REORDER -> "Reorder collection rows"
            else -> null
        }
        else -> null
    }

    private fun addonSuffix(operation: ConfigOperation): String {
        val viewer = owners.addons.viewerProfile()
        val primary = owners.addons.primaryProfile()
        val name = owners.addons.listVisibleAddons(viewer, primary)
            .firstOrNull { it.addonId == operation.targetId }?.displayName
        return if (name.isNullOrBlank()) "" else " — $name"
    }

    private fun originToken(text: String?): String {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return "link hidden"
        return runCatching {
            val uri = java.net.URI(raw)
            val scheme = uri.scheme?.lowercase() ?: return@runCatching "link hidden"
            val host = uri.host ?: return@runCatching "link hidden"
            if (host.isBlank()) return@runCatching "link hidden"
            val port = if (uri.port > 0) ":${uri.port}" else ""
            "$scheme://$host$port"
        }.getOrDefault("link hidden")
    }

    fun addonsState(): List<ConfigAddonState> {
        val viewer = owners.addons.viewerProfile()
        val primary = owners.addons.primaryProfile()
        val installed = owners.addons.listVisibleAddons(viewer, primary)
        val catalogs = owners.addons.catalogEntries(viewer, primary)
        return installed.mapIndexed { index, addon ->
            ConfigAddonState(
                addonId = addon.addonId,
                name = addon.displayName,
                enabled = addon.enabled,
                position = index,
                origin = originOf(addon),
                managed = addon.canManage,
                catalogs = catalogsOf(catalogs, addon.addonId),
            )
        }
    }

    private fun originOf(addon: AddonManagementItemUi): String = originToken(addon.origin)

    private fun catalogsOf(catalogs: List<AddonCatalogEntryUi>, addonId: String): List<ConfigAddonCatalogState> =
        catalogs.filter { it.addonId == addonId }
            .sortedWith(compareBy({ it.sortOrder ?: Int.MAX_VALUE }, { it.catalogId }))
            .mapIndexed { index, entry ->
                ConfigAddonCatalogState(
                    catalogType = entry.catalogType,
                    catalogId = entry.catalogId,
                    name = entry.displayName,
                    position = index,
                    visibleInDiscover = entry.visibleInDiscover,
                )
            }

    fun homeCatalogsState(): List<ConfigHomeCatalogState> {
        val profileId = owners.activeProfileId()
        val viewer = owners.homeCatalog.viewerProfile()
        val primary = owners.homeCatalog.primaryProfile()
        val available = owners.homeCatalog.availableCatalogs(viewer, primary)
        val selected = owners.homeCatalog.selectedCatalogs(profileId)
        val hidden = owners.homeCatalog.providerPreferences(profileId)
            .filter { it.hiddenFromHome }
            .map { it.addonId }
            .toSet()
        val rank = available.associate { option ->
            "${option.key.addonId}|${option.key.catalogType}|${option.key.catalogId}" to option
        }
        val selectionOrder = selected.withIndex().associate { it.value.shelfKey to it.index }
        return available.map { option ->
            val key = option.key
            ConfigHomeCatalogState(
                addonId = key.addonId,
                catalogType = key.catalogType,
                catalogId = key.catalogId,
                name = option.displayName,
                selected = selected.any { it == key },
                hiddenFromHome = key.addonId in hidden,
                position = selectionOrder[key.shelfKey] ?: (selected.size + rank.size),
            )
        }.sortedWith(compareBy({ if (it.selected) it.position else Int.MAX_VALUE }, { it.name }))
    }

    fun widgetsState(): List<ConfigWidgetState> {
        val profileId = owners.activeProfileId()
        return owners.widgets.widgetConfiguration(profileId).records.mapIndexed { index, record ->
            ConfigWidgetState(
                widgetId = record.widgetId,
                label = widgetLabel(record, index),
                position = index,
            )
        }
    }

    private fun widgetLabel(record: HomeWidgetRecord, index: Int): String {
        val title = record.displayTitle?.trim()
        if (!title.isNullOrEmpty()) return title
        return when (val source = record.source) {
            is dev.infusion.core.domain.home.HomeWidgetSourceRef.ExternalCatalog ->
                "${source.catalogType.replaceFirstChar { it.uppercase() }} — ${source.catalogId}"
            is dev.infusion.core.domain.home.HomeWidgetSourceRef.CollectionGroup -> "Collection page"
            else -> "Home row ${index + 1}"
        }
    }

    fun widgetDestinationsState(): List<ConfigWidgetDestinationState> =
        owners.widgets.destinations().map { destination ->
            ConfigWidgetDestinationState(destination.id, redactLabel(destination.displayName))
        }

    fun widgetCatalogsState(): List<ConfigWidgetCatalogState> {
        val viewer = owners.widgets.viewerProfile()
        val primary = owners.widgets.primaryProfile()
        return owners.widgets.availableExternalCatalogs(viewer, primary).map { option ->
            ConfigWidgetCatalogState(
                addonId = option.key.addonId,
                catalogType = option.key.catalogType,
                catalogId = option.key.catalogId,
                name = redactLabel(option.displayName),
            )
        }
    }

    fun widgetCollectionGroupsState(): List<ConfigWidgetCollectionGroupState> {
        val profileId = owners.activeProfileId()
        return owners.widgets.availableCollectionGroups(profileId).map { (ref, label) ->
            ConfigWidgetCollectionGroupState(
                packId = ref.packId,
                groupKey = ref.groupKey,
                name = redactLabel(label),
            )
        }
    }

    private fun redactLabel(label: String): String =
        if (label.contains("://")) originToken(label) else label

    fun packsState(): List<ConfigCollectionPackState> {
        val profileId = owners.activeProfileId()
        return owners.collectionPackStore.read(profileId).packs.map { pack ->
            ConfigCollectionPackState(
                packId = pack.packId,
                name = pack.name,
                origin = originToken(pack.provenance.sourceUrl),
                groupCount = pack.groups.size,
                tileCount = pack.groups.sumOf { it.tiles.size },
                placementCount = placementsFor(profileId, pack.packId),
            )
        }
    }

    fun pagesState(): List<ConfigCollectionPageState> {
        val profileId = owners.activeProfileId()
        val packs = owners.collectionPackStore.read(profileId).packs
        val pages = ArrayList<ConfigCollectionPageState>()
        for (pack in packs) {
            for (group in pack.groups) {
                for (tile in group.tiles) {
                    if (pages.size >= MAX_COLLECTION_PAGES) return pages
                    val rows = rowsOf(profileId, pack.packId, group.groupKey, tile.tileKey)
                    pages += ConfigCollectionPageState(
                        packId = pack.packId,
                        groupKey = group.groupKey,
                        tileKey = tile.tileKey,
                        title = group.title.ifBlank { tile.title.ifBlank { tile.tileKey } },
                        rows = rows.take(MAX_COLLECTION_ROWS_PER_PAGE),
                        truncated = rows.size > MAX_COLLECTION_ROWS_PER_PAGE,
                    )
                }
            }
        }
        return pages
    }

    private fun rowsOf(
        profileId: String,
        packId: String,
        groupKey: String,
        tileKey: String,
    ): List<ConfigCollectionRowState> {
        val tile = tileOf(profileId, packId, groupKey, tileKey) ?: return emptyList()
        val preferences = owners.collectionRowPreferences.readPage(profileId, packId, groupKey, tileKey)
        val ordered = dev.infusion.core.domain.collections.CollectionRowOrdering
            .orderedEntries(tile.rows.map { it.rowKey }, preferences)
        val byKey = tile.rows.distinctBy { it.rowKey }.associateBy { it.rowKey }
        return ordered.mapIndexedNotNull { index, entry ->
            val row = byKey[entry.rowKey] ?: return@mapIndexedNotNull null
            ConfigCollectionRowState(
                rowKey = entry.rowKey,
                title = row.title.ifBlank { row.definition.provider },
                enabled = entry.enabled,
                position = index,
            )
        }
    }

    private fun tileOf(profileId: String, packId: String, groupKey: String, tileKey: String): CollectionTile? =
        owners.collectionPackStore.read(profileId)
            .packs.firstOrNull { it.packId == packId }
            ?.groups?.firstOrNull { it.groupKey == groupKey }
            ?.tiles?.firstOrNull { it.tileKey == tileKey }

    private fun placementsFor(profileId: String, packId: String): Int =
        owners.widgets.widgetConfiguration(profileId).records.count { record ->
            val source = record.source
            source is dev.infusion.core.domain.home.HomeWidgetSourceRef.CollectionGroup &&
                source.packId == packId
        }

    fun revisionValues(): List<RevisionValue> {
        val values = ArrayList<RevisionValue>()
        val viewer = owners.addons.viewerProfile()
        val primary = owners.addons.primaryProfile()
        owners.addons.listVisibleAddons(viewer, primary).forEach { addon ->
            values += RevisionValue(ConfigFamily.ADDONS, "addon.${addon.addonId}", "${addon.enabled}")
        }
        owners.addons.catalogEntries(viewer, primary).forEach { entry ->
            values += RevisionValue(
                ConfigFamily.ADDONS,
                "catalog.${entry.addonId}.${entry.catalogType}.${entry.catalogId}",
                "${entry.sortOrder ?: -1}|${entry.visibleInDiscover}",
            )
        }
        val profileId = owners.activeProfileId()
        owners.homeCatalog.selectedCatalogs(profileId).forEachIndexed { index, key ->
            values += RevisionValue(
                ConfigFamily.HOME,
                "home_catalog.$index",
                "${key.addonId}|${key.catalogType}|${key.catalogId}",
            )
        }
        owners.homeCatalog.providerPreferences(profileId).forEach { provider ->
            values += RevisionValue(
                ConfigFamily.HOME,
                "home_provider.${provider.addonId}",
                "${provider.hiddenFromHome}",
            )
        }
        owners.widgets.widgetConfiguration(profileId).records.forEachIndexed { index, record ->
            values += RevisionValue(ConfigFamily.HOME, "widget.$index", record.widgetId)
        }
        owners.collectionPackStore.read(profileId).packs.forEach { pack ->
            values += RevisionValue(ConfigFamily.COLLECTIONS, "pack.${pack.packId}", "${pack.groups.size}")
        }
        for (page in pagesState()) {
            for (row in page.rows) {
                values += RevisionValue(
                    ConfigFamily.COLLECTIONS,
                    "collection_row.${page.packId}.${page.groupKey}.${page.tileKey}.${row.rowKey}",
                    "${row.enabled}|${row.position}",
                )
            }
        }
        return values
    }

    data class CollectionRowId(
        val packId: String,
        val groupKey: String,
        val tileKey: String,
        val rowKey: String,
    )

    private sealed interface WidgetSource {
        val destinationId: String

        data class ExternalCatalog(
            override val destinationId: String,
            val addonId: String,
            val catalogType: String,
            val catalogId: String,
        ) : WidgetSource

        data class CollectionGroup(
            override val destinationId: String,
            val packId: String,
            val groupKey: String,
        ) : WidgetSource
    }

    private fun matches(option: HomeCatalogOption, source: WidgetSource.ExternalCatalog): Boolean =
        option.key.addonId == source.addonId &&
            option.key.catalogType == source.catalogType &&
            option.key.catalogId == source.catalogId

    private fun catalogIdOf(encoded: String?): Triple<String, String, String>? {
        val parts = encoded?.split('|') ?: return null
        if (parts.size != 3 || parts.any { it.isBlank() }) return null
        return Triple(parts[0], parts[1], parts[2])
    }

    private fun collectionRowIdOf(encoded: String?): CollectionRowId? {
        val parts = encoded?.split('|') ?: return null
        if (parts.size != 4 || parts.any { it.isBlank() }) return null
        return CollectionRowId(parts[0], parts[1], parts[2], parts[3])
    }

    private fun widgetSourceOf(encoded: String?): WidgetSource? {
        val parts = encoded?.split('|') ?: return null
        return when {
            parts.size == 5 && parts[1] == "addon" && parts.none { it.isBlank() } ->
                WidgetSource.ExternalCatalog(parts[0], parts[2], parts[3], parts[4])
            parts.size == 5 && parts[1] == "collection" && parts.none { it.isBlank() } ->
                WidgetSource.CollectionGroup(parts[0], parts[2], parts[3])
            else -> null
        }
    }

    private fun textOf(operation: ConfigOperation): String? = (operation.value as? ConfigValue.Text)?.value

    private fun orderOf(operation: ConfigOperation): List<String>? =
        (operation.value as? ConfigValue.Order)?.ids?.takeIf { it.isNotEmpty() }

    private fun verifiedExists(present: () -> Boolean): ConfigApplyResult =
        if (present()) ConfigApplyResult.Applied else ConfigApplyResult.Failed("the owner did not apply this change")

    private companion object {
        const val ROW_ADDON: String = "addon"
        const val ROW_CATALOG_DISCOVER: String = "catalog_discover"
        const val ROW_INSTALLED_ORDER: String = "installed_order"
        const val ROW_CATALOG_ORDER: String = "catalog_order"
        const val ROW_WIDGET: String = "widget"
        const val ROW_WIDGET_ORDER: String = "widget_order"
        const val ROW_HOME_CATALOG: String = "home_catalog"
        const val ROW_HOME_CATALOG_ORDER: String = "home_catalog_order"
        const val ROW_PROVIDER_HIDDEN: String = "provider_hidden"
        const val ROW_PACK: String = "pack"
        const val ROW_COLLECTION_ROW: String = "row"
        const val ROW_COLLECTION_ROW_ORDER: String = "row_order"

        const val IMPORT_FAILED_PREFIX: String = "Import failed"
        const val MAX_COLLECTION_PAGES: Int = 64
        const val MAX_COLLECTION_ROWS_PER_PAGE: Int = 64
    }
}
