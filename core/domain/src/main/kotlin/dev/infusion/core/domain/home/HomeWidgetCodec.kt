package dev.infusion.core.domain.home

import java.nio.charset.StandardCharsets

object HomeWidgetCodec {

    const val PAYLOAD_VERSION_TAG: String = "hw1"

    private const val FIELD_SEPARATOR = '|'
    private const val RECORD_SEPARATOR = ';'

    private const val UNRESERVED: String =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._-"

    private const val HEX: String = "0123456789ABCDEF"

    fun encode(
        records: List<HomeWidgetRecord>,
        unreadableRecords: List<String> = emptyList(),
    ): String {
        val encoded = ArrayList<String>(records.size + unreadableRecords.size)
        records.forEach { record -> encoded += encodeRecord(record) }

        encoded += unreadableRecords
        return PAYLOAD_VERSION_TAG + RECORD_SEPARATOR + encoded.joinToString(RECORD_SEPARATOR.toString())
    }

    fun decode(stored: String?): HomeWidgetConfiguration {
        if (stored == null) return HomeWidgetConfiguration.Absent
        val prefix = PAYLOAD_VERSION_TAG + RECORD_SEPARATOR
        if (!stored.startsWith(prefix)) {

            return HomeWidgetConfiguration(configured = true, unreadablePayload = stored)
        }
        val body = stored.substring(prefix.length)
        if (body.isEmpty()) {

            return HomeWidgetConfiguration(configured = true)
        }
        val records = ArrayList<HomeWidgetRecord>()
        val unreadable = ArrayList<String>()
        val seenWidgetIds = HashSet<String>()
        body.split(RECORD_SEPARATOR).forEach { raw ->
            val record = decodeRecord(raw, seenWidgetIds)
            if (record == null) {
                unreadable += raw
            } else {
                seenWidgetIds += record.widgetId
                records += record
            }
        }
        return HomeWidgetConfiguration(
            configured = true,
            records = records,
            unreadableRecords = unreadable,
        )
    }

    private fun encodeRecord(record: HomeWidgetRecord): String {
        val source = record.source
        val fields = when (source) {
            is HomeWidgetSourceRef.ExternalCatalog -> listOf(
                source.token,
                source.addonId,
                source.catalogType,
                source.catalogId,
            )
            is HomeWidgetSourceRef.PublicList -> listOf(
                source.token,
                source.providerId,
                source.owner ?: HomeWidgetSourceRef.PublicList.ABSENT_OWNER,
                source.listId,
            )

            is HomeWidgetSourceRef.CollectionGroup -> listOf(
                source.token,
                source.packId,
                source.groupKey,
            )
        }

        val title = HomeWidgetRecord.storableTitle(record.displayTitle)
        val all = listOf(record.recordVersion.toString(), record.widgetId, record.destinationId) + fields +
            listOfNotNull(title)
        require(!violatesBounds(record)) {
            "widget record is outside the storage bounds: $record"
        }
        return all.joinToString(FIELD_SEPARATOR.toString()) { escape(it) }
    }

