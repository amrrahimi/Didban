package com.pokervision.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Enum-valued columns are stored as their `name` strings (not ordinals) so reordering enum
 * constants can never silently corrupt stored data. Mapping lives in :core:data.
 */
@Entity(
    tableName = "media_assets",
    indices = [Index("type"), Index("created_at")],
)
data class MediaAssetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val origin: String,
    @ColumnInfo(name = "local_path") val localPath: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    val width: Int?,
    val height: Int?,
    @ColumnInfo(name = "duration_ms") val durationMillis: Long?,
    @ColumnInfo(name = "created_at") val createdAtMillis: Long,
)

@Entity(
    tableName = "games",
    foreignKeys = [
        ForeignKey(
            entity = MediaAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["media_asset_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("media_asset_id"), Index("started_at")],
)
data class GameEntity(
    @PrimaryKey val id: String,
    val variant: String,
    val source: String,
    @ColumnInfo(name = "player_count") val playerCount: Int,
    @ColumnInfo(name = "started_at") val startedAtMillis: Long,
    @ColumnInfo(name = "ended_at") val endedAtMillis: Long?,
    @ColumnInfo(name = "media_asset_id") val mediaAssetId: Long?,
    val notes: String,
)

@Entity(
    tableName = "hand_histories",
    foreignKeys = [
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["id"],
            childColumns = ["game_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["game_id", "hand_number"], unique = true)],
)
data class HandHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "game_id") val gameId: String,
    @ColumnInfo(name = "hand_number") val handNumber: Int,
    val variant: String,
    /** Compact card codes, e.g. "AsKd7h" (see CardCodec). */
    @ColumnInfo(name = "board_cards") val boardCards: String,
    @ColumnInfo(name = "hero_cards") val heroCards: String,
    /** Null when the pot was not observed. */
    @ColumnInfo(name = "final_pot") val finalPot: Long?,
    /** Comma-separated player ids. */
    @ColumnInfo(name = "winner_ids") val winnerIds: String,
    val notes: String,
    @ColumnInfo(name = "created_at") val createdAtMillis: Long,
)
