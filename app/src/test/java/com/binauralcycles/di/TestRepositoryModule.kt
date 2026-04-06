package com.binauralcycles.di

import com.binaural.core.domain.repository.PresetRepository
import com.binaural.core.domain.repository.SettingsRepository
import com.binaural.core.domain.test.FakePresetRepository
import com.binaural.core.domain.test.FakeSettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.testing.TestInstallIn
import com.binaural.data.preferences.di.RepositoryModule
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Тестовый DI-модуль для замены реальных репозиториев на fake реализации.
 * Используется в unit и instrumentation тестах.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [RepositoryModule::class]
)
abstract class TestRepositoryModule {
    
    @Binds
    @Singleton
    abstract fun bindPresetRepository(
        fake: FakePresetRepository
    ): PresetRepository
    
    @Binds
    @Singleton
    abstract fun bindSettingsRepository(
        fake: FakeSettingsRepository
    ): SettingsRepository
}