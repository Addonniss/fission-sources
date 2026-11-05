package dev.infusion.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.infusion.core.database.dao.AddonDao
import dev.infusion.core.database.dao.AvailabilityDao
import dev.infusion.core.database.dao.CandidateHandoffDao
import dev.infusion.core.database.dao.CheckpointDao
import dev.infusion.core.database.dao.EditionDao
import dev.infusion.core.database.dao.ExternalIdDao
import dev.infusion.core.database.dao.IdentityMappingDao
import dev.infusion.core.database.dao.JellyfinSourceStateDao
import dev.infusion.core.database.dao.HomeCatalogPreferenceDao
import dev.infusion.core.database.dao.HomePresentationPreferenceDao
import dev.infusion.core.database.dao.HomeProviderPreferenceDao
import dev.infusion.core.database.dao.LocalAssetDao
import dev.infusion.core.database.dao.MetadataCacheDao
import dev.infusion.core.database.dao.OutboxDao
import dev.infusion.core.database.dao.PlayableCandidateDao
import dev.infusion.core.database.dao.PlaybackAttemptDao
import dev.infusion.core.database.dao.PlaySessionContextDao
import dev.infusion.core.database.dao.ProfileDao
import dev.infusion.core.database.dao.RetirementIntentDao
import dev.infusion.core.database.dao.SecretReferenceDao
import dev.infusion.core.database.dao.ServerItemDao
import dev.infusion.core.database.dao.ServerMediaVersionDao
import dev.infusion.core.database.dao.SourceDao
import dev.infusion.core.database.dao.TitleDao
import dev.infusion.core.database.dao.TitleLinkDao
import dev.infusion.core.database.dao.WatchEventDao
import dev.infusion.core.database.dao.WatchStateDao
import dev.infusion.core.database.entity.AddonCatalogPreferenceEntity
import dev.infusion.core.database.entity.AddonEntity
import dev.infusion.core.database.entity.AddonHealthObservationEntity
import dev.infusion.core.database.entity.AvailabilityEntity
import dev.infusion.core.database.entity.CandidateHandoffEntity
import dev.infusion.core.database.entity.CheckpointEntity
import dev.infusion.core.database.entity.EditionEntity
import dev.infusion.core.database.entity.ExternalIdEntity
import dev.infusion.core.database.entity.IdentityMappingEntity
import dev.infusion.core.database.entity.JellyfinSourceStateEntity
import dev.infusion.core.database.entity.HomeCatalogPreferenceEntity
import dev.infusion.core.database.entity.HomePresentationPreferenceEntity
import dev.infusion.core.database.entity.HomeProviderPreferenceEntity
import dev.infusion.core.database.entity.LocalAssetEntity
import dev.infusion.core.database.entity.MetadataCacheEntity
import dev.infusion.core.database.entity.OutboxEntity
import dev.infusion.core.database.entity.PlayableCandidateEntity
import dev.infusion.core.database.entity.PlaybackAttemptEntity
import dev.infusion.core.database.entity.PlaySessionContextEntity
import dev.infusion.core.database.entity.ProfileEntity
import dev.infusion.core.database.entity.RetirementIntentEntity
import dev.infusion.core.database.entity.SecretReferenceEntity
import dev.infusion.core.database.entity.ServerItemEntity
import dev.infusion.core.database.entity.ServerMediaVersionEntity
import dev.infusion.core.database.entity.SourceEntity
import dev.infusion.core.database.entity.TitleEntity
import dev.infusion.core.database.entity.TitleLinkEntity
import dev.infusion.core.database.entity.WatchEventEntity
import dev.infusion.core.database.entity.WatchStateEntity

