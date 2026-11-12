package com.pokervision.core.media.di

import com.pokervision.core.domain.media.KeyframeStore
import com.pokervision.core.domain.media.VideoFrameReader
import com.pokervision.core.media.AndroidVideoFrameReader
import com.pokervision.core.media.CacheKeyframeStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class MediaModule {
    @Binds
    abstract fun bindVideoFrameReader(impl: AndroidVideoFrameReader): VideoFrameReader

    @Binds
    abstract fun bindKeyframeStore(impl: CacheKeyframeStore): KeyframeStore
}
