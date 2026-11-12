package com.pokervision.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validates every migration path against the exported schemas in core/database/schemas.
 * Each version bump adds a test that creates the old version, inserts rows, migrates, and checks
 * that the data survived.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PokerVisionDatabase::class.java,
    )

    @Test
    fun currentSchemaCanBeCreatedAndValidated() {
        helper.createDatabase(TEST_DB, PokerVisionDatabase.VERSION).close()
        helper.runMigrationsAndValidate(TEST_DB, PokerVisionDatabase.VERSION, true, *PokerVisionMigrations.ALL)
    }

    @Test
    fun migrate1To2KeepsMediaAndAddsNullThumbnail() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO media_assets (id, type, origin, local_path, display_name, mime_type, size_bytes, " +
                    "width, height, duration_ms, created_at) VALUES " +
                    "(7, 'VIDEO', 'USER_IMPORT', '/x/v.mp4', 'v.mp4', 'video/mp4', 1234, 1920, 1080, 5000, 42)",
            )
            db.execSQL(
                "INSERT INTO games (id, variant, source, player_count, started_at, ended_at, media_asset_id, notes) " +
                    "VALUES ('g1', 'TEXAS_HOLDEM', 'VIDEO', 6, 1, NULL, 7, '')",
            )
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, PokerVisionMigrations.MIGRATION_1_2)
        db.query("SELECT display_name, size_bytes, thumbnail_path FROM media_assets WHERE id = 7").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("v.mp4", c.getString(0))
            assertEquals(1234L, c.getLong(1))
            assertTrue(c.isNull(2))
        }
        db.query("SELECT media_asset_id FROM games WHERE id = 'g1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(7L, c.getLong(0))
        }
        db.close()
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
