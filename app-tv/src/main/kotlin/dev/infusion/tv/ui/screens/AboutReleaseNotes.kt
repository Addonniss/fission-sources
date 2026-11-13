package dev.infusion.tv.ui.screens

internal data class AboutReleaseNote(

    val versionName: String,

    val title: String,

    val whatsNew: List<String>,

    val fixes: List<String>,
)

internal val AboutReleaseNotes: List<AboutReleaseNote> = generatedAboutReleaseNotes

internal const val AboutReleaseNotesUnavailable: String =
    "Release notes aren't available in this build. Published notes are on the releases page."
