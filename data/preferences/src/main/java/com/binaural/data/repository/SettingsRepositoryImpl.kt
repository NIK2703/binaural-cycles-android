package com.binaural.data.repository

import com.binaural.core.domain.repository.SettingsRepository
import com.binaural.core.domain.model.ChannelSwapSettings
import com.binaural.core.domain.model.VolumeNormalizationSettings
import com.binaural.data.datasource.local.PreferencesDataSource
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Реализация SettingsRepository.
 * Отвечает только за настройки приложения.
 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val preferencesDataSource: PreferencesDataSource
) : SettingsRepository {
    
    override fun getVolume(): Flow<Float> {
        return preferencesDataSource.getVolume()
    }
    
    override suspend fun saveVolume(volume: Float) {
        preferencesDataSource.saveVolume(volume)
    }
    
    override fun getSampleRate(): Flow<Int> {
        return preferencesDataSource.getSampleRate()
    }
    
    override suspend fun saveSampleRate(rate: Int) {
        preferencesDataSource.saveSampleRate(rate)
    }
    
    override fun getBufferGenerationMinutes(): Flow<Int> {
        return preferencesDataSource.getBufferGenerationMinutes()
    }
    
    override suspend fun saveBufferGenerationMinutes(minutes: Int) {
        preferencesDataSource.saveBufferGenerationMinutes(minutes)
    }
    
    override fun getChannelSwapSettings(): Flow<ChannelSwapSettings> {
        return preferencesDataSource.getChannelSwapSettings()
    }
    
    override suspend fun saveChannelSwapSettings(settings: ChannelSwapSettings) {
        preferencesDataSource.saveChannelSwapSettings(settings)
    }
    
    override fun getVolumeNormalizationSettings(): Flow<VolumeNormalizationSettings> {
        return preferencesDataSource.getVolumeNormalizationSettings()
    }
    
    override suspend fun saveVolumeNormalizationSettings(settings: VolumeNormalizationSettings) {
        preferencesDataSource.saveVolumeNormalizationSettings(settings)
    }
    
    override fun getResumeOnHeadsetConnect(): Flow<Boolean> {
        return preferencesDataSource.getResumeOnHeadsetConnect()
    }
    
    override suspend fun saveResumeOnHeadsetConnect(enabled: Boolean) {
        preferencesDataSource.saveResumeOnHeadsetConnect(enabled)
    }
    
    override fun getAutoResumeOnAppStart(): Flow<Boolean> {
        return preferencesDataSource.getAutoResumeOnAppStart()
    }
    
    override suspend fun saveAutoResumeOnAppStart(enabled: Boolean) {
        preferencesDataSource.saveAutoResumeOnAppStart(enabled)
    }
    
    override fun getAutoExpandGraphRange(): Flow<Boolean> {
        return preferencesDataSource.getAutoExpandGraphRange()
    }
    
    override suspend fun saveAutoExpandGraphRange(enabled: Boolean) {
        preferencesDataSource.saveAutoExpandGraphRange(enabled)
    }
}