    private fun decodeRecord(raw: String, seenWidgetIds: Set<String>): HomeWidgetRecord? {
        val fields = raw.split(FIELD_SEPARATOR)
        if (fields.size < 4) return null
        val version = fields[0].toIntOrNull() ?: return null
        if (version != HomeWidgetRecord.CURRENT_RECORD_VERSION) return null
        val widgetId = unescape(fields[1]) ?: return null
        val destinationId = unescape(fields[2]) ?: return null
        val sourceToken = unescape(fields[3]) ?: return null
        if (!withinIdBounds(widgetId) || !withinIdBounds(destinationId)) return null
        if (widgetId in seenWidgetIds) return null

        val baseFields = when (sourceToken) {
            HomeWidgetSourceRef.ExternalCatalog.TOKEN, HomeWidgetSourceRef.PublicList.TOKEN -> 7
            HomeWidgetSourceRef.CollectionGroup.TOKEN -> 6
            else -> return null
        }
        val source = when (HomeWidgetSourceRef.fromToken(sourceToken)) {
            HomeWidgetSourceRef.ExternalCatalog.TOKEN -> {
                if (fields.size != 7 && fields.size != 8) return null
                val addonId = unescape(fields[4]) ?: return null
                val catalogType = unescape(fields[5]) ?: return null
                val catalogId = unescape(fields[6]) ?: return null
                if (!withinTokenBounds(addonId) || !withinTokenBounds(catalogType) || !withinTokenBounds(catalogId)) {
                    return null
                }
                HomeWidgetSourceRef.ExternalCatalog(
                    addonId = addonId,
                    catalogType = catalogType,
                    catalogId = catalogId,
                )
            }
            HomeWidgetSourceRef.PublicList.TOKEN -> {
                if (fields.size != 7 && fields.size != 8) return null
                val providerId = unescape(fields[4]) ?: return null
                val owner = unescape(fields[5]) ?: return null
                val listId = unescape(fields[6]) ?: return null
                if (!withinTokenBounds(providerId) || !withinTokenBounds(owner) || !withinTokenBounds(listId)) {
                    return null
                }

                if (dev.infusion.core.domain.publist.PublicListProvider.fromId(providerId) == null) return null
                if (listId.isBlank()) return null
                HomeWidgetSourceRef.PublicList(
                    providerId = providerId,
                    listId = listId,
                    owner = owner.takeIf { it.isNotEmpty() },
                )
            }
            HomeWidgetSourceRef.CollectionGroup.TOKEN -> {
                if (fields.size != 6 && fields.size != 7) return null
                val packId = unescape(fields[4]) ?: return null
                val groupKey = unescape(fields[5]) ?: return null
                if (!withinTokenBounds(packId) || !withinTokenBounds(groupKey)) return null
                if (packId.isBlank() || groupKey.isBlank()) return null
                HomeWidgetSourceRef.CollectionGroup(packId = packId, groupKey = groupKey)
            }

            else -> return null
        }

        val displayTitle = if (fields.size == baseFields + 1) {
            val raw = unescape(fields[baseFields]) ?: return null
            val title = raw.takeIf { it.isNotEmpty() } ?: return null
            if (title.length > HomeWidgetLimits.MAX_DISPLAY_TITLE_LENGTH) return null
            title
        } else {
            null
        }
        return HomeWidgetRecord(
            widgetId = widgetId,
            destinationId = destinationId,
            source = source,
            displayTitle = displayTitle,
            recordVersion = version,
        )
    }

    private fun violatesBounds(record: HomeWidgetRecord): Boolean {
        if (!withinIdBounds(record.widgetId) || !withinIdBounds(record.destinationId)) return true

        val title = record.displayTitle
        if (title != null && HomeWidgetRecord.storableTitle(title) != title) return true
        val source = record.source
        return when (source) {
            is HomeWidgetSourceRef.ExternalCatalog ->
                !withinTokenBounds(source.addonId) ||
                    !withinTokenBounds(source.catalogType) ||
                    !withinTokenBounds(source.catalogId)
            is HomeWidgetSourceRef.PublicList ->
                !withinTokenBounds(source.providerId) ||
                    !withinTokenBounds(source.owner ?: HomeWidgetSourceRef.PublicList.ABSENT_OWNER) ||
                    !withinTokenBounds(source.listId) ||
                    source.listId.isBlank() ||
                    dev.infusion.core.domain.publist.PublicListProvider.fromId(source.providerId) == null
            is HomeWidgetSourceRef.CollectionGroup ->
                !withinTokenBounds(source.packId) ||
                    !withinTokenBounds(source.groupKey) ||
                    source.packId.isBlank() ||
                    source.groupKey.isBlank()
        }
    }

    private fun withinIdBounds(value: String): Boolean =
        value.isNotBlank() && value.length <= HomeWidgetLimits.MAX_ID_LENGTH

    private fun withinTokenBounds(value: String): Boolean =
        value.length <= HomeWidgetLimits.MAX_SOURCE_TOKEN_LENGTH

    private fun escape(value: String): String {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        val out = StringBuilder(bytes.size)
        bytes.forEach { byte ->
            val char = byte.toInt().toChar()
            if (byte >= 0 && char in UNRESERVED) {
                out.append(char)
            } else {
                val unsigned = byte.toInt() and 0xFF
                out.append('%').append(HEX[unsigned shr 4]).append(HEX[unsigned and 0x0F])
            }
        }
        return out.toString()
    }

    private fun unescape(value: String): String? {
        if ('%' !in value) return value
        val out = java.io.ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char != '%') {

                if (char.code > 0x7F) return null
                out.write(char.code)
                index += 1
                continue
            }
            if (index + 3 > value.length) return null
            val high = Character.digit(value[index + 1], 16)
            val low = Character.digit(value[index + 2], 16)
            if (high < 0 || low < 0) return null
            out.write((high shl 4) or low)
            index += 3
        }
        return String(out.toByteArray(), StandardCharsets.UTF_8)
    }
}
