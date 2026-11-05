package dev.infusion.core.model

object ArtworkLocator {

    private const val VERSION = "i1"
    private const val CATALOG = "catalog"
    private const val CATALOG_TYPED = "catalogTyped"
    private const val ADDON = "addon"
    private const val ADDON_LANDSCAPE = "addonLandscape"
    private const val ADDON_STILL = "addonStill"
    private const val STILL = "still"
    private const val METADATA = "meta"

    private const val USER_AVATAR = "userAvatar"

    private const val COLLECTION = "collection"

    const val DEFAULT_CATALOG_TYPE = "Primary"

    const val COLLECTION_SLOT_COVER: String = "cover"
    const val COLLECTION_SLOT_LOGO: String = "logo"
    const val COLLECTION_SLOT_BACKDROP: String = "backdrop"
    const val COLLECTION_SLOT_FOCUS: String = "focus"

    const val CATALOG_BACKDROP_TYPE = "Backdrop"

    fun catalog(nativeItemId: String): String = encode(CATALOG, nativeItemId)

    fun catalog(nativeItemId: String, type: String): String =
        encode(CATALOG_TYPED, nativeItemId, type)

    fun addon(addonId: String, nativeItemId: String): String =
        encode(ADDON, addonId, nativeItemId)

    fun addonLandscape(addonId: String, nativeItemId: String): String =
        encode(ADDON_LANDSCAPE, addonId, nativeItemId)

    fun addonStill(addonId: String, nativeItemId: String): String =
        encode(ADDON_STILL, addonId, nativeItemId)

    fun still(nativeItemId: String): String = encode(STILL, nativeItemId)

    fun userAvatar(origin: String, userId: String, tag: String): String =
        encode(USER_AVATAR, origin, userId, tag)

    fun landscapeSlot(locator: String): String? = when (val target = decode(locator)) {
        is Target.Catalog -> catalog(target.nativeItemId, CATALOG_BACKDROP_TYPE)
        is Target.CatalogTyped ->
            if (target.type == CATALOG_BACKDROP_TYPE) locator
            else catalog(target.nativeItemId, CATALOG_BACKDROP_TYPE)
        is Target.Addon -> addonLandscape(target.addonId, target.nativeItemId)
        is Target.AddonLandscape -> locator
        is Target.Still,
        is Target.AddonStill,
        is Target.Metadata,
        is Target.CollectionArtwork,
        is Target.UserAvatar,
        null,
        -> null
    }

    fun metadata(provider: String, size: String, path: String): String =
        encode(METADATA, provider, size, path)

    fun collectionArtwork(
        packId: String,
        groupKey: String,
        tileKey: String,
        slot: String,
    ): String = encode(COLLECTION, packId, groupKey, tileKey, slot)

    sealed interface Target {
        data class Catalog(val nativeItemId: String) : Target
        data class CatalogTyped(val nativeItemId: String, val type: String) : Target
        data class Addon(val addonId: String, val nativeItemId: String) : Target
        data class AddonLandscape(val addonId: String, val nativeItemId: String) : Target

        data class AddonStill(val addonId: String, val nativeItemId: String) : Target
        data class Still(val nativeItemId: String) : Target
        data class Metadata(val provider: String, val size: String, val path: String) : Target

        data class UserAvatar(val origin: String, val userId: String, val tag: String) : Target
        data class CollectionArtwork(
            val packId: String,
            val groupKey: String,
            val tileKey: String,
            val slot: String,
        ) : Target
    }

    fun resolvableWithoutFetchedPage(locator: String): Boolean = when (decode(locator)) {
        is Target.Catalog,
        is Target.CatalogTyped,
        is Target.Still,
        is Target.Metadata,
        is Target.UserAvatar,
        is Target.CollectionArtwork,
        -> true
        is Target.Addon, is Target.AddonLandscape, is Target.AddonStill, null -> false
    }

    fun decode(locator: String): Target? {
        val parts = split(locator) ?: return null
        if (parts.size < 2 || parts[0] != VERSION) return null
        return when (parts[1]) {
            CATALOG -> parts.getOrNull(2)?.let { Target.Catalog(it) }
            CATALOG_TYPED -> {
                val id = parts.getOrNull(2)
                val type = parts.getOrNull(3)
                if (id == null || type == null) null else Target.CatalogTyped(id, type)
            }
            ADDON -> {
                val addonId = parts.getOrNull(2)
                val id = parts.getOrNull(3)
                if (addonId == null || id == null) null else Target.Addon(addonId, id)
            }
            ADDON_LANDSCAPE -> {
                val addonId = parts.getOrNull(2)
                val id = parts.getOrNull(3)
                if (addonId == null || id == null) {
                    null
                } else {
                    Target.AddonLandscape(addonId, id)
                }
            }
            ADDON_STILL -> {
                val addonId = parts.getOrNull(2)
                val id = parts.getOrNull(3)
                if (addonId == null || id == null) {
                    null
                } else {
                    Target.AddonStill(addonId, id)
                }
            }
            STILL -> parts.getOrNull(2)?.let { Target.Still(it) }
            USER_AVATAR -> {
                val origin = parts.getOrNull(2)
                val userId = parts.getOrNull(3)
                val tag = parts.getOrNull(4)
                if (origin == null || userId == null || tag == null) {
                    null
                } else {
                    Target.UserAvatar(origin, userId, tag)
                }
            }
            METADATA -> {
                val provider = parts.getOrNull(2)
                val size = parts.getOrNull(3)
                val path = parts.getOrNull(4)
                if (provider == null || size == null || path == null) {
                    null
                } else {
                    Target.Metadata(provider, size, path)
                }
            }
            COLLECTION -> {
                val packId = parts.getOrNull(2)
                val groupKey = parts.getOrNull(3)
                val tileKey = parts.getOrNull(4)
                val slot = parts.getOrNull(5)
                if (packId == null || groupKey == null || tileKey == null || slot == null) {
                    null
                } else {
                    Target.CollectionArtwork(packId, groupKey, tileKey, slot)
                }
            }
            else -> null
        }
    }

    private fun encode(kind: String, vararg fields: String): String =
        (listOf(VERSION, kind) + fields.toList()).joinToString(separator = "|") { escape(it) }

    private fun escape(field: String): String = buildString(field.length) {
        for (c in field) {
            if (c == '\\' || c == '|') append('\\')
            append(c)
        }
    }

    private fun split(locator: String): List<String>? {
        val parts = mutableListOf<String>()
        val field = StringBuilder()
        var i = 0
        while (i < locator.length) {
            when (val c = locator[i]) {
                '\\' -> {
                    i++
                    if (i >= locator.length) return null
                    field.append(locator[i])
                }
                '|' -> {
                    parts += field.toString()
                    field.clear()
                }
                else -> field.append(c)
            }
            i++
        }
        parts += field.toString()
        return parts
    }
}
