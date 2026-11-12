package com.pokervision.core.domain.usecase

import com.pokervision.core.common.result.AppResult
import com.pokervision.core.domain.repository.GameRepository
import com.pokervision.core.domain.repository.MediaRepository
import com.pokervision.core.model.game.PokerGame
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveGamesUseCase @Inject constructor(private val repository: GameRepository) {
    operator fun invoke(): Flow<List<PokerGame>> = repository.observeGames()
}

class DeleteGameUseCase @Inject constructor(private val repository: GameRepository) {
    suspend operator fun invoke(id: String): AppResult<Unit> = repository.deleteGame(id)
}

class GetStorageUsageUseCase @Inject constructor(private val repository: MediaRepository) {
    suspend operator fun invoke(): Long = repository.storageUsageBytes()
}
