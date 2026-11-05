package dev.infusion.core.database.profile

import android.content.Context
import android.content.SharedPreferences
import dev.infusion.core.playback.api.SubtitleAppearance
import dev.infusion.core.playback.api.SubtitleAppearancePreferenceStore

class SharedPreferencesSubtitleAppearancePreferenceStore(
    context: Context,
    prefsName: String = PREFS_NAME,
) : SubtitleAppearancePreferenceStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun appearanceFor(profileId: String): SubtitleAppearance = SubtitleAppearance.fromStored(
        mode = prefs.getString(modeKey(profileId), null),
        size = prefs.getString(sizeKey(profileId), null),
        font = prefs.getString(fontKey(profileId), null),
        textColor = prefs.getString(textColorKey(profileId), null),
        background = prefs.getString(backgroundKey(profileId), null),
        backgroundOpacity = prefs.getString(backgroundOpacityKey(profileId), null),
        edge = prefs.getString(edgeKey(profileId), null),
        position = prefs.getString(positionKey(profileId), null),
    )

    override fun setAppearance(profileId: String, appearance: SubtitleAppearance) {
        prefs.edit()
            .putString(modeKey(profileId), appearance.mode.name)
            .putString(sizeKey(profileId), appearance.size.name)
            .putString(fontKey(profileId), appearance.font.name)
            .putString(textColorKey(profileId), appearance.textColor.name)
            .putString(backgroundKey(profileId), appearance.background.name)
            .putString(backgroundOpacityKey(profileId), appearance.backgroundOpacity.name)
            .putString(edgeKey(profileId), appearance.edge.name)
            .putString(positionKey(profileId), appearance.position.name)
            .apply()
    }

    override fun clearForProfile(profileId: String) {

        prefs.edit()
            .remove(modeKey(profileId))
            .remove(sizeKey(profileId))
            .remove(fontKey(profileId))
            .remove(textColorKey(profileId))
            .remove(backgroundKey(profileId))
            .remove(backgroundOpacityKey(profileId))
            .remove(edgeKey(profileId))
            .remove(positionKey(profileId))
            .apply()
    }

    private fun modeKey(profileId: String): String = "$MODE_PREFIX$profileId"

    private fun sizeKey(profileId: String): String = "$SIZE_PREFIX$profileId"

    private fun fontKey(profileId: String): String = "$FONT_PREFIX$profileId"

    private fun textColorKey(profileId: String): String = "$TEXT_COLOR_PREFIX$profileId"

    private fun backgroundKey(profileId: String): String = "$BACKGROUND_PREFIX$profileId"

    private fun backgroundOpacityKey(profileId: String): String = "$BACKGROUND_OPACITY_PREFIX$profileId"

    private fun edgeKey(profileId: String): String = "$EDGE_PREFIX$profileId"

    private fun positionKey(profileId: String): String = "$POSITION_PREFIX$profileId"

    private companion object {
        const val PREFS_NAME = "infusion_subtitle_appearance_preferences"
        const val MODE_PREFIX = "subtitle_appearance_mode_v1:"
        const val SIZE_PREFIX = "subtitle_appearance_size_v1:"
        const val FONT_PREFIX = "subtitle_appearance_font_v1:"
        const val TEXT_COLOR_PREFIX = "subtitle_appearance_text_color_v1:"
        const val BACKGROUND_PREFIX = "subtitle_appearance_background_v1:"
        const val BACKGROUND_OPACITY_PREFIX = "subtitle_appearance_background_opacity_v1:"
        const val EDGE_PREFIX = "subtitle_appearance_edge_v1:"
        const val POSITION_PREFIX = "subtitle_appearance_position_v1:"
    }
}
