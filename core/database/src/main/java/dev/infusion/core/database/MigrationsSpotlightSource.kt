package dev.infusion.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object SpotlightSourceMigrations {

    const val DEFAULT_SPOTLIGHT_SOURCE: String = "auto"

    val MIGRATION_23_24 = object : Migration(23, 24) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `home_presentation_preferences` " +
                    "ADD COLUMN `spotlight_source` TEXT NOT NULL DEFAULT '$DEFAULT_SPOTLIGHT_SOURCE'",
            )
        }
    }
}
