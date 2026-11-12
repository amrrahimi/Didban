package com.pokervision.core.domain.usecase

import com.pokervision.core.common.result.AppError
import com.pokervision.core.common.result.AppResult
import com.pokervision.core.domain.repository.GameRepository
import com.pokervision.core.domain.repository.MediaRepository
import com.pokervision.core.domain.repository.SettingsRepository
import com.pokervision.core.model.game.PokerVariant
import com.pokervision.core.model.media.MediaAsset
import com.pokervision.core.model.media.MediaType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

data class DashboardSummary(
    val imageCount: Int,
    val videoCount: Int,
    val gameCount: Int,
    val handCount: Int,
    val defaultVariant: PokerVariant,
    val recentMedia: List<MediaAsset>,
)

class ObserveDashboardUseCase @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val gameRepository: GameRepository,
    private val settingsRepository: SettingsRepository,
) {
    operator fun invoke(recentLimit: Int = DEFAULT_RECENT_LIMIT): Flow<DashboardSummary> = combine(
        mediaRepository.observeAll(),
        gameRepository.observeGames(),
        gameRepository.observeHandCount(),
        settingsRepository.settings,
    ) { media, games, hands, settings ->
        DashboardSummary(
            imageCount = media.count { it.type == MediaType.IMAGE },
            videoCount = media.count { it.type == MediaType.VIDEO },
            gameCount = games.size,
            handCount = hands,
            defaultVariant = settings.defaultVariant,
            recentMedia = media.sortedByDescending { it.createdAtMillis }.take(recentLimit),
        )
    }

    companion object {
        const val DEFAULT_RECENT_LIMIT = 5
    }
}

/**
 * Privacy action: removes all media files, game history and resets settings.
 * Attempts every step even if one fails, then reports the first failure.
 */
class ClearAllLocalDataUseCase @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val gameRepository: GameRepository,
    private val settingsRepository: SettingsRepository,
) {
    suspend operator fun invoke(): AppResult<Unit> {
        val media = mediaRepository.deleteAll()
        val games = gameRepository.deleteAll()
        val settings = try {
            settingsRepository.resetToDefaults()
            AppResult.Success(Unit)
        } catch (c: kotlin.coroutines.cancellation.CancellationException) {
            throw c
        } catch (t: Exception) {
            AppResult.Failure(AppError.Io("Failed to reset settings", t))
        }
        return listOf(media, games, settings).firstOrNull { it is AppResult.Failure } ?: AppResult.Success(Unit)
    }
}
