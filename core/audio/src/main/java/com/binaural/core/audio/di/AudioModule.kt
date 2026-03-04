package com.binaural.core.audio.di

import android.content.Context
import com.binaural.core.audio.engine.BinauralAudioEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AudioModule {

    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope {
        return CoroutineScope(Dispatchers.Default)
    }

    @Provides
    @Singleton
    fun provideBinauralAudioEngine(
        scope: CoroutineScope,
        @ApplicationContext context: Context
    ): BinauralAudioEngine {
        return BinauralAudioEngine(context).apply {
            initialize(scope)
        }
    }
}
