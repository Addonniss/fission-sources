package dev.infusion.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.infusion.core.domain.home.HomePresentationDefaults

object SpotlightAutoRollMigrations {

    const val DEFAULT_INTERVAL_SECONDS: Int = HomePresentationDefaults.SPOTLIGHT_AUTO_ROLL_SECONDS

    val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `home_presentation_preferences` " +
                    "ADD COLUMN `spotlight_auto_roll_seconds` INTEGER NOT NULL DEFAULT 0",
            )
        }
    }

    val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `home_presentation_preferences_new` (" +
                    "`profile_id` TEXT NOT NULL, " +
                    "`show_provider_name` INTEGER NOT NULL, " +
                    "`separate_continue_watching` INTEGER NOT NULL, " +
                    "`spotlight_auto_roll_seconds` INTEGER NOT NULL DEFAULT " +
                    "$DEFAULT_INTERVAL_SECONDS, " +
                    "PRIMARY KEY(`profile_id`), " +
                    "FOREIGN KEY(`profile_id`) REFERENCES `profiles`(`profile_id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )",
            )

            db.execSQL(
                "INSERT INTO `home_presentation_preferences_new` " +
                    "(`profile_id`, `show_provider_name`, `separate_continue_watching`, " +
                    "`spotlight_auto_roll_seconds`) " +
                    "SELECT `profile_id`, `show_provider_name`, `separate_continue_watching`, " +
                    "CASE WHEN `spotlight_auto_roll_seconds` = 0 THEN $DEFAULT_INTERVAL_SECONDS " +
                    "ELSE `spotlight_auto_roll_seconds` END " +
                    "FROM `home_presentation_preferences`",
            )
            db.execSQL("DROP TABLE `home_presentation_preferences`")
            db.execSQL(
                "ALTER TABLE `home_presentation_preferences_new` " +
                    "RENAME TO `home_presentation_preferences`",
            )
        }
    }
}
