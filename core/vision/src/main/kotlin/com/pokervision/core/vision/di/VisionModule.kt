package com.pokervision.core.vision.di

import com.pokervision.core.vision.CardDetector
import com.pokervision.core.vision.OnnxCardDetector
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class VisionModule {
    @Binds
    abstract fun bindCardDetector(impl: OnnxCardDetector): CardDetector
}
