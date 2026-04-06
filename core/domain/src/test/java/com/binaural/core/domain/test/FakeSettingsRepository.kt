package com.binaural.core.domain.test

import com.binaural.core.domain.repository.SettingsRepository
import com.binaural.core.domain.model.ChannelSwapSettings
import com.binaural.core.domain.model.VolumeNormalizationSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fake реализация SettingsRepository для тестирования.
 * Хранит настройки в памяти.
 */
class FakeSettingsRepository : SettingsRepository {
    
    private val volume = MutableStateFlow(0.7f)
    private val sampleRate = MutableStateFlow(44100)
    private val bufferGenerationMinutes = MutableStateFlow(10)
    private val channelSwapSettings = MutableStateFlow(ChannelSwapSettings())
    private val volumeNormalizationSettings = MutableStateFlow(VolumeNormalizationSettings())
    private val resumeOnHeadsetConnect = MutableStateFlow(false)
    private val autoResumeOnAppStart = MutableStateFlow(false)
    private val autoExpandGraphRange = MutableStateFlow(false)
    
    // ========== Методы для настройки в тестах ==========
    
    fun setVolume(value: Float) {
        volume.value = value
    }
    
    fun setSampleRate(value: Int) {
        sampleRate.value = value
    }
    
    fun setChannelSwapSettings(settings: ChannelSwapSettings) {
        channelSwapSettings.value = settings
    }
    
    fun setVolumeNormalizationSettings(settings: VolumeNormalizationSettings) {
        volumeNormalizationSettings.value = settings
    }
    
    // ========== Реализация интерфейса ==========
    
    override fun getVolume(): Flow<Float> = volume.asStateFlow()
    
    override suspend fun saveVolume(value: Float) {
        volume.value = value
    }
    
    override fun getSampleRate(): Flow<Int> = sampleRate.asStateFlow()
    
    override suspend fun saveSampleRate(rate: Int) {
        sampleRate.value = rate
    }
    
    override fun getBufferGenerationMinutes(): Flow<Int> = bufferGenerationMinutes.asStateFlow()
    
    override suspend fun saveBufferGenerationMinutes(minutes: Int) {
        bufferGenerationMinutes.value = minutes
    }
    
    override fun getChannelSwapSettings(): Flow<ChannelSwapSettings> = 
        channelSwapSettings.asStateFlow()
    
    override suspend fun saveChannelSwapSettings(settings: ChannelSwapSettings) {
        channelSwapSettings.value = settings
    }
    
    override fun getVolumeNormalizationSettings(): Flow<VolumeNormalizationSettings> = 
        volumeNormalizationSettings.asStateFlow()
    
    override suspend fun saveVolumeNormalizationSettings(settings: VolumeNormalizationSettings) {
        volumeNormalizationSettings.value = settings
    }
    
    override fun getResumeOnHeadsetConnect(): Flow<Boolean> = 
        resumeOnHeadsetConnect.asStateFlow()
    
    override suspend fun saveResumeOnHeadsetConnect(enabled: Boolean) {
        resumeOnHeadsetConnect.value = enabled
    }
    
    override fun getAutoResumeOnAppStart(): Flow<Boolean> = 
        autoResumeOnAppStart.asStateFlow()
    
    override suspend fun saveAutoResumeOnAppStart(enabled: Boolean) {
        autoResumeOnAppStart.value = enabled
    }
    
    override fun getAutoExpandGraphRange(): Flow<Boolean> = 
        autoExpandGraphRange.asStateFlow()
    
    override suspend fun saveAutoExpandGraphRange(enabled: Boolean) {
        autoExpandGraphRange.value = enabled
    }
    
    /**
     * Сбросить все настройки к значениям по умолчанию
     */
    fun reset() {
        volume.value = 0.7f
        sampleRate.value = 44100
        bufferGenerationMinutes.value = 10
        channelSwapSettings.value = ChannelSwapSettings()
        volumeNormalizationSettings.value = VolumeNormalizationSettings()
        resumeOnHeadsetConnect.value = false
        autoResumeOnAppStart.value = false
        autoExpandGraphRange.value = false
    }
}