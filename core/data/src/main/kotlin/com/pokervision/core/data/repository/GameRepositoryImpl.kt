package com.pokervision.core.data.repository

import com.pokervision.core.common.result.AppError
import com.pokervision.core.common.result.AppResult
import com.pokervision.core.common.result.appResultOf
import com.pokervision.core.data.di.IoDispatcher
import com.pokervision.core.data.mapper.toEntity
import com.pokervision.core.data.mapper.toModelOrNull
import com.pokervision.core.database.dao.GameDao
import com.pokervision.core.database.dao.HandHistoryDao
import com.pokervision.core.domain.repository.GameRepository
import com.pokervision.core.model.game.PokerGame
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GameRepositoryImpl @Inject constructor(
    private val gameDao: GameDao,
    private val handDao: HandHistoryDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) : GameRepository {

    override fun observeGames(): Flow<List<PokerGame>> = gameDao.observeAll().map { it.mapNotNull { e -> e.toModelOrNull() } }

    override fun observeHandCount(): Flow<Int> = handDao.observeCount()

    override suspend fun saveGame(game: PokerGame): AppResult<Unit> = withContext(io) {
        appResultOf({ AppError.Io("Could not save game", it) }) { gameDao.upsert(game.toEntity()) }
    }

    override suspend fun deleteGame(id: String): AppResult<Unit> = withContext(io) {
        if (gameDao.deleteById(id) > 0) AppResult.Success(Unit) else AppResult.Failure(AppError.NotFound("Game $id"))
    }

    override suspend fun deleteAll(): AppResult<Unit> = withContext(io) {
        // Hands are removed by ON DELETE CASCADE.
        appResultOf({ AppError.Io("Could not clear games", it) }) { gameDao.deleteAll() }
    }
}
