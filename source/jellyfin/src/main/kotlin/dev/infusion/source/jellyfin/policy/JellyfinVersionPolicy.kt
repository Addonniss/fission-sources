package dev.infusion.source.jellyfin.policy

object JellyfinVersionPolicy {

    val FLOOR = Version(10, 10, 7)

    val QUALIFIED_EXACT = setOf(
        Version(10, 10, 7),
        Version(10, 11, 11),
    )

    data class Version(
        val major: Int,
        val minor: Int,
        val patch: Int,

        val fourth: Int? = null,
        val prerelease: String? = null,

        val buildMetadata: String? = null,
    ) : Comparable<Version> {
        override fun compareTo(other: Version): Int {
            val base = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
            if (base != 0) return base

            return when {
                prerelease == null && other.prerelease == null -> 0
                prerelease == null -> 1
                other.prerelease == null -> -1
                else -> prerelease.compareTo(other.prerelease)
            }
        }

        val isCanonical: Boolean
            get() = fourth == null && prerelease == null && buildMetadata == null

        override fun toString(): String {
            val base = if (fourth != null) "$major.$minor.$patch.$fourth" else "$major.$minor.$patch"
            val withPrerelease = if (prerelease != null) "$base-$prerelease" else base
            return if (buildMetadata != null) "$withPrerelease+$buildMetadata" else withPrerelease
        }
    }

    enum class VersionQualification {

        QUALIFIED,

        OBSERVED,

        BELOW_FLOOR,

        UNPARSEABLE,
    }

    fun parse(raw: String?): Version? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim()

        val hasDash = cleaned.contains('-')
        val hasPlus = cleaned.contains('+')
        if (hasDash && cleaned.substringAfter('-', "").isBlank()) return null
        if (hasPlus && cleaned.substringAfter('+', "").isBlank()) return null

        val numeric = cleaned.substringBefore('-').substringBefore('+').trim()
        val parts = numeric.split('.').map { it.trim() }

        if (parts.size < 2 || parts.size > 4) return null
        if (parts.any { it.isEmpty() || it.toIntOrNull() == null || it.toIntOrNull()!! < 0 }) return null
        val numbers = parts.map { it.toInt() }
        val afterDash = cleaned.substringAfter('-', "").takeIf { it.isNotBlank() }
        val prerelease = afterDash?.substringBefore('+')?.takeIf { it.isNotBlank() }

        val buildMetadata = cleaned.substringAfter('+', "").takeIf { it.isNotBlank() }
        return Version(
            major = numbers[0],
            minor = numbers.getOrElse(1) { 0 },
            patch = numbers.getOrElse(2) { 0 },
            fourth = numbers.getOrElse(3) { 0 }.takeIf { numbers.size > 3 },
            prerelease = prerelease,
            buildMetadata = buildMetadata,
        )
    }

    fun qualify(raw: String?): VersionQualification {
        val version = parse(raw) ?: return VersionQualification.UNPARSEABLE
        return when {
            version.isCanonical && version in QUALIFIED_EXACT -> VersionQualification.QUALIFIED
            version < FLOOR -> VersionQualification.BELOW_FLOOR
            else -> VersionQualification.OBSERVED
        }
    }

    fun isQualifiedUpgrade(fromRaw: String?, toRaw: String?): Boolean {
        val from = parse(fromRaw) ?: return false
        val to = parse(toRaw) ?: return false
        return from.isCanonical && to.isCanonical &&
            from in QUALIFIED_EXACT && to in QUALIFIED_EXACT && to > from
    }
}
