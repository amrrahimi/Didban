package com.pokervision.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.pokervision.core.database.dao.GameDao
import com.pokervision.core.database.dao.HandHistoryDao
import com.pokervision.core.database.dao.MediaAssetDao
import com.pokervision.core.database.entity.GameEntity
import com.pokervision.core.database.entity.HandHistoryEntity
import com.pokervision.core.database.entity.MediaAssetEntity

@Database(
    entities = [MediaAssetEntity::class, GameEntity::class, HandHistoryEntity::class],
    version = PokerVisionDatabase.VERSION,
    exportSchema = true,
)
abstract class PokerVisionDatabase : RoomDatabase() {
    abstract fun mediaAssetDao(): MediaAssetDao
    abstract fun gameDao(): GameDao
    abstract fun handHistoryDao(): HandHistoryDao

    companion object {
        const val VERSION = 2
        const val NAME = "poker_vision.db"
    }
}

/**
 * Versioned migrations. Policy:
 * 1. Bump [PokerVisionDatabase.VERSION].
 * 2. Build once so Room exports `schemas/<N>.json`; commit it.
 * 3. Add `MIGRATION_<N-1>_<N>` here (or an `@AutoMigration` for additive changes).
 * 4. Add a case to `MigrationTest` (androidTest).
 * Destructive fallback is intentionally NOT enabled: user history must never be dropped silently.
 */
object PokerVisionMigrations {

    /** v2: video thumbnails. Existing rows get NULL (the UI falls back to an icon). */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE media_assets ADD COLUMN thumbnail_path TEXT")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
