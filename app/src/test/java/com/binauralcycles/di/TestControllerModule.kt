package com.binauralcycles.di

import com.binaural.core.domain.repository.PlaybackController
import com.binaural.core.domain.test.FakePlaybackController
import dagger.Binds
import dagger.Module
import dagger.hilt.testing.TestInstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Тестовый DI-модуль для замены реального PlaybackController на fake реализацию.
 * Используется в unit и instrumentation тестах для эмуляции воспроизведения.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [ServiceModule::class]
)
abstract class TestControllerModule {
    
    @Binds
    @Singleton
    abstract fun bindPlaybackController(
        fake: FakePlaybackController
    ): PlaybackController
}