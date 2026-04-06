package com.binaural.core.domain.repository

import com.binaural.core.domain.model.ChannelSwapSettings
import com.binaural.core.domain.model.VolumeNormalizationSettings
import kotlinx.coroutines.flow.Flow

/**
 * Интерфейс репозитория для настроек приложения.
 * Скрывает детали хранения настроек.
 */
interface SettingsRepository {
    
    // ========== Громкость ==========
    
    fun getVolume(): Flow<Float>
    suspend fun saveVolume(volume: Float)
    
    // ========== Частота дискретизации ==========
    
    fun getSampleRate(): Flow<Int>
    suspend fun saveSampleRate(rate: Int)
    
    // ========== Интервал генерации буфера ==========
    
    fun getBufferGenerationMinutes(): Flow<Int>
    suspend fun saveBufferGenerationMinutes(minutes: Int)
    
    // ========== Перестановка каналов ==========
    
    fun getChannelSwapSettings(): Flow<ChannelSwapSettings>
    suspend fun saveChannelSwapSettings(settings: ChannelSwapSettings)
    
    // ========== Нормализация громкости ==========
    
    fun getVolumeNormalizationSettings(): Flow<VolumeNormalizationSettings>
    suspend fun saveVolumeNormalizationSettings(settings: VolumeNormalizationSettings)
    
    // ========== Автовозобновление ==========
    
    fun getResumeOnHeadsetConnect(): Flow<Boolean>
    suspend fun saveResumeOnHeadsetConnect(enabled: Boolean)
    
    fun getAutoResumeOnAppStart(): Flow<Boolean>
    suspend fun saveAutoResumeOnAppStart(enabled: Boolean)
    
    // ========== График ==========
    
    fun getAutoExpandGraphRange(): Flow<Boolean>
    suspend fun saveAutoExpandGraphRange(enabled: Boolean)
}