package com.binauralcycles.di

import com.binaural.core.domain.repository.PlaybackStateRepository
import com.binaural.core.domain.repository.PresetRepository
import com.binaural.core.domain.repository.SettingsRepository
import com.binaural.core.domain.service.FileStorageService
import com.binaural.core.domain.service.PlaybackController
import com.binaural.core.domain.usecase.DeletePresetUseCase
import com.binaural.core.domain.usecase.DuplicatePresetUseCase
import com.binaural.core.domain.usecase.ExportPresetUseCase
import com.binaural.core.domain.usecase.GetActivePresetUseCase
import com.binaural.core.domain.usecase.ImportPresetUseCase
import com.binaural.core.domain.usecase.PlayPresetUseCase
import com.binaural.core.domain.usecase.PlaybackUseCase
import com.binaural.core.domain.usecase.PresetUseCase
import com.binaural.core.domain.usecase.SavePresetUseCase
import com.binaural.core.domain.usecase.SettingsUseCase
import com.binaural.core.domain.usecase.StopPlaybackUseCase
import com.binaural.core.domain.usecase.UpdateFrequenciesUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * DI-модуль domain-слоя.
 * Предоставляет Use Cases для бизнес-логики приложения.
 */
@Module
@InstallIn(SingletonComponent::class)
object DomainModule {
    
    // Существующие Use Cases
    
    @Provides
    @Singleton
    fun providePresetUseCase(
        presetRepository: PresetRepository,
        playbackController: PlaybackController
    ): PresetUseCase {
        return PresetUseCase(presetRepository, playbackController)
    }
    
    @Provides
    @Singleton
    fun providePlaybackUseCase(
        playbackController: PlaybackController
    ): PlaybackUseCase {
        return PlaybackUseCase(playbackController)
    }
    
    @Provides
    @Singleton
    fun provideSettingsUseCase(
        settingsRepository: SettingsRepository,
        playbackController: PlaybackController
    ): SettingsUseCase {
        return SettingsUseCase(settingsRepository, playbackController)
    }
    
    // Новые Use Cases
    
    @Provides
    @Singleton
    fun provideGetActivePresetUseCase(
        presetRepository: PresetRepository,
        playbackStateRepository: PlaybackStateRepository
    ): GetActivePresetUseCase {
        return GetActivePresetUseCase(presetRepository, playbackStateRepository)
    }
    
    @Provides
    @Singleton
    fun provideSavePresetUseCase(
        presetRepository: PresetRepository
    ): SavePresetUseCase {
        return SavePresetUseCase(presetRepository)
    }
    
    @Provides
    @Singleton
    fun provideDeletePresetUseCase(
        presetRepository: PresetRepository,
        playbackStateRepository: PlaybackStateRepository,
        playbackController: PlaybackController
    ): DeletePresetUseCase {
        return DeletePresetUseCase(presetRepository, playbackStateRepository, playbackController)
    }
    
    @Provides
    @Singleton
    fun provideDuplicatePresetUseCase(
        presetRepository: PresetRepository
    ): DuplicatePresetUseCase {
        return DuplicatePresetUseCase(presetRepository)
    }
    
    @Provides
    @Singleton
    fun providePlayPresetUseCase(
        presetRepository: PresetRepository,
        playbackStateRepository: PlaybackStateRepository,
        playbackController: PlaybackController
    ): PlayPresetUseCase {
        return PlayPresetUseCase(presetRepository, playbackStateRepository, playbackController)
    }
    
    @Provides
    @Singleton
    fun provideStopPlaybackUseCase(
        playbackController: PlaybackController,
        presetRepository: PresetRepository,
        playbackStateRepository: PlaybackStateRepository
    ): StopPlaybackUseCase {
        return StopPlaybackUseCase(playbackController, presetRepository, playbackStateRepository)
    }
    
    @Provides
    @Singleton
    fun provideExportPresetUseCase(
        presetRepository: PresetRepository,
        fileStorageService: FileStorageService
    ): ExportPresetUseCase {
        return ExportPresetUseCase(presetRepository, fileStorageService)
    }
    
    @Provides
    @Singleton
    fun provideImportPresetUseCase(
        presetRepository: PresetRepository,
        fileStorageService: FileStorageService,
        duplicatePresetUseCase: DuplicatePresetUseCase
    ): ImportPresetUseCase {
        return ImportPresetUseCase(presetRepository, fileStorageService, duplicatePresetUseCase)
    }
    
    @Provides
    @Singleton
    fun provideUpdateFrequenciesUseCase(
        playbackStateRepository: PlaybackStateRepository
    ): UpdateFrequenciesUseCase {
        return UpdateFrequenciesUseCase(playbackStateRepository)
    }
}
