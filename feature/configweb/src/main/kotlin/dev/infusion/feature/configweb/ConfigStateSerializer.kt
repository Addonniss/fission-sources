package dev.infusion.feature.configweb

import org.json.JSONArray
import org.json.JSONObject

object ConfigStateSerializer {

    fun toJson(state: ConfigState): JSONObject {
        val families = JSONArray()
        for (family in state.families) {
            families.put(familyJson(family))
        }
        val body = JSONObject()
            .put("status", "ok")
            .put("revision", state.revision)
            .put("families", families)
            .put("addons", addonsJson(state.addons))
            .put(
                "home",
                JSONObject()
                    .put("catalogs", homeCatalogsJson(state.homeCatalogs))
                    .put("widgets", widgetsJson(state.widgets))
                    .put("widgetLimit", state.widgetLimit)
                    .put("widgetDestinations", widgetDestinationsJson(state.widgetDestinations))
                    .put("widgetCatalogs", widgetCatalogsJson(state.widgetCatalogs))
                    .put("widgetCollectionGroups", widgetCollectionGroupsJson(state.widgetCollectionGroups)),
            )
            .put("ratings", JSONObject().put("omdbKeyConfigured", state.omdbKeyConfigured))
            .put(
                "collections",
                JSONObject()
                    .put("packs", packsJson(state.collectionPacks))
                    .put("pages", pagesJson(state.collectionPages)),
            )
        return body
    }

    private fun familyJson(family: ConfigFamilyState): JSONObject {
        val groups = JSONArray()
        for (group in family.groups) {
            val rows = JSONArray()
            for (row in group.rows) {
                rows.put(rowJson(row))
            }
            groups.put(JSONObject().put("name", group.name).put("rows", rows))
        }
        return JSONObject()
            .put("family", family.family.wireName)
            .put("label", family.family.label)
            .put("groups", groups)
    }

    private fun rowJson(row: ConfigRowState): JSONObject {
        val json = JSONObject()
            .put("key", row.key)
            .put("label", row.label)
            .put("group", row.group)
            .put("control", row.control.wireName)
            .put("value", row.value)
            .put("scope", row.scope.wireName)
        val choices = JSONArray()
        for (choice in row.choices) {
            choices.put(JSONObject().put("token", choice.token).put("label", choice.label))
        }
        json.put("choices", choices)
        row.parentKey?.let { json.put("parent", it) }
        json.put("active", row.active)
        row.min?.let { json.put("min", it) }
        row.max?.let { json.put("max", it) }
        row.help?.let { json.put("help", it) }
        return json
    }

    private fun addonsJson(addons: List<ConfigAddonState>): JSONArray {
        val array = JSONArray()
        for (addon in addons) {
            val catalogs = JSONArray()
            for (catalog in addon.catalogs) {
                catalogs.put(
                    JSONObject()
                        .put("catalogType", catalog.catalogType)
                        .put("catalogId", catalog.catalogId)
                        .put("name", catalog.name)
                        .put("position", catalog.position)
                        .put("visibleInDiscover", catalog.visibleInDiscover),
                )
            }
            array.put(
                JSONObject()
                    .put("addonId", addon.addonId)
                    .put("name", addon.name)
                    .put("enabled", addon.enabled)
                    .put("position", addon.position)
                    .put("origin", addon.origin)
                    .put("managed", addon.managed)
                    .put("catalogs", catalogs),
            )
        }
        return array
    }

    private fun homeCatalogsJson(catalogs: List<ConfigHomeCatalogState>): JSONArray {
        val array = JSONArray()
        for (catalog in catalogs) {
            array.put(
                JSONObject()
                    .put("addonId", catalog.addonId)
                    .put("catalogType", catalog.catalogType)
                    .put("catalogId", catalog.catalogId)
                    .put("name", catalog.name)
                    .put("selected", catalog.selected)
                    .put("hiddenFromHome", catalog.hiddenFromHome)
                    .put("position", catalog.position),
            )
        }
        return array
    }

    private fun widgetsJson(widgets: List<ConfigWidgetState>): JSONArray {
        val array = JSONArray()
        for (widget in widgets) {
            array.put(
                JSONObject()
                    .put("widgetId", widget.widgetId)
                    .put("label", widget.label)
                    .put("position", widget.position),
            )
        }
        return array
    }

    private fun widgetDestinationsJson(destinations: List<ConfigWidgetDestinationState>): JSONArray {
        val array = JSONArray()
        for (destination in destinations) {
            array.put(JSONObject().put("id", destination.id).put("label", destination.label))
        }
        return array
    }

    private fun widgetCatalogsJson(catalogs: List<ConfigWidgetCatalogState>): JSONArray {
        val array = JSONArray()
        for (catalog in catalogs) {
            array.put(
                JSONObject()
                    .put("addonId", catalog.addonId)
                    .put("catalogType", catalog.catalogType)
                    .put("catalogId", catalog.catalogId)
                    .put("name", catalog.name),
            )
        }
        return array
    }

    private fun widgetCollectionGroupsJson(groups: List<ConfigWidgetCollectionGroupState>): JSONArray {
        val array = JSONArray()
        for (group in groups) {
            array.put(
                JSONObject()
                    .put("packId", group.packId)
                    .put("groupKey", group.groupKey)
                    .put("name", group.name),
            )
        }
        return array
    }

    private fun packsJson(packs: List<ConfigCollectionPackState>): JSONArray {
        val array = JSONArray()
        for (pack in packs) {
            array.put(
                JSONObject()
                    .put("packId", pack.packId)
                    .put("name", pack.name)
                    .put("origin", pack.origin)
                    .put("groupCount", pack.groupCount)
                    .put("tileCount", pack.tileCount)
                    .put("placementCount", pack.placementCount),
            )
        }
        return array
    }

    private fun pagesJson(pages: List<ConfigCollectionPageState>): JSONArray {
        val array = JSONArray()
        for (page in pages) {
            val rows = JSONArray()
            for (row in page.rows) {
                rows.put(
                    JSONObject()
                        .put("rowKey", row.rowKey)
                        .put("title", row.title)
                        .put("enabled", row.enabled)
                        .put("position", row.position),
                )
            }
            array.put(
                JSONObject()
                    .put("packId", page.packId)
                    .put("groupKey", page.groupKey)
                    .put("tileKey", page.tileKey)
                    .put("title", page.title)
                    .put("rows", rows)
                    .put("truncated", page.truncated),
            )
        }
        return array
    }
}
