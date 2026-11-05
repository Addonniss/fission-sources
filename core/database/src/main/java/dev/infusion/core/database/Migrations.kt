package dev.infusion.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migrations {

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE watch_events ADD COLUMN observed_at_ms INTEGER")
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `play_session_contexts` (" +
                    "`context_id` TEXT NOT NULL, " +
                    "`source_id` TEXT NOT NULL, " +
                    "`watch_session_id` TEXT NOT NULL, " +
                    "`item_id` TEXT NOT NULL, " +
                    "`media_source_id` TEXT NOT NULL, " +
                    "`play_session_id` TEXT NOT NULL, " +
                    "`created_at_ms` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`context_id`), " +
                    "FOREIGN KEY(`source_id`) REFERENCES `sources`(`source_id`) ON UPDATE NO ACTION ON DELETE CASCADE" +
                    ")",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_play_session_contexts_source_id_watch_session_id` " +
                    "ON `play_session_contexts` (`source_id`, `watch_session_id`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_play_session_contexts_source_id` " +
                    "ON `play_session_contexts` (`source_id`)",
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `jellyfin_source_state` (" +
                    "`source_id` TEXT NOT NULL, " +
                    "`origin` TEXT NOT NULL, " +
                    "`username` TEXT NOT NULL, " +
                    "`user_id` TEXT NOT NULL, " +
                    "`server_id` TEXT NOT NULL, " +
                    "`device_id` TEXT NOT NULL, " +
                    "`token_ref_id` TEXT, " +
                    "`server_version` TEXT, " +
                    "`version_qualification` TEXT, " +
                    "`lan_consented` INTEGER NOT NULL, " +
                    "`created_at_ms` INTEGER NOT NULL, " +
                    "`updated_at_ms` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`source_id`), " +
                    "FOREIGN KEY(`source_id`) REFERENCES `sources`(`source_id`) ON UPDATE NO ACTION ON DELETE CASCADE" +
                    ")",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_jellyfin_source_state_source_id` " +
                    "ON `jellyfin_source_state` (`source_id`)",
            )
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE server_items ADD COLUMN display_title TEXT")
            db.execSQL("ALTER TABLE server_items ADD COLUMN production_year INTEGER")
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS watch_events_new (
                    event_id TEXT NOT NULL,
                    profile_id TEXT NOT NULL,
                    watch_key TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    origin TEXT NOT NULL,
                    position_ms INTEGER,
                    duration_ms INTEGER,
                    occurred_at_ms INTEGER NOT NULL,
                    received_at_ms INTEGER NOT NULL,
                    observed_at_ms INTEGER,
                    connector_id TEXT,
                    connector_event_id TEXT,
                    session_id TEXT,
                    anchor_source_id TEXT,
                    anchor_native_id TEXT,
                    anchor_family_id TEXT,
                    anchor_version_id TEXT,
                    anchor_position_ms INTEGER,
                    anchor_anchored_at_ms INTEGER,
                    PRIMARY KEY(event_id),
                    FOREIGN KEY(profile_id) REFERENCES profiles(profile_id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO watch_events_new (
                    event_id, profile_id, watch_key, kind, origin, position_ms, duration_ms,
                    occurred_at_ms, received_at_ms, observed_at_ms, connector_id,
                    connector_event_id, session_id, anchor_source_id, anchor_native_id,
                    anchor_family_id, anchor_version_id, anchor_position_ms, anchor_anchored_at_ms
                )
                SELECT
                    event_id,
                    profile_id,
                    CASE WHEN edition_id IS NULL THEN 'T:' || title_id ELSE 'T:' || title_id || '#E:' || edition_id END,
                    CASE kind
                        WHEN 'COMPLETION' THEN 'COMPLETED'
                        WHEN 'CANCELLATION' THEN 'CANCELLED'
                        WHEN 'RESET' THEN 'EXPLICIT_RESET'
                        WHEN 'WATCHED' THEN 'EXPLICIT_WATCHED'
                        ELSE kind
                    END,
                    origin,
                    position_ms,
                    duration_ms,
                    occurred_at_ms,
                    received_at_ms,
                    observed_at_ms,
                    NULL,
                    connector_event_id,
                    session_id,
                    source_id,
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    NULL
                FROM watch_events
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE watch_events")
            db.execSQL("ALTER TABLE watch_events_new RENAME TO watch_events")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_watch_events_profile_id_watch_key_occurred_at_ms ON watch_events (profile_id, watch_key, occurred_at_ms)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_watch_events_profile_id_connector_event_id ON watch_events (profile_id, connector_event_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_watch_events_session_id ON watch_events (session_id)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS watch_states_new (
                    profile_id TEXT NOT NULL,
                    watch_key TEXT NOT NULL,
                    status TEXT NOT NULL,
                    position_ms INTEGER NOT NULL,
                    duration_ms INTEGER,
                    completion_fraction REAL NOT NULL,
                    last_event_id TEXT,
                    last_meaningful_at_ms INTEGER,
                    active_session_id TEXT,
                    anchor_source_id TEXT,
                    anchor_native_id TEXT,
                    anchor_family_id TEXT,
                    anchor_version_id TEXT,
                    anchor_position_ms INTEGER,
                    anchor_anchored_at_ms INTEGER,
                    conflicts_json TEXT NOT NULL,
                    connector_states_json TEXT NOT NULL,
                    updated_at_ms INTEGER NOT NULL,
                    PRIMARY KEY(profile_id, watch_key),
                    FOREIGN KEY(profile_id) REFERENCES profiles(profile_id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO watch_states_new (
                    profile_id, watch_key, status, position_ms, duration_ms, completion_fraction,
                    last_event_id, last_meaningful_at_ms, active_session_id,
                    anchor_source_id, anchor_native_id, anchor_family_id, anchor_version_id,
                    anchor_position_ms, anchor_anchored_at_ms, conflicts_json, connector_states_json,
                    updated_at_ms
                )
                SELECT
                    profile_id,
                    watch_key,
                    status,
                    COALESCE(position_ms, 0),
                    duration_ms,
                    COALESCE(completion_fraction, 0.0),
                    last_event_id,
                    last_event_at_ms,
                    NULL,
                    resume_anchor_source_id,
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    '[]',
                    '{}',
                    updated_at_ms
                FROM watch_states
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE watch_states")
            db.execSQL("ALTER TABLE watch_states_new RENAME TO watch_states")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_watch_states_status ON watch_states (status)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS outbox_new (
                    outbox_id TEXT NOT NULL,
                    connector_id TEXT NOT NULL,
                    idempotency_key TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    watch_key TEXT NOT NULL,
                    position_ms INTEGER,
                    duration_ms INTEGER,
                    session_id TEXT,
                    occurred_at_ms INTEGER NOT NULL,
                    state TEXT NOT NULL,
                    attempt_count INTEGER NOT NULL,
                    created_at_ms INTEGER NOT NULL,
                    next_attempt_at_ms INTEGER,
                    last_error TEXT,
                    acked_at_ms INTEGER,
                    PRIMARY KEY(outbox_id)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO outbox_new (
                    outbox_id, connector_id, idempotency_key, kind, watch_key, position_ms,
                    duration_ms, session_id, occurred_at_ms, state, attempt_count,
                    created_at_ms, next_attempt_at_ms, last_error, acked_at_ms
                )
                SELECT
                    o.outbox_id,
                    COALESCE(o.connector_target, o.connector_kind),
                    o.idempotency_key,
                    -- watch_events was rebuilt above: kind + watch_key are canonical
                    e.kind,
                    e.watch_key,
                    e.position_ms,
                    e.duration_ms,
                    e.session_id,
                    e.occurred_at_ms,
                    CASE o.state
                        WHEN 'CLAIMED' THEN 'PENDING'
                        WHEN 'ACKED' THEN 'ACKNOWLEDGED'
                        ELSE o.state
                    END,
                    o.attempt_count,
                    o.created_at_ms,
                    o.next_attempt_at_ms,
                    o.last_error,
                    o.acked_at_ms
                FROM outbox o
                LEFT JOIN watch_events e ON e.event_id = o.event_id
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE outbox")
            db.execSQL("ALTER TABLE outbox_new RENAME TO outbox")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_outbox_connector_id_idempotency_key ON outbox (connector_id, idempotency_key)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_outbox_state_next_attempt_at_ms ON outbox (state, next_attempt_at_ms)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS checkpoints_new (
                    connector_id TEXT NOT NULL,
                    last_import_cursor TEXT,
                    last_imported_at_ms INTEGER,
                    updated_at_ms INTEGER NOT NULL,
                    PRIMARY KEY(connector_id)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO checkpoints_new (connector_id, last_import_cursor, last_imported_at_ms, updated_at_ms)
                SELECT connector_key, cursor, NULL, updated_at_ms FROM checkpoints
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE checkpoints")
            db.execSQL("ALTER TABLE checkpoints_new RENAME TO checkpoints")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_checkpoints_updated_at_ms ON checkpoints (updated_at_ms)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS identity_mappings (
                    mapping_id TEXT NOT NULL,
                    authority TEXT NOT NULL,
                    external_value TEXT NOT NULL,
                    canonical_title_id TEXT NOT NULL,
                    canonical_edition_id TEXT,
                    status TEXT NOT NULL,
                    confidence REAL NOT NULL,
                    origin TEXT NOT NULL,
                    supersedes_mapping_id TEXT,
                    created_at_ms INTEGER NOT NULL,
                    PRIMARY KEY(mapping_id)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_identity_mappings_authority_external_value ON identity_mappings (authority, external_value)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_identity_mappings_canonical_title_id ON identity_mappings (canonical_title_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_identity_mappings_supersedes_mapping_id ON identity_mappings (supersedes_mapping_id)")
            db.execSQL("DROP TABLE IF EXISTS identity_corrections")
        }
    }

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `candidate_handoff` (" +
                    "`candidate_id` TEXT NOT NULL, " +
                    "`snapshot_json` TEXT NOT NULL, " +
                    "`transport_secret_ref` TEXT, " +
                    "`created_at_ms` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`candidate_id`)" +
                    ")",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_candidate_handoff_candidate_id` " +
                    "ON `candidate_handoff` (`candidate_id`)",
            )
        }
    }

    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE titles ADD COLUMN genres TEXT")
            db.execSQL("ALTER TABLE titles ADD COLUMN rating TEXT")
            db.execSQL("ALTER TABLE titles ADD COLUMN synopsis_provenance TEXT")
            db.execSQL("ALTER TABLE titles ADD COLUMN genres_provenance TEXT")
            db.execSQL("ALTER TABLE titles ADD COLUMN rating_provenance TEXT")
        }
    }

    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `home_catalog_preferences` (
                    `profile_id` TEXT NOT NULL,
                    `addon_id` TEXT NOT NULL,
                    `catalog_type` TEXT NOT NULL,
                    `catalog_id` TEXT NOT NULL,
                    `sort_order` INTEGER NOT NULL,
                    PRIMARY KEY(`profile_id`, `addon_id`, `catalog_type`, `catalog_id`),
                    FOREIGN KEY(`addon_id`) REFERENCES `installed_addons`(`addon_id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`profile_id`) REFERENCES `profiles`(`profile_id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_home_catalog_preferences_addon_id` " +
                    "ON `home_catalog_preferences` (`addon_id`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_home_catalog_preferences_profile_id_sort_order` " +
                    "ON `home_catalog_preferences` (`profile_id`, `sort_order`)",
            )
        }
    }

    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `home_provider_preferences` (
                    `profile_id` TEXT NOT NULL,
                    `addon_id` TEXT NOT NULL,
                    `hidden_from_home` INTEGER NOT NULL,
                    `sort_order` INTEGER NOT NULL,
                    PRIMARY KEY(`profile_id`, `addon_id`),
                    FOREIGN KEY(`addon_id`) REFERENCES `installed_addons`(`addon_id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`profile_id`) REFERENCES `profiles`(`profile_id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_home_provider_preferences_addon_id` " +
                    "ON `home_provider_preferences` (`addon_id`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_home_provider_preferences_profile_id_sort_order` " +
                    "ON `home_provider_preferences` (`profile_id`, `sort_order`)",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `home_presentation_preferences` (
                    `profile_id` TEXT NOT NULL,
                    `show_provider_name` INTEGER NOT NULL,
                    `separate_continue_watching` INTEGER NOT NULL,
                    PRIMARY KEY(`profile_id`),
                    FOREIGN KEY(`profile_id`) REFERENCES `profiles`(`profile_id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `installed_addons_new` (
                    `addon_id` TEXT NOT NULL,
                    `public_base_url` TEXT NOT NULL,
                    `query_secret_ref_id` TEXT,
                    `manifest_id` TEXT NOT NULL,
                    `manifest_name` TEXT NOT NULL,
                    `manifest_version` TEXT NOT NULL,
                    `manifest_description` TEXT NOT NULL,
                    `manifest_snapshot_json` TEXT NOT NULL,
                    `manifest_fetched_at_ms` INTEGER NOT NULL,
                    `disclosure_json` TEXT NOT NULL,
                    `lan_consented` INTEGER NOT NULL,
                    `lan_host` TEXT,
                    `enabled` INTEGER NOT NULL,
                    `sort_order` INTEGER NOT NULL,
                    `owner_profile_id` TEXT NOT NULL,
                    `installed_at_ms` INTEGER NOT NULL,
                    `updated_at_ms` INTEGER NOT NULL,
                    `removed_at_ms` INTEGER,
                    PRIMARY KEY(`addon_id`),
                    FOREIGN KEY(`addon_id`) REFERENCES `sources`(`source_id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`owner_profile_id`) REFERENCES `profiles`(`profile_id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )

            db.execSQL(
                """
                INSERT OR REPLACE INTO `installed_addons_new` (
                    addon_id, public_base_url, query_secret_ref_id,
                    manifest_id, manifest_name, manifest_version, manifest_description,
                    manifest_snapshot_json, manifest_fetched_at_ms, disclosure_json,
                    lan_consented, lan_host, enabled, sort_order,
                    owner_profile_id, installed_at_ms, updated_at_ms, removed_at_ms
                )
                SELECT
                    a.addon_id,
                    CASE
                        WHEN a.manifest_url LIKE '%/manifest.json' THEN substr(a.manifest_url, 1, length(a.manifest_url) - length('/manifest.json'))
                        ELSE a.manifest_url
                    END,
                    NULL,
                    a.manifest_id,
                    a.manifest_name,
                    a.manifest_version,
                    a.manifest_description,
                    a.manifest_snapshot_json,
                    COALESCE(a.updated_at_ms, a.installed_at_ms),
                    a.disclosure_json,
                    a.lan_consented,
                    a.lan_host,
                    a.enabled,
                    0,
                    a.owner_profile_id,
                    a.installed_at_ms,
                    a.updated_at_ms,
                    a.removed_at_ms
                FROM installed_addons a
                """.trimIndent(),
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `installed_addons_ordered` (
                    `addon_id` TEXT NOT NULL,
                    `public_base_url` TEXT NOT NULL,
                    `query_secret_ref_id` TEXT,
                    `manifest_id` TEXT NOT NULL,
                    `manifest_name` TEXT NOT NULL,
                    `manifest_version` TEXT NOT NULL,
                    `manifest_description` TEXT NOT NULL,
                    `manifest_snapshot_json` TEXT NOT NULL,
                    `manifest_fetched_at_ms` INTEGER NOT NULL,
                    `disclosure_json` TEXT NOT NULL,
                    `lan_consented` INTEGER NOT NULL,
                    `lan_host` TEXT,
                    `enabled` INTEGER NOT NULL,
                    `sort_order` INTEGER NOT NULL,
                    `owner_profile_id` TEXT NOT NULL,
                    `installed_at_ms` INTEGER NOT NULL,
                    `updated_at_ms` INTEGER NOT NULL,
                    `removed_at_ms` INTEGER,
                    PRIMARY KEY(`addon_id`),
                    FOREIGN KEY(`addon_id`) REFERENCES `sources`(`source_id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`owner_profile_id`) REFERENCES `profiles`(`profile_id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT OR REPLACE INTO `installed_addons_ordered` (
                    addon_id, public_base_url, query_secret_ref_id,
                    manifest_id, manifest_name, manifest_version, manifest_description,
                    manifest_snapshot_json, manifest_fetched_at_ms, disclosure_json,
                    lan_consented, lan_host, enabled, sort_order,
                    owner_profile_id, installed_at_ms, updated_at_ms, removed_at_ms
                )
                SELECT
                    n.addon_id, n.public_base_url, n.query_secret_ref_id,
                    n.manifest_id, n.manifest_name, n.manifest_version, n.manifest_description,
                    n.manifest_snapshot_json, n.manifest_fetched_at_ms, n.disclosure_json,
                    n.lan_consented, n.lan_host, n.enabled,
                    (SELECT COUNT(*) FROM installed_addons_new x
                        WHERE x.removed_at_ms IS NULL
                          AND (COALESCE((SELECT MIN(p.sort_order) FROM addon_catalog_preferences p
                                          WHERE p.addon_id = x.addon_id), 2147483647)
                               < COALESCE((SELECT MIN(p.sort_order) FROM addon_catalog_preferences p
                                          WHERE p.addon_id = n.addon_id), 2147483647)
                               OR (COALESCE((SELECT MIN(p.sort_order) FROM addon_catalog_preferences p
                                          WHERE p.addon_id = x.addon_id), 2147483647)
                                   = COALESCE((SELECT MIN(p.sort_order) FROM addon_catalog_preferences p
                                          WHERE p.addon_id = n.addon_id), 2147483647)
                                   AND (x.installed_at_ms < n.installed_at_ms
                                        OR (x.installed_at_ms = n.installed_at_ms AND x.addon_id < n.addon_id)))
                    )),
                    n.owner_profile_id, n.installed_at_ms, n.updated_at_ms, n.removed_at_ms
                FROM installed_addons_new n
                WHERE n.removed_at_ms IS NULL
                """.trimIndent(),
            )

            db.execSQL(
                """
                INSERT OR REPLACE INTO `installed_addons_ordered` (
                    addon_id, public_base_url, query_secret_ref_id,
                    manifest_id, manifest_name, manifest_version, manifest_description,
                    manifest_snapshot_json, manifest_fetched_at_ms, disclosure_json,
                    lan_consented, lan_host, enabled, sort_order,
                    owner_profile_id, installed_at_ms, updated_at_ms, removed_at_ms
                )
                SELECT
                    n.addon_id, n.public_base_url, n.query_secret_ref_id,
                    n.manifest_id, n.manifest_name, n.manifest_version, n.manifest_description,
                    n.manifest_snapshot_json, n.manifest_fetched_at_ms, n.disclosure_json,
                    n.lan_consented, n.lan_host, n.enabled, n.sort_order,
                    n.owner_profile_id, n.installed_at_ms, n.updated_at_ms, n.removed_at_ms
                FROM installed_addons_new n
                WHERE n.removed_at_ms IS NOT NULL
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE installed_addons")
            db.execSQL("ALTER TABLE installed_addons_new RENAME TO installed_addons_dropped")
            db.execSQL("DROP TABLE installed_addons_dropped")
            db.execSQL("ALTER TABLE installed_addons_ordered RENAME TO installed_addons")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_installed_addons_owner_profile_id` " +
                    "ON `installed_addons` (`owner_profile_id`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_installed_addons_public_base_url` " +
                    "ON `installed_addons` (`public_base_url`)",
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `addon_catalog_preferences_new` (
                    `profile_id` TEXT NOT NULL,
                    `addon_id` TEXT NOT NULL,
                    `catalog_type` TEXT NOT NULL,
                    `catalog_id` TEXT NOT NULL,
                    `sort_order` INTEGER NOT NULL,
                    `hidden` INTEGER NOT NULL,
                    PRIMARY KEY(`profile_id`, `addon_id`, `catalog_type`, `catalog_id`),
                    FOREIGN KEY(`addon_id`) REFERENCES `installed_addons`(`addon_id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`profile_id`) REFERENCES `profiles`(`profile_id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT OR IGNORE INTO `addon_catalog_preferences_new` (
                    profile_id, addon_id, catalog_type, catalog_id, sort_order, hidden
                )
                SELECT
                    p.profile_id,
                    p.addon_id,
                    json_extract(c.value, '$.type'),
                    json_extract(c.value, '$.id'),
                    p.sort_order,
                    p.hidden
                FROM addon_catalog_preferences p
                JOIN installed_addons a ON a.addon_id = p.addon_id
                JOIN json_tree(a.manifest_snapshot_json, '$.catalogs') c
                WHERE json_valid(a.manifest_snapshot_json) = 1
                  AND c.type = 'object'
                  AND json_extract(c.value, '$.type') IS NOT NULL
                  AND json_extract(c.value, '$.id') IS NOT NULL
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE addon_catalog_preferences")
            db.execSQL("ALTER TABLE addon_catalog_preferences_new RENAME TO addon_catalog_preferences")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_addon_catalog_preferences_addon_id` " +
                    "ON `addon_catalog_preferences` (`addon_id`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_addon_catalog_preferences_profile_id_sort_order` " +
                    "ON `addon_catalog_preferences` (`profile_id`, `sort_order`)",
            )
        }
    }

    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `retirement_intents` (
                    `source_id` TEXT NOT NULL,
                    `payload` TEXT NOT NULL,
                    `created_at_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`source_id`)
                )
                """.trimIndent(),
            )

            db.execSQL(
                """
                INSERT OR IGNORE INTO `retirement_intents` (`source_id`, `payload`, `created_at_ms`)
                SELECT
                    substr(`connector_id`, length('jellyfin-retire:') + 1),
                    `last_import_cursor`,
                    COALESCE(`last_imported_at_ms`, 0)
                FROM `checkpoints`
                WHERE `connector_id` LIKE 'jellyfin-retire:%'
                  AND `last_import_cursor` IS NOT NULL
                ORDER BY `last_imported_at_ms` ASC
                """.trimIndent(),
            )

            db.execSQL(
                "DELETE FROM `checkpoints` WHERE `connector_id` LIKE 'jellyfin-retire:%'",
            )
        }
    }

    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE titles ADD COLUMN synopsis TEXT")
            db.execSQL("ALTER TABLE titles ADD COLUMN season_number INTEGER")
            db.execSQL("ALTER TABLE titles ADD COLUMN episode_number INTEGER")
        }
    }

    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `installed_addons` (
                    `addon_id` TEXT NOT NULL,
                    `manifest_url` TEXT NOT NULL,
                    `manifest_id` TEXT NOT NULL,
                    `manifest_name` TEXT NOT NULL,
                    `manifest_version` TEXT NOT NULL,
                    `manifest_description` TEXT NOT NULL,
                    `manifest_snapshot_json` TEXT NOT NULL,
                    `disclosure_json` TEXT NOT NULL,
                    `lan_consented` INTEGER NOT NULL,
                    `lan_host` TEXT,
                    `enabled` INTEGER NOT NULL,
                    `owner_profile_id` TEXT NOT NULL,
                    `installed_at_ms` INTEGER NOT NULL,
                    `updated_at_ms` INTEGER NOT NULL,
                    `removed_at_ms` INTEGER,
                    PRIMARY KEY(`addon_id`),
                    FOREIGN KEY(`addon_id`) REFERENCES `sources`(`source_id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`owner_profile_id`) REFERENCES `profiles`(`profile_id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_installed_addons_owner_profile_id` " +
                    "ON `installed_addons` (`owner_profile_id`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_installed_addons_manifest_url` " +
                    "ON `installed_addons` (`manifest_url`)",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `addon_catalog_preferences` (
                    `profile_id` TEXT NOT NULL,
                    `addon_id` TEXT NOT NULL,
                    `sort_order` INTEGER NOT NULL,
                    `hidden` INTEGER NOT NULL,
                    PRIMARY KEY(`profile_id`, `addon_id`),
                    FOREIGN KEY(`addon_id`) REFERENCES `installed_addons`(`addon_id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`profile_id`) REFERENCES `profiles`(`profile_id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_addon_catalog_preferences_addon_id` " +
                    "ON `addon_catalog_preferences` (`addon_id`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_addon_catalog_preferences_profile_id_sort_order` " +
                    "ON `addon_catalog_preferences` (`profile_id`, `sort_order`)",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `addon_health_observations` (
                    `observation_id` TEXT NOT NULL,
                    `addon_id` TEXT NOT NULL,
                    `observed_at_ms` INTEGER NOT NULL,
                    `healthy` INTEGER NOT NULL,
                    `reason` TEXT,
                    `status` INTEGER,
                    `source_health` TEXT NOT NULL,
                    PRIMARY KEY(`observation_id`),
                    FOREIGN KEY(`addon_id`) REFERENCES `installed_addons`(`addon_id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_addon_health_observations_addon_id_observed_at_ms` " +
                    "ON `addon_health_observations` (`addon_id`, `observed_at_ms`)",
            )
        }
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `profiles_new` (
                    `profile_id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `is_default` INTEGER NOT NULL,
                    `created_at_ms` INTEGER NOT NULL,
                    `profile_index` INTEGER NOT NULL CHECK(profile_index BETWEEN 1 AND 6),
                    `pin_hash` TEXT,
                    `failed_pin_attempts` INTEGER NOT NULL,
                    `pin_locked_until_ms` INTEGER,
                    `uses_primary_sources` INTEGER NOT NULL,
                    PRIMARY KEY(`profile_id`)
                )
                """.trimIndent(),
            )

            db.execSQL(
                """
                INSERT INTO profiles_new (
                    profile_id, name, is_default, created_at_ms, profile_index,
                    pin_hash, failed_pin_attempts, pin_locked_until_ms, uses_primary_sources
                )
                SELECT
                    p.profile_id,
                    p.name,
                    p.is_default,
                    p.created_at_ms,
                    (SELECT COUNT(*) FROM profiles q
                        WHERE q.is_default > p.is_default
                           OR (q.is_default = p.is_default AND q.created_at_ms < p.created_at_ms)
                           OR (q.is_default = p.is_default AND q.created_at_ms = p.created_at_ms AND q.rowid <= p.rowid)),
                    NULL,
                    0,
                    NULL,
                    1
                FROM profiles p
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE profiles")
            db.execSQL("ALTER TABLE profiles_new RENAME TO profiles")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_profiles_profile_index` " +
                    "ON `profiles` (`profile_index`)",
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `outbox_new` (
                    `outbox_id` TEXT NOT NULL,
                    `profile_id` TEXT NOT NULL,
                    `connector_id` TEXT NOT NULL,
                    `idempotency_key` TEXT NOT NULL,
                    `kind` TEXT NOT NULL,
                    `watch_key` TEXT NOT NULL,
                    `position_ms` INTEGER,
                    `duration_ms` INTEGER,
                    `session_id` TEXT,
                    `occurred_at_ms` INTEGER NOT NULL,
                    `state` TEXT NOT NULL,
                    `attempt_count` INTEGER NOT NULL,
                    `created_at_ms` INTEGER NOT NULL,
                    `next_attempt_at_ms` INTEGER,
                    `last_error` TEXT,
                    `acked_at_ms` INTEGER,
                    PRIMARY KEY(`outbox_id`),
                    FOREIGN KEY(`profile_id`) REFERENCES `profiles`(`profile_id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )

            db.execSQL(
                """
                INSERT INTO outbox_new (
                    outbox_id, profile_id, connector_id, idempotency_key, kind, watch_key,
                    position_ms, duration_ms, session_id, occurred_at_ms, state, attempt_count,
                    created_at_ms, next_attempt_at_ms, last_error, acked_at_ms
                )
                SELECT
                    outbox_id,
                    (SELECT profile_id FROM profiles ORDER BY profile_index ASC LIMIT 1),
                    connector_id, idempotency_key, kind, watch_key,
                    position_ms, duration_ms, session_id, occurred_at_ms, state, attempt_count,
                    created_at_ms, next_attempt_at_ms, last_error, acked_at_ms
                FROM outbox
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE outbox")
            db.execSQL("ALTER TABLE outbox_new RENAME TO outbox")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_outbox_profile_id` " +
                    "ON `outbox` (`profile_id`)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_outbox_connector_id_idempotency_key` " +
                    "ON `outbox` (`connector_id`, `idempotency_key`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_outbox_state_next_attempt_at_ms` " +
                    "ON `outbox` (`state`, `next_attempt_at_ms`)",
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `checkpoints_new` (
                    `profile_id` TEXT NOT NULL,
                    `connector_id` TEXT NOT NULL,
                    `last_import_cursor` TEXT,
                    `last_imported_at_ms` INTEGER,
                    `updated_at_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`profile_id`, `connector_id`),
                    FOREIGN KEY(`profile_id`) REFERENCES `profiles`(`profile_id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO checkpoints_new (profile_id, connector_id, last_import_cursor, last_imported_at_ms, updated_at_ms)
                SELECT
                    (SELECT profile_id FROM profiles ORDER BY profile_index ASC LIMIT 1),
                    connector_id, last_import_cursor, last_imported_at_ms, updated_at_ms
                FROM checkpoints
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE checkpoints")
            db.execSQL("ALTER TABLE checkpoints_new RENAME TO checkpoints")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_checkpoints_profile_id` " +
                    "ON `checkpoints` (`profile_id`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_checkpoints_updated_at_ms` " +
                    "ON `checkpoints` (`updated_at_ms`)",
            )
        }
    }

    val MIGRATION_19_20 = object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE jellyfin_source_state ADD COLUMN user_avatar_tag TEXT")
        }
    }

    val MIGRATION_20_21 = object : Migration(20, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE installed_addons ADD COLUMN display_name_override TEXT")
        }
    }

    val MIGRATION_21_22 = object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE addon_catalog_preferences " +
                    "ADD COLUMN discover_visible INTEGER NOT NULL DEFAULT 1",
            )
        }
    }
}
