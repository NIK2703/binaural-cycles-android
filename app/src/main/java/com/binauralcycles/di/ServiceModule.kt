package com.binauralcycles.di

import com.binaural.core.domain.service.FileStorageService
import com.binaural.core.domain.service.PlaybackController
import com.binauralcycles.service.FileStorageServiceImpl
import com.binauralcycles.service.PlaybackControllerImpl
import com.binauralcycles.service.ServiceLifecycleManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ServiceModule {
    
    @Binds
    @Singleton
    abstract fun bindPlaybackController(
        impl: PlaybackControllerImpl
    ): PlaybackController
    
    @Binds
    @Singleton
    abstract fun bindFileStorageService(
        impl: FileStorageServiceImpl
    ): FileStorageService
}
