package dev.infusion.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object WatchHistoryMigrations {

    val MIGRATION_24_25 = object : Migration(24, 25) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `watch_states` ADD COLUMN `watched_at_ms` INTEGER")
        }
    }
}
