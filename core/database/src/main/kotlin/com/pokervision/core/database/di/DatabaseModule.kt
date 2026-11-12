package com.pokervision.core.database.di

import android.content.Context
import androidx.room.Room
import com.pokervision.core.database.PokerVisionDatabase
import com.pokervision.core.database.PokerVisionMigrations
import com.pokervision.core.database.dao.GameDao
import com.pokervision.core.database.dao.HandHistoryDao
import com.pokervision.core.database.dao.MediaAssetDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PokerVisionDatabase =
        Room.databaseBuilder(context, PokerVisionDatabase::class.java, PokerVisionDatabase.NAME)
            .addMigrations(*PokerVisionMigrations.ALL)
            .build()

    @Provides
    fun provideMediaAssetDao(db: PokerVisionDatabase): MediaAssetDao = db.mediaAssetDao()

    @Provides
    fun provideGameDao(db: PokerVisionDatabase): GameDao = db.gameDao()

    @Provides
    fun provideHandHistoryDao(db: PokerVisionDatabase): HandHistoryDao = db.handHistoryDao()
}
