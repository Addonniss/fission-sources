package dev.infusion.tv.ui.screens

import androidx.annotation.DrawableRes
import dev.infusion.tv.R

@DrawableRes
internal fun settingsRailIcon(railKey: String): Int = when (railKey) {
    SettingsRailGeneral -> R.drawable.ic_settings_general
    SettingsRailPlayback -> R.drawable.ic_settings_playback
    SettingsRailHome -> R.drawable.ic_settings_home
    SettingsRailWidgets -> R.drawable.ic_settings_widgets
    SettingsRailProfiles -> R.drawable.ic_settings_profiles
    SettingsRailJellyfin -> R.drawable.ic_settings_jellyfin
    SettingsRailTracking -> R.drawable.ic_settings_tracking
    SettingsRailRatings -> R.drawable.ic_settings_ratings
    SettingsRailPairing -> R.drawable.ic_settings_pairing
    SettingsRailAddons -> R.drawable.ic_settings_addons
    SettingsRailAbout -> R.drawable.ic_settings_about
    else -> 0
}
