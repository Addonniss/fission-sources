package dev.infusion.core.database.store

import dev.infusion.core.database.dao.ExternalIdDao
import dev.infusion.core.database.dao.IdentityMappingDao
import dev.infusion.core.database.dao.TitleDao
import dev.infusion.core.database.entity.ExternalIdEntity
import dev.infusion.core.database.entity.IdentityMappingEntity
import dev.infusion.core.database.entity.TitleEntity

internal const val BATCH_READ_CHUNK: Int = 400

internal fun TitleDao.listByIdsChunked(titleIds: Collection<String>): List<TitleEntity> {
    if (titleIds.isEmpty()) return emptyList()
    val ids = titleIds.distinct()
    val out = ArrayList<TitleEntity>(ids.size)
    for (chunk in ids.chunked(BATCH_READ_CHUNK)) out += listByIds(chunk)
    return out
}

internal fun ExternalIdDao.listByTitleIdsChunked(titleIds: Collection<String>): List<ExternalIdEntity> {
    if (titleIds.isEmpty()) return emptyList()
    val ids = titleIds.distinct()
    val out = ArrayList<ExternalIdEntity>(ids.size)
    for (chunk in ids.chunked(BATCH_READ_CHUNK)) out += listByTitleIds(chunk)
    return out
}

internal fun ExternalIdDao.listByAuthorityAndNativeIdsChunked(
    authority: String,
    nativeIds: Collection<String>,
): List<ExternalIdEntity> {
    val ids = nativeIds.distinct()
    if (ids.isEmpty()) return emptyList()
    val out = ArrayList<ExternalIdEntity>(ids.size)
    for (chunk in ids.chunked(BATCH_READ_CHUNK)) out += listByAuthorityAndNativeIds(authority, chunk)
    return out
}

internal fun IdentityMappingDao.activeByAuthorityAndExternalsChunked(
    authority: String,
    externalValues: Collection<String>,
): List<IdentityMappingEntity> {
    val values = externalValues.distinct()
    if (values.isEmpty()) return emptyList()
    val out = ArrayList<IdentityMappingEntity>(values.size)
    for (chunk in values.chunked(BATCH_READ_CHUNK)) out += activeByAuthorityAndExternals(authority, chunk)
    return out
}
