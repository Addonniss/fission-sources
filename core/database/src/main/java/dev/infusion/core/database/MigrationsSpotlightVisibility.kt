package dev.infusion.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object SpotlightVisibilityMigrations {

    const val DEFAULT_SHOW_SPOTLIGHT: Boolean = true

    val MIGRATION_22_23 = object : Migration(22, 23) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `home_presentation_preferences` " +
                    "ADD COLUMN `show_spotlight` INTEGER NOT NULL DEFAULT 1",
            )
        }
    }
}
