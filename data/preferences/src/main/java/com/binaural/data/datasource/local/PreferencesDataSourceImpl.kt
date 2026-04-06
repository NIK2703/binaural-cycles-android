package com.binaural.data.datasource.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.binaural.core.domain.model.ChannelSwapSettings
import com.binaural.core.domain.model.NormalizationType
import com.binaural.core.domain.model.SwapMode
import com.binaural.core.domain.model.VolumeNormalizationSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Реализация PreferencesDataSource через DataStore.
 * Инкапсулирует работу с DataStore и ключами preferences.
 */
@Singleton
class PreferencesDataSourceImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : PreferencesDataSource {
    
    companion object {
        private val VOLUME_KEY = stringPreferencesKey("volume")
        private val SAMPLE_RATE_KEY = intPreferencesKey("sample_rate")
        private val BUFFER_GENERATION_MINUTES_KEY = intPreferencesKey("buffer_generation_minutes")
        
        // Настройки перестановки каналов
        private val CHANNEL_SWAP_ENABLED_KEY = booleanPreferencesKey("channel_swap_enabled")
        private val CHANNEL_SWAP_INTERVAL_KEY = intPreferencesKey("channel_swap_interval")
        private val CHANNEL_SWAP_FADE_DURATION_KEY = intPreferencesKey("channel_swap_fade_duration")
        private val CHANNEL_SWAP_PAUSE_DURATION_KEY = intPreferencesKey("channel_swap_pause_duration")
        private val CHANNEL_SWAP_MODE_KEY = stringPreferencesKey("channel_swap_mode")
        
        // Настройки нормализации громкости
        private val VOLUME_NORMALIZATION_TYPE_KEY = stringPreferencesKey("volume_normalization_type")
        private val VOLUME_NORMALIZATION_STRENGTH_KEY = floatPreferencesKey("volume_normalization_strength")
        
        // Автовозобновление
        private val RESUME_ON_HEADSET_CONNECT_KEY = booleanPreferencesKey("resume_on_headset_connect")
        private val AUTO_RESUME_ON_APP_START_KEY = booleanPreferencesKey("auto_resume_on_app_start")
        
        // График
        private val AUTO_EXPAND_GRAPH_RANGE_KEY = booleanPreferencesKey("auto_expand_graph_range")
    }
    
    // ========== Громкость ==========
    
    override fun getVolume(): Flow<Float> {
        return dataStore.data.map { preferences ->
            preferences[VOLUME_KEY]?.toFloatOrNull() ?: 0.7f
        }
    }
    
    override suspend fun saveVolume(volume: Float) {
        dataStore.edit { preferences ->
            preferences[VOLUME_KEY] = volume.toString()
        }
    }
    
    // ========== Частота дискретизации ==========
    
    override fun getSampleRate(): Flow<Int> {
        return dataStore.data.map { preferences ->
            preferences[SAMPLE_RATE_KEY] ?: 22050
        }
    }
    
    override suspend fun saveSampleRate(rate: Int) {
        dataStore.edit { preferences ->
            preferences[SAMPLE_RATE_KEY] = rate
        }
    }
    
    // ========== Интервал генерации буфера ==========
    
    override fun getBufferGenerationMinutes(): Flow<Int> {
        return dataStore.data.map { preferences ->
            preferences[BUFFER_GENERATION_MINUTES_KEY] ?: 10
        }
    }
    
    override suspend fun saveBufferGenerationMinutes(minutes: Int) {
        dataStore.edit { preferences ->
            preferences[BUFFER_GENERATION_MINUTES_KEY] = minutes.coerceIn(1, 60)
        }
    }
    
    // ========== Перестановка каналов ==========
    
    override fun getChannelSwapSettings(): Flow<ChannelSwapSettings> {
        return dataStore.data.map { preferences ->
            ChannelSwapSettings(
                enabled = preferences[CHANNEL_SWAP_ENABLED_KEY] ?: false,
                intervalSeconds = preferences[CHANNEL_SWAP_INTERVAL_KEY] ?: 60,
                fadeEnabled = true, // Всегда включено
                fadeDurationMs = preferences[CHANNEL_SWAP_FADE_DURATION_KEY]?.toLong() ?: 2000L,
                pauseDurationMs = preferences[CHANNEL_SWAP_PAUSE_DURATION_KEY]?.toLong() ?: 0L,
                swapMode = preferences[CHANNEL_SWAP_MODE_KEY]?.let {
                    try { SwapMode.valueOf(it) } catch (e: Exception) { SwapMode.INTERVAL }
                } ?: SwapMode.INTERVAL
            )
        }
    }
    
    override suspend fun saveChannelSwapSettings(settings: ChannelSwapSettings) {
        dataStore.edit { preferences ->
            preferences[CHANNEL_SWAP_ENABLED_KEY] = settings.enabled
            preferences[CHANNEL_SWAP_INTERVAL_KEY] = settings.intervalSeconds
            preferences[CHANNEL_SWAP_FADE_DURATION_KEY] = settings.fadeDurationMs.toInt()
            preferences[CHANNEL_SWAP_PAUSE_DURATION_KEY] = settings.pauseDurationMs.toInt()
            preferences[CHANNEL_SWAP_MODE_KEY] = settings.swapMode.name
        }
    }
    
    // ========== Нормализация громкости ==========
    
    override fun getVolumeNormalizationSettings(): Flow<VolumeNormalizationSettings> {
        return dataStore.data.map { preferences ->
            VolumeNormalizationSettings(
                type = preferences[VOLUME_NORMALIZATION_TYPE_KEY]?.let {
                    try { NormalizationType.valueOf(it) } catch (e: Exception) { NormalizationType.TEMPORAL }
                } ?: NormalizationType.TEMPORAL,
                strength = preferences[VOLUME_NORMALIZATION_STRENGTH_KEY] ?: 1.0f
            )
        }
    }
    
    override suspend fun saveVolumeNormalizationSettings(settings: VolumeNormalizationSettings) {
        dataStore.edit { preferences ->
            preferences[VOLUME_NORMALIZATION_TYPE_KEY] = settings.type.name
            preferences[VOLUME_NORMALIZATION_STRENGTH_KEY] = settings.strength
        }
    }
    
    // ========== Автовозобновление ==========
    
    override fun getResumeOnHeadsetConnect(): Flow<Boolean> {
        return dataStore.data.map { preferences ->
            preferences[RESUME_ON_HEADSET_CONNECT_KEY] ?: false
        }
    }
    
    override suspend fun saveResumeOnHeadsetConnect(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[RESUME_ON_HEADSET_CONNECT_KEY] = enabled
        }
    }
    
    override fun getAutoResumeOnAppStart(): Flow<Boolean> {
        return dataStore.data.map { preferences ->
            preferences[AUTO_RESUME_ON_APP_START_KEY] ?: false
        }
    }
    
    override suspend fun saveAutoResumeOnAppStart(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[AUTO_RESUME_ON_APP_START_KEY] = enabled
        }
    }
    
    // ========== График ==========
    
    override fun getAutoExpandGraphRange(): Flow<Boolean> {
        return dataStore.data.map { preferences ->
            preferences[AUTO_EXPAND_GRAPH_RANGE_KEY] ?: false
        }
    }
    
    override suspend fun saveAutoExpandGraphRange(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[AUTO_EXPAND_GRAPH_RANGE_KEY] = enabled
        }
    }
}