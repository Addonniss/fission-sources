package dev.infusion.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "home_catalog_preferences",
    primaryKeys = ["profile_id", "addon_id", "catalog_type", "catalog_id"],
    foreignKeys = [
        ForeignKey(
            entity = AddonEntity::class,
            parentColumns = ["addon_id"],
            childColumns = ["addon_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["profile_id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["addon_id"]),
        Index(value = ["profile_id", "sort_order"]),
    ],
)
data class HomeCatalogPreferenceEntity(
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "addon_id") val addonId: String,
    @ColumnInfo(name = "catalog_type") val catalogType: String,
    @ColumnInfo(name = "catalog_id") val catalogId: String,

    @ColumnInfo(name = "sort_order") val sortOrder: Int,
)

@Entity(
    tableName = "home_provider_preferences",
    primaryKeys = ["profile_id", "addon_id"],
    foreignKeys = [
        ForeignKey(
            entity = AddonEntity::class,
            parentColumns = ["addon_id"],
            childColumns = ["addon_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["profile_id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["addon_id"]),
        Index(value = ["profile_id", "sort_order"]),
    ],
)
data class HomeProviderPreferenceEntity(
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "addon_id") val addonId: String,

    @ColumnInfo(name = "hidden_from_home") val hiddenFromHome: Boolean,

    @ColumnInfo(name = "sort_order") val sortOrder: Int,
)

@Entity(
    tableName = "home_presentation_preferences",
    primaryKeys = ["profile_id"],
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["profile_id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class HomePresentationPreferenceEntity(
    @ColumnInfo(name = "profile_id") val profileId: String,

    @ColumnInfo(name = "show_provider_name") val showProviderName: Boolean,

    @ColumnInfo(name = "separate_continue_watching") val separateContinueWatching: Boolean,

    @ColumnInfo(name = "spotlight_auto_roll_seconds", defaultValue = "10")
    val spotlightAutoRollSeconds: Int,

    @ColumnInfo(name = "show_spotlight", defaultValue = "1")
    val showSpotlight: Boolean,

    @ColumnInfo(name = "spotlight_source", defaultValue = "auto")
    val spotlightSource: String,
)
