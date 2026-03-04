package com.binaural.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.binaural.core.audio.model.FrequencyCurve
import com.binaural.core.audio.model.FrequencyPoint
import com.binaural.core.audio.model.FrequencyRange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Сериализуемая точка частоты для хранения
 */
@Serializable
data class SerializableFrequencyPoint(
    val hour: Int,
    val minute: Int,
    val carrierFrequency: Double,
    val beatFrequency: Double
)

/**
 * Сериализуемый диапазон частот
 */
@Serializable
data class SerializableFrequencyRange(
    val min: Double,
    val max: Double
)

/**
 * Сериализуемая кривая частоты для хранения
 */
@Serializable
data class SerializableFrequencyCurve(
    val points: List<SerializableFrequencyPoint>,
    val carrierRange: SerializableFrequencyRange? = null,
    val beatRange: SerializableFrequencyRange? = null
)

/**
 * Репозиторий для хранения настроек бинауральных ритмов
 */
@Singleton
class BinauralPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val FREQUENCY_CURVE_KEY = stringPreferencesKey("frequency_curve")
        private val VOLUME_KEY = stringPreferencesKey("volume")
        // Настройки перестановки каналов
        private val CHANNEL_SWAP_ENABLED_KEY = booleanPreferencesKey("channel_swap_enabled")
        private val CHANNEL_SWAP_INTERVAL_KEY = intPreferencesKey("channel_swap_interval")
        // Настройки нормализации громкости
        private val VOLUME_NORMALIZATION_ENABLED_KEY = booleanPreferencesKey("volume_normalization_enabled")
        private val VOLUME_NORMALIZATION_STRENGTH_KEY = floatPreferencesKey("volume_normalization_strength")
        // Частота дискретизации
        private val SAMPLE_RATE_KEY = intPreferencesKey("sample_rate")
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Получить кривую частот
     */
    fun getFrequencyCurve(): Flow<FrequencyCurve> {
        return dataStore.data.map { preferences ->
            preferences[FREQUENCY_CURVE_KEY]?.let { jsonString ->
                deserializeCurve(jsonString)
            } ?: FrequencyCurve.defaultCurve()
        }
    }

    /**
     * Сохранить кривую частот
     */
    suspend fun saveFrequencyCurve(curve: FrequencyCurve) {
        dataStore.edit { preferences ->
            preferences[FREQUENCY_CURVE_KEY] = serializeCurve(curve)
        }
    }

    private fun serializeCurve(curve: FrequencyCurve): String {
        val serializable = SerializableFrequencyCurve(
            points = curve.points.map { point ->
                SerializableFrequencyPoint(
                    hour = point.time.hour,
                    minute = point.time.minute,
                    carrierFrequency = point.carrierFrequency,
                    beatFrequency = point.beatFrequency
                )
            },
            carrierRange = SerializableFrequencyRange(curve.carrierRange.min, curve.carrierRange.max),
            beatRange = SerializableFrequencyRange(curve.beatRange.min, curve.beatRange.max)
        )
        return json.encodeToString(serializable)
    }

    private fun deserializeCurve(jsonString: String): FrequencyCurve {
        return try {
            val serializable = json.decodeFromString<SerializableFrequencyCurve>(jsonString)
            FrequencyCurve(
                points = serializable.points.map { point ->
                    FrequencyPoint(
                        time = LocalTime(point.hour, point.minute),
                        carrierFrequency = point.carrierFrequency,
                        beatFrequency = point.beatFrequency
                    )
                },
                carrierRange = serializable.carrierRange?.let { 
                    FrequencyRange(it.min, it.max) 
                } ?: FrequencyRange.DEFAULT_CARRIER,
                beatRange = serializable.beatRange?.let { 
                    FrequencyRange(it.min, it.max) 
                } ?: FrequencyRange.DEFAULT_BEAT
            )
        } catch (e: Exception) {
            FrequencyCurve.defaultCurve()
        }
    }
    
    // Методы для перестановки каналов
    
    fun getChannelSwapEnabled(): Flow<Boolean> {
        return dataStore.data.map { preferences ->
            preferences[CHANNEL_SWAP_ENABLED_KEY] ?: false
        }
    }
    
    suspend fun saveChannelSwapEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[CHANNEL_SWAP_ENABLED_KEY] = enabled
        }
    }
    
    fun getChannelSwapInterval(): Flow<Int> {
        return dataStore.data.map { preferences ->
            preferences[CHANNEL_SWAP_INTERVAL_KEY] ?: 300 // 5 минут по умолчанию
        }
    }
    
    suspend fun saveChannelSwapInterval(seconds: Int) {
        dataStore.edit { preferences ->
            preferences[CHANNEL_SWAP_INTERVAL_KEY] = seconds
        }
    }
    
    // Методы для нормализации громкости
    
    fun getVolumeNormalizationEnabled(): Flow<Boolean> {
        return dataStore.data.map { preferences ->
            preferences[VOLUME_NORMALIZATION_ENABLED_KEY] ?: false
        }
    }
    
    suspend fun saveVolumeNormalizationEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[VOLUME_NORMALIZATION_ENABLED_KEY] = enabled
        }
    }
    
    fun getVolumeNormalizationStrength(): Flow<Float> {
        return dataStore.data.map { preferences ->
            preferences[VOLUME_NORMALIZATION_STRENGTH_KEY] ?: 0.5f
        }
    }
    
    suspend fun saveVolumeNormalizationStrength(strength: Float) {
        dataStore.edit { preferences ->
            preferences[VOLUME_NORMALIZATION_STRENGTH_KEY] = strength
        }
    }
    
    // Методы для частоты дискретизации
    
    fun getSampleRate(): Flow<Int> {
        return dataStore.data.map { preferences ->
            preferences[SAMPLE_RATE_KEY] ?: 44100 // 44100 по умолчанию (MEDIUM)
        }
    }
    
    suspend fun saveSampleRate(rate: Int) {
        dataStore.edit { preferences ->
            preferences[SAMPLE_RATE_KEY] = rate
        }
    }
}
