package com.binaural.core.audio.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Модуль DI для аудио-компонентов. Сейчас пуст и оставлен как точка расширения.
 *
 * Исторически через DI предоставлялся `BinauralAudioEngine`, но он давно
 * создаётся и управляется самим `BinauralPlaybackService` (полная изоляция
 * генерации от UI-потока), а в 2026-08-31 и сам класс удалён как мёртвый.
 */
@Module
@InstallIn(SingletonComponent::class)
object AudioModule