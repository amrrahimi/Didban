package com.pokervision.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validates every migration path against the exported schemas in core/database/schemas.
 * When adding version N, add a test that creates N-1, inserts rows, runs the migration and
 * checks the data survived.
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

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
