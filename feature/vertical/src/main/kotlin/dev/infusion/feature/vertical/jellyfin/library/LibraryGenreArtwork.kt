package dev.infusion.feature.vertical.jellyfin.library

object LibraryGenreArtwork {

    const val CANDIDATES: Int = 8

    fun assign(
        genreIdsInPageOrder: List<String>,
        candidates: Map<String, List<String>>,
    ): Map<String, String> {
        val used = HashSet<String>()
        val assigned = LinkedHashMap<String, String>(genreIdsInPageOrder.size)
        for (genreId in genreIdsInPageOrder) {
            val slice = candidates[genreId].orEmpty().filter { it.isNotBlank() }.distinct()
            if (slice.isEmpty()) continue
            val start = fnv1a(genreId) % slice.size
            var chosen: String? = null
            for (step in slice.indices) {
                val candidate = slice[(start + step) % slice.size]
                if (used.add(candidate)) {
                    chosen = candidate
                    break
                }
            }

            val resolved = chosen ?: slice[start]
            used.add(resolved)
            assigned[genreId] = resolved
        }
        return assigned
    }

    internal fun fnv1a(text: String): Int {
        var hash = -0x7ee3623b
        for (byte in text.encodeToByteArray()) {
            hash = hash xor (byte.toInt() and 0xFF)
            hash *= 0x01000193
        }
        return hash and 0x7FFFFFFF
    }
}
