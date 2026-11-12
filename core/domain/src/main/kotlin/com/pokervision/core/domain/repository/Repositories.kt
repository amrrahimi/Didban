package com.pokervision.core.domain.repository

import com.pokervision.core.common.result.AppResult
import com.pokervision.core.model.game.PokerGame
import com.pokervision.core.model.game.PokerVariant
import com.pokervision.core.model.media.MediaAsset
import com.pokervision.core.model.media.MediaOrigin
import com.pokervision.core.model.media.MediaType
import com.pokervision.core.model.settings.AppSettings
import com.pokervision.core.model.settings.KeyframeSensitivity
import kotlinx.coroutines.flow.Flow

/** Images and videos owned by the app (captured in-app or imported). Implemented in :core:data. */
interface MediaRepository {
    fun observeAll(): Flow<List<MediaAsset>>

    fun observeByType(type: MediaType): Flow<List<MediaAsset>>

    suspend fun getById(id: Long): MediaAsset?

    /**
     * Copies the content behind [sourceUri] (a content:// URI from the system picker) into
     * app-private storage and records it. Fails with [com.pokervision.core.common.result.AppError.Validation]
     * if the content is not of [expectedType].
     */
    suspend fun importFromUri(sourceUri: String, expectedType: MediaType): AppResult<MediaAsset>

    /** Allocates a new, empty file in app-private storage for the camera to write into. Returns its path. */
    suspend fun createCaptureFile(type: MediaType): AppResult<String>

    /** Records a file already written to app-private storage by the camera. */
    suspend fun registerLocalFile(path: String, type: MediaType, origin: MediaOrigin): AppResult<MediaAsset>

    /** Deletes the database row and the underlying file. */
    suspend fun delete(id: Long): AppResult<Unit>

    suspend fun deleteAll(): AppResult<Unit>

    /** Bytes used by app-owned media files. */
    suspend fun storageUsageBytes(): Long
}

/** Game sessions and hand histories. */
interface GameRepository {
    fun observeGames(): Flow<List<PokerGame>>

    fun observeHandCount(): Flow<Int>

    suspend fun saveGame(game: PokerGame): AppResult<Unit>

    suspend fun deleteGame(id: String): AppResult<Unit>

    suspend fun deleteAll(): AppResult<Unit>
}

interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun setDefaultVariant(variant: PokerVariant)

    suspend fun setAnalysisFps(fps: Int)

    suspend fun setConfidenceThresholds(minimumDetection: Float, autoConfirm: Float)

    suspend fun setVideoSampleInterval(intervalMs: Long)

    suspend fun setKeyframeSensitivity(sensitivity: KeyframeSensitivity)

    suspend fun resetToDefaults()
}
