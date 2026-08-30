package com.binauralcycles.debug

import com.binaural.data.preferences.BinauralPreferencesRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Доступ к репозиторию настроек из кода, который Hilt не инжектит.
 *
 * Отладочный исполнитель команд живёт в `Application`, а не в `ViewModel`,
 * — иначе он умирал бы вместе с активити при выключенном экране и команды
 * из adb переставали работать ровно тогда, когда они нужнее всего (фон,
 * экран погас, на устройство не смотрят).
 *
 * Репозиторий — `@Singleton`, поэтому брать его через entry point, а не
 * создавать вручную: второй экземпляр читал бы тот же DataStore, но
 * рассинхронизировался бы по кэшам с тем, что видит UI.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DebugRepositoryEntryPoint {
    fun preferencesRepository(): BinauralPreferencesRepository
}
