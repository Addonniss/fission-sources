package dev.infusion.core.update

import org.json.JSONArray
import org.json.JSONObject

data class RemoteRelease(
    val tagName: String?,
    val title: String?,
    val notesUrl: String?,
    val draft: Boolean,
    val prerelease: Boolean,
    val assets: List<RemoteAsset>,
)

data class RemoteAsset(
    val name: String,
    val sizeBytes: Long,
    val downloadUrl: String,
    val digest: String?,
)

object ReleaseJson {

    fun parseList(body: String): List<RemoteRelease>? {
        val array = try {
            JSONArray(body)
        } catch (_: Exception) {
            return null
        }
        val releases = ArrayList<RemoteRelease>(array.length())
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: return null
            releases += parseRelease(entry) ?: return null
        }
        return releases
    }

    fun parseSingle(body: String): RemoteRelease? {
        val entry = try {
            JSONObject(body)
        } catch (_: Exception) {
            return null
        }
        return parseRelease(entry)
    }

    private fun parseRelease(entry: JSONObject): RemoteRelease? {
        val assetsArray = entry.optJSONArray("assets") ?: return null
        val assets = ArrayList<RemoteAsset>(assetsArray.length())
        for (index in 0 until assetsArray.length()) {
            val asset = assetsArray.optJSONObject(index) ?: return null
            assets += parseAsset(asset) ?: return null
        }

        val draft = booleanOr(entry, "draft") ?: true
        val prerelease = booleanOr(entry, "prerelease") ?: false
        return RemoteRelease(
            tagName = stringOrNull(entry, "tag_name"),
            title = stringOrNull(entry, "name"),
            notesUrl = stringOrNull(entry, "html_url"),
            draft = draft,
            prerelease = prerelease,
            assets = assets,
        )
    }

    private fun parseAsset(asset: JSONObject): RemoteAsset? {
        val name = stringOrNull(asset, "name")?.takeIf { it.isNotEmpty() } ?: return null
        val downloadUrl = stringOrNull(asset, "browser_download_url")
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val size = asset.optLong("size", -1L)
        if (size < 0L) return null
        return RemoteAsset(
            name = name,
            sizeBytes = size,
            downloadUrl = downloadUrl,
            digest = stringOrNull(asset, "digest")?.takeIf { it.isNotEmpty() },
        )
    }

    private fun stringOrNull(entry: JSONObject, key: String): String? {
        if (!entry.has(key) || entry.isNull(key)) return null
        return entry.opt(key) as? String
    }

    private fun booleanOr(entry: JSONObject, key: String): Boolean? {
        if (!entry.has(key) || entry.isNull(key)) return null
        return when (val value = entry.opt(key)) {
            is Boolean -> value
            is String -> value.toBooleanStrictOrNull()
            else -> null
        }
    }
}