@Database(
    entities = [
        ProfileEntity::class,
        TitleEntity::class,
        TitleLinkEntity::class,
        EditionEntity::class,
        ExternalIdEntity::class,
        IdentityMappingEntity::class,
        SourceEntity::class,
        SecretReferenceEntity::class,
        ServerItemEntity::class,
        ServerMediaVersionEntity::class,
        LocalAssetEntity::class,
        AvailabilityEntity::class,
        CandidateHandoffEntity::class,
        PlayableCandidateEntity::class,
        WatchEventEntity::class,
        WatchStateEntity::class,
        PlaybackAttemptEntity::class,
        OutboxEntity::class,
        CheckpointEntity::class,
        PlaySessionContextEntity::class,
        JellyfinSourceStateEntity::class,
        RetirementIntentEntity::class,
        AddonEntity::class,
        AddonCatalogPreferenceEntity::class,
        AddonHealthObservationEntity::class,
        HomeCatalogPreferenceEntity::class,
        HomeProviderPreferenceEntity::class,
        HomePresentationPreferenceEntity::class,
        MetadataCacheEntity::class,
    ],
    version = 24,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class InfusionDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun titleDao(): TitleDao
    abstract fun titleLinkDao(): TitleLinkDao
    abstract fun editionDao(): EditionDao
    abstract fun externalIdDao(): ExternalIdDao
    abstract fun identityMappingDao(): IdentityMappingDao
    abstract fun sourceDao(): SourceDao
    abstract fun secretReferenceDao(): SecretReferenceDao
    abstract fun serverItemDao(): ServerItemDao
    abstract fun serverMediaVersionDao(): ServerMediaVersionDao
    abstract fun localAssetDao(): LocalAssetDao
    abstract fun availabilityDao(): AvailabilityDao
    abstract fun candidateHandoffDao(): CandidateHandoffDao
    abstract fun playableCandidateDao(): PlayableCandidateDao
    abstract fun watchEventDao(): WatchEventDao
    abstract fun watchStateDao(): WatchStateDao
    abstract fun playbackAttemptDao(): PlaybackAttemptDao
    abstract fun outboxDao(): OutboxDao
    abstract fun checkpointDao(): CheckpointDao
    abstract fun retirementIntentDao(): RetirementIntentDao
    abstract fun playSessionContextDao(): PlaySessionContextDao
    abstract fun jellyfinSourceStateDao(): JellyfinSourceStateDao
    abstract fun addonDao(): AddonDao
    abstract fun homeCatalogPreferenceDao(): HomeCatalogPreferenceDao
    abstract fun homeProviderPreferenceDao(): HomeProviderPreferenceDao
    abstract fun homePresentationPreferenceDao(): HomePresentationPreferenceDao

    abstract fun metadataCacheDao(): MetadataCacheDao

    companion object {
        const val DEFAULT_DATABASE_NAME = "infusion.db"

        fun create(context: Context, name: String = DEFAULT_DATABASE_NAME): InfusionDatabase =
            Room.databaseBuilder(context, InfusionDatabase::class.java, name)
                .addMigrations(
                    Migrations.MIGRATION_1_2,
                    Migrations.MIGRATION_2_3,
                    Migrations.MIGRATION_3_4,
                    Migrations.MIGRATION_4_5,
                    Migrations.MIGRATION_5_6,
                    Migrations.MIGRATION_6_7,
                    Migrations.MIGRATION_7_8,
                    Migrations.MIGRATION_8_9,
                    Migrations.MIGRATION_9_10,
                    Migrations.MIGRATION_10_11,
                    Migrations.MIGRATION_11_12,
                    Migrations.MIGRATION_12_13,
                    Migrations.MIGRATION_13_14,

                    CanonicalMetadataMigrations.MIGRATION_14_15,

                    SpotlightAutoRollMigrations.MIGRATION_15_16,
                    SpotlightAutoRollMigrations.MIGRATION_16_17,

                    MigrationsCanonicalIdentity.MIGRATION_17_18,

                    MigrationsWatchlist.MIGRATION_18_19,

                    Migrations.MIGRATION_19_20,

                    Migrations.MIGRATION_20_21,

                    Migrations.MIGRATION_21_22,

                    SpotlightVisibilityMigrations.MIGRATION_22_23,

                    SpotlightSourceMigrations.MIGRATION_23_24,
                )
                .build()

        fun createInMemory(context: Context): InfusionDatabase =
            Room.inMemoryDatabaseBuilder(context, InfusionDatabase::class.java).build()
    }
}
