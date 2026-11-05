package dev.infusion.core.database.profile

import android.content.Context
import dev.infusion.core.domain.profile.ProfileScope
import dev.infusion.core.domain.ratings.RatingsPreferences
import dev.infusion.core.domain.ratings.RatingsPreferencesStore
import dev.infusion.core.model.metadata.RatingAppearance
import dev.infusion.core.model.metadata.RatingMetric
import dev.infusion.core.model.metadata.RatingSupplier

class SharedPreferencesRatingsPreferencesStore(
    context: Context,
    private val profileScope: ProfileScope,
    prefsName: String = PREFS_NAME,
) : RatingsPreferencesStore {

    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun current(): RatingsPreferences = currentFor(profileScope.activeProfileId)

    private fun currentFor(profileId: String): RatingsPreferences {
        val order = decodeSuppliers(prefs.getString(key(profileId, KEY_ORDER), null))
            .takeIf { it.isNotEmpty() }
            ?: RatingSupplier.DefaultOrder
        val disabledRaw = prefs.getString(key(profileId, KEY_DISABLED), null)

        val disabled = if (disabledRaw == null) {
            RatingSupplier.DefaultDisabled
        } else {
            decodeSuppliers(disabledRaw).toSet()
        }
        val metricsRaw = prefs.getString(key(profileId, KEY_METRICS), null)

        val metrics = if (metricsRaw == null) RatingMetric.DefaultDisplayed else decodeMetrics(metricsRaw)

        val orderRaw = prefs.getString(key(profileId, KEY_EPISODE_ORDER), null)
        val enabledRaw = prefs.getString(key(profileId, KEY_EPISODE_ENABLED), null)
        val episodeOrder: List<RatingMetric>
        val episodeEnabled: Set<RatingMetric>
        if (orderRaw != null || enabledRaw != null) {
            episodeOrder = normalizeEpisodeOrder(decodeMetricList(orderRaw))
            episodeEnabled = decodeMetrics(enabledRaw).filter { it in RatingMetric.SortableEpisodeSources }.toSet()
        } else {
            episodeOrder = adaptEpisodeOrder(profileId)
            episodeEnabled = adaptEpisodeEnabled(profileId)
        }
        val appearance = prefs.getString(key(profileId, KEY_APPEARANCE), null)
            ?.let { stored -> RatingAppearance.entries.firstOrNull { it.name == stored } }
            ?: RatingAppearance.Default
        return RatingsPreferences(
            supplierOrder = order,
            enabledSuppliers = RatingSupplier.Configurable.filterNot { it in disabled }.toSet(),
            displayedMetrics = metrics,
            appearance = appearance,
            episodeSourceOrder = episodeOrder,
            episodeEnabledMetrics = episodeEnabled,
        )
    }

    override fun record(preferences: RatingsPreferences) {
        val profileId = profileScope.activeProfileId
        val allConfigurable = RatingSupplier.Configurable.toSet()
        val disabled = allConfigurable.filterNot { it in preferences.enabledSuppliers }
        prefs.edit()
            .putString(key(profileId, KEY_ORDER), preferences.supplierOrder.joinToString(",") { it.id })
            .putString(key(profileId, KEY_DISABLED), disabled.joinToString(",") { it.id })
            .putString(
                key(profileId, KEY_METRICS),
                RatingMetric.entries.filter { it in preferences.displayedMetrics }.joinToString(",") { it.id },
            )
            .putString(key(profileId, KEY_APPEARANCE), preferences.appearance.name)
            .putString(
                key(profileId, KEY_EPISODE_ORDER),
                normalizeEpisodeOrder(preferences.episodeSourceOrder).joinToString(",") { it.id },
            )
            .putString(
                key(profileId, KEY_EPISODE_ENABLED),
                RatingMetric.SortableEpisodeSources
                    .filter { it in preferences.episodeEnabledMetrics }
                    .joinToString(",") { it.id },
            )
            .apply()
    }

    override fun clearProfile(profileId: String) {
        prefs.edit().apply {
            KEYS.forEach { remove(key(profileId, it)) }
        }.apply()
    }

    override fun clearAllProfiles() {
        val prefix = "$PREFS_KEY_PREFIX."
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith(prefix) }.forEach { remove(it) }
        }.apply()
    }

    private fun key(profileId: String, suffix: String) = "$PREFS_KEY_PREFIX.$profileId.$suffix"

    private fun decodeSuppliers(raw: String?): List<RatingSupplier> =
        raw.orEmpty().split(',')
            .mapNotNull { RatingSupplier.fromId(it) }
            .distinct()

    private fun decodeMetrics(raw: String?): Set<RatingMetric> =
        raw.orEmpty().split(',')
            .mapNotNull { RatingMetric.fromId(it) }
            .toSet()

    private fun decodeMetricList(raw: String?): List<RatingMetric> =
        raw.orEmpty().split(',')
            .mapNotNull { RatingMetric.fromId(it) }
            .distinct()

    private fun normalizeEpisodeOrder(recorded: List<RatingMetric>): List<RatingMetric> {
        val selectable = RatingMetric.SortableEpisodeSources
        val seen = LinkedHashSet<RatingMetric>()
        recorded.forEach { if (it in selectable) seen.add(it) }
        selectable.forEach { seen.add(it) }
        return seen.toList()
    }

    private fun adaptEpisodeEnabled(profileId: String): Set<RatingMetric> {
        val slotsRaw = prefs.getString(key(profileId, KEY_EPISODE_SLOTS), null)
        if (slotsRaw != null) {
            return slotsRaw.split(',')
                .mapNotNull { RatingMetric.fromId(it) }
                .filter { it in RatingMetric.SortableEpisodeSources }
                .distinct()
                .toSet()
        }
        val legacyRaw = prefs.getString(key(profileId, KEY_EPISODE_METRICS), null) ?: return RatingMetric.DefaultEpisode
        return decodeMetrics(legacyRaw).filter { it in RatingMetric.SortableEpisodeSources }.toSet()
    }

    private fun adaptEpisodeOrder(profileId: String): List<RatingMetric> {
        val slotsRaw = prefs.getString(key(profileId, KEY_EPISODE_SLOTS), null)
        val previouslyChosen = if (slotsRaw != null) {
            slotsRaw.split(',')
                .mapNotNull { RatingMetric.fromId(it) }
                .filter { it in RatingMetric.SortableEpisodeSources }
                .distinct()
        } else {
            val legacyRaw = prefs.getString(key(profileId, KEY_EPISODE_METRICS), null)
            if (legacyRaw == null) {
                return RatingMetric.DefaultEpisodeOrder
            }
            decodeMetricList(legacyRaw).filter { it in RatingMetric.SortableEpisodeSources }
        }
        return normalizeEpisodeOrder(previouslyChosen)
    }

    companion object {

        const val PREFS_NAME: String = "infusion_ratings_preferences"

        private const val PREFS_KEY_PREFIX = "ratings"

        private const val KEY_ORDER = "supplier-order"
        private const val KEY_DISABLED = "suppliers-disabled"
        private const val KEY_METRICS = "displayed-metrics"
        private const val KEY_APPEARANCE = "appearance"

        private const val KEY_EPISODE_ORDER = "episode-source-order"

        private const val KEY_EPISODE_ENABLED = "episode-sources-enabled"

        private const val KEY_EPISODE_SLOTS = "episode-rating-slots"

        private const val KEY_EPISODE_METRICS = "episode-metrics"

        private val KEYS = listOf(
            KEY_ORDER, KEY_DISABLED, KEY_METRICS, KEY_APPEARANCE,
            KEY_EPISODE_ORDER, KEY_EPISODE_ENABLED, KEY_EPISODE_SLOTS, KEY_EPISODE_METRICS,
        )
    }
}
