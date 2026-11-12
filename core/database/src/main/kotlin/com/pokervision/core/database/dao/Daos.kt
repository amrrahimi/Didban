package com.pokervision.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.pokervision.core.database.entity.GameEntity
import com.pokervision.core.database.entity.HandHistoryEntity
import com.pokervision.core.database.entity.MediaAssetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaAssetDao {
    @Query("SELECT * FROM media_assets ORDER BY created_at DESC")
    fun observeAll(): Flow<List<MediaAssetEntity>>

    @Query("SELECT * FROM media_assets WHERE type = :type ORDER BY created_at DESC")
    fun observeByType(type: String): Flow<List<MediaAssetEntity>>

    @Query("SELECT * FROM media_assets WHERE id = :id")
    suspend fun getById(id: Long): MediaAssetEntity?

    @Query("SELECT * FROM media_assets")
    suspend fun getAll(): List<MediaAssetEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: MediaAssetEntity): Long

    @Query("DELETE FROM media_assets WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM media_assets")
    suspend fun deleteAll()

    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM media_assets")
    suspend fun totalSizeBytes(): Long
}

@Dao
interface GameDao {
    @Query("SELECT * FROM games ORDER BY started_at DESC")
    fun observeAll(): Flow<List<GameEntity>>

    @Upsert
    suspend fun upsert(entity: GameEntity)

    @Query("DELETE FROM games WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM games")
    suspend fun deleteAll()
}

@Dao
interface HandHistoryDao {
    @Query("SELECT * FROM hand_histories WHERE game_id = :gameId ORDER BY hand_number")
    fun observeForGame(gameId: String): Flow<List<HandHistoryEntity>>

    @Query("SELECT COUNT(*) FROM hand_histories")
    fun observeCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: HandHistoryEntity): Long
}
