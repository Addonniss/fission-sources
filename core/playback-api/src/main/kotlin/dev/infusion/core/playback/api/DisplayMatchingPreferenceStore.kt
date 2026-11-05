package dev.infusion.core.playback.api

interface DisplayMatchingPreferenceStore {

    fun policy(): DisplayMatchingPolicy

    fun setPolicy(policy: DisplayMatchingPolicy)
}
