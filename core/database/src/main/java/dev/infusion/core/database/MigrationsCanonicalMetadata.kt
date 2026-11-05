package dev.infusion.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object CanonicalMetadataMigrations {

    val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `metadata_cache` (
                    `namespace` TEXT NOT NULL,
                    `cache_key` TEXT NOT NULL,
                    `payload` TEXT NOT NULL,
                    `stored_at_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`namespace`, `cache_key`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_metadata_cache_stored_at_ms` " +
                    "ON `metadata_cache` (`stored_at_ms`)",
            )
        }
    }
}
