package dev.infusion.source.acquisition

import dev.infusion.core.domain.acquisition.AcquisitionQualityProfile
import dev.infusion.core.domain.acquisition.AcquisitionRootFolder
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal object AcquisitionWire {

    class MalformedBodyException(message: String, cause: Throwable? = null) : Exception(message, cause)

    fun parseVersion(body: String): String? {
        val json = objectOf(body, "system/status")
        return json.optString("version", "").trim().takeIf { it.isNotEmpty() }
    }

    fun parseRootFolders(body: String): List<AcquisitionRootFolder> {
        val arr = arrayOf(body, "rootfolder")
        val out = ArrayList<AcquisitionRootFolder>(arr.length())
        for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i)
                ?: throw MalformedBodyException("rootfolder[$i] is not an object")
            val path = entry.optString("path", "").trim()
            if (path.isEmpty()) throw MalformedBodyException("rootfolder[$i] has no path")
            out += AcquisitionRootFolder(path)
        }
        return out
    }

    fun parseQualityProfiles(body: String): List<AcquisitionQualityProfile> {
        val arr = arrayOf(body, "qualityprofile")
        val out = ArrayList<AcquisitionQualityProfile>(arr.length())
        for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i)
                ?: throw MalformedBodyException("qualityprofile[$i] is not an object")
            val id = entry.optInt("id", Int.MIN_VALUE)
            val name = entry.optString("name", "").trim()
            if (id == Int.MIN_VALUE || id <= 0) throw MalformedBodyException("qualityprofile[$i] has no valid id")
            if (name.isEmpty()) throw MalformedBodyException("qualityprofile[$i] has no name")
            out += AcquisitionQualityProfile(id, name)
        }
        return out
    }

    private fun objectOf(body: String, label: String): JSONObject {
        val text = body.trim()
        if (text.isEmpty()) throw MalformedBodyException("empty $label body")
        if (!text.startsWith("{")) throw MalformedBodyException("expected a JSON object for $label")
        return try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw MalformedBodyException("malformed JSON object for $label", e)
        }
    }

    private fun arrayOf(body: String, label: String): JSONArray {
        val text = body.trim()
        if (text.isEmpty()) throw MalformedBodyException("empty $label body")
        if (!text.startsWith("[")) throw MalformedBodyException("expected a JSON array for $label")
        return try {
            JSONArray(text)
        } catch (e: JSONException) {
            throw MalformedBodyException("malformed JSON array for $label", e)
        }
    }
}
