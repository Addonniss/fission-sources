package dev.infusion.core.database.profile

import dev.infusion.core.database.entity.ProfileEntity
import dev.infusion.core.model.profile.Profile

object ProfileMapper {

    fun toDomain(entity: ProfileEntity): Profile = Profile(
        profileId = entity.profileId,
        name = entity.name,
        isDefault = entity.isDefault,
        profileIndex = entity.profileIndex,
        usesPrimarySources = entity.usesPrimarySources,

        pinProtected = entity.pinHash != null,
        createdAtMs = entity.createdAtMs,
    )
}

fun ProfileEntity.toDomain(): Profile = ProfileMapper.toDomain(this)
