package com.pokervision.core.domain.usecase

import com.pokervision.core.common.result.AppError
import com.pokervision.core.common.result.AppResult
import com.pokervision.core.domain.repository.GameRepository
import com.pokervision.core.domain.repository.MediaRepository
import com.pokervision.core.domain.repository.SettingsRepository
import com.pokervision.core.model.confidence.ConfidenceThresholds
import com.pokervision.core.model.game.PokerGame
import com.pokervision.core.model.game.PokerVariant
import com.pokervision.core.model.media.MediaAsset
import com.pokervision.core.model.media.MediaOrigin
import com.pokervision.core.model.media.MediaType
import com.pokervision.core.model.settings.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeMediaRepository(initial: List<MediaAsset> = emptyList()) : MediaRepository {
    val items = MutableStateFlow(initial)
    var failDeleteAll = false
    var lastImport: Pair<String, MediaType>? = null

    override fun observeAll(): Flow<List<MediaAsset>> = items
    override fun observeByType(type: MediaType) = items.map { list -> list.filter { it.type == type } }
    override suspend fun getById(id: Long) = items.value.firstOrNull { it.id == id }

    override suspend fun importFromUri(sourceUri: String, expectedType: MediaType): AppResult<MediaAsset> {
        lastImport = sourceUri to expectedType
        val asset = media(items.value.size + 1L, expectedType, origin = MediaOrigin.USER_IMPORT)
        items.value = items.value + asset
        return AppResult.Success(asset)
    }

    override suspend fun createCaptureFile(type: MediaType): AppResult<String> =
        AppResult.Success("/data/media/capture_${items.value.size}")

    override suspend fun registerLocalFile(path: String, type: MediaType, origin: MediaOrigin): AppResult<MediaAsset> {
        val asset = media(items.value.size + 1L, type, origin = origin).copy(localPath = path)
        items.value = items.value + asset
        return AppResult.Success(asset)
    }

    override suspend fun delete(id: Long): AppResult<Unit> {
        items.value = items.value.filterNot { it.id == id }
        return AppResult.Success(Unit)
    }

    override suspend fun deleteAll(): AppResult<Unit> {
        if (failDeleteAll) return AppResult.Failure(AppError.Io("disk"))
        items.value = emptyList()
        return AppResult.Success(Unit)
    }

    override suspend fun storageUsageBytes(): Long = items.value.sumOf { it.sizeBytes }
}

class FakeGameRepository(initial: List<PokerGame> = emptyList(), hands: Int = 0) : GameRepository {
    val games = MutableStateFlow(initial)
    val handCount = MutableStateFlow(hands)
    override fun observeGames(): Flow<List<PokerGame>> = games
    override fun observeHandCount(): Flow<Int> = handCount
    override suspend fun saveGame(game: PokerGame): AppResult<Unit> {
        games.value = games.value.filterNot { it.id == game.id } + game
        return AppResult.Success(Unit)
    }
    override suspend fun deleteGame(id: String): AppResult<Unit> {
        games.value = games.value.filterNot { it.id == id }
        return AppResult.Success(Unit)
    }
    override suspend fun deleteAll(): AppResult<Unit> {
        games.value = emptyList()
        handCount.value = 0
        return AppResult.Success(Unit)
    }
}

class FakeSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state
    val current: AppSettings get() = state.value
    override suspend fun setDefaultVariant(variant: PokerVariant) { state.value = state.value.copy(defaultVariant = variant) }
    override suspend fun setAnalysisFps(fps: Int) { state.value = state.value.copy(analysisFps = fps) }
    override suspend fun setConfidenceThresholds(minimumDetection: Float, autoConfirm: Float) {
        state.value = state.value.copy(confidenceThresholds = ConfidenceThresholds(minimumDetection, autoConfirm))
    }
    override suspend fun resetToDefaults() { state.value = AppSettings() }
}

fun media(id: Long, type: MediaType, createdAt: Long = id, origin: MediaOrigin = MediaOrigin.USER_IMPORT) = MediaAsset(
    id = id,
    type = type,
    origin = origin,
    localPath = "/data/media/$id",
    displayName = "file$id",
    mimeType = if (type == MediaType.IMAGE) "image/jpeg" else "video/mp4",
    sizeBytes = 100,
    createdAtMillis = createdAt,
)
