package com.binaural.core.audio.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Модуль DI для аудио-компонентов.
 * 
 * Примечание: BinauralAudioEngine больше не предоставляется через DI,
 * так как он создаётся и управляется исключительно в BinauralPlaybackService.
 * Это обеспечивает полную изоляцию аудио-генерации от UI потока.
 */
@Module
@InstallIn(SingletonComponent::class)
object AudioModule {
    // BinauralAudioEngine создаётся в BinauralPlaybackService
    // и не должен быть singleton в Hilt, чтобы избежать утечек памяти
}