package dev.infusion.feature.vertical.sourcepicker

fun providerInstanceLabels(entries: List<Pair<String, String>>): Map<String, String> {
    if (entries.isEmpty()) return emptyMap()
    val counts = HashMap<String, Int>()
    for ((_, rawName) in entries) counts[rawName] = (counts[rawName] ?: 0) + 1
    val ordinals = HashMap<String, Int>()
    val labels = LinkedHashMap<String, String>(entries.size)
    for ((key, rawName) in entries) {
        if (counts[rawName] == 1) {
            labels[key] = rawName
            continue
        }
        val ordinal = (ordinals[rawName] ?: 0) + 1
        ordinals[rawName] = ordinal
        labels[key] = "$rawName $ordinal"
    }
    return labels
}
