package com.binaural.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.binaural.core.audio.model.BinauralPreset
import com.binaural.core.audio.model.FrequencyCurve
import com.binaural.core.audio.model.FrequencyPoint
import com.binaural.core.audio.model.FrequencyRange
import com.binaural.core.audio.model.InterpolationType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
    val beatRange: SerializableFrequencyRange? = null,
    val interpolationType: String? = null
)

/**
 * Сериализуемый пресет для хранения
 */
@Serializable
data class SerializablePreset(
    val id: String,
    val name: String,
    val curve: SerializableFrequencyCurve,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Сериализуемый список пресетов
 */
@Serializable
data class SerializablePresetList(
    val presets: List<SerializablePreset>,
    val activePresetId: String? = null
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
        private val CHANNEL_SWAP_FADE_ENABLED_KEY = booleanPreferencesKey("channel_swap_fade_enabled")
        private val CHANNEL_SWAP_FADE_DURATION_KEY = intPreferencesKey("channel_swap_fade_duration")
        // Настройки нормализации громкости
        private val VOLUME_NORMALIZATION_ENABLED_KEY = booleanPreferencesKey("volume_normalization_enabled")
        private val VOLUME_NORMALIZATION_STRENGTH_KEY = floatPreferencesKey("volume_normalization_strength")
        // Частота дискретизации
        private val SAMPLE_RATE_KEY = intPreferencesKey("sample_rate")
        // Интервал обновления частот (мс)
        private val FREQUENCY_UPDATE_INTERVAL_KEY = intPreferencesKey("frequency_update_interval")
        // Пресеты
        private val PRESETS_KEY = stringPreferencesKey("presets")
        private val ACTIVE_PRESET_ID_KEY = stringPreferencesKey("active_preset_id")
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
            beatRange = SerializableFrequencyRange(curve.beatRange.min, curve.beatRange.max),
            interpolationType = curve.interpolationType.name
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
                } ?: FrequencyRange.DEFAULT_BEAT,
                interpolationType = serializable.interpolationType?.let {
                    try { InterpolationType.valueOf(it) } catch (e: Exception) { InterpolationType.LINEAR }
                } ?: InterpolationType.LINEAR
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
    
    fun getChannelSwapFadeEnabled(): Flow<Boolean> {
        return dataStore.data.map { preferences ->
            preferences[CHANNEL_SWAP_FADE_ENABLED_KEY] ?: true // включено по умолчанию
        }
    }
    
    suspend fun saveChannelSwapFadeEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[CHANNEL_SWAP_FADE_ENABLED_KEY] = enabled
        }
    }
    
    fun getChannelSwapFadeDuration(): Flow<Int> {
        return dataStore.data.map { preferences ->
            preferences[CHANNEL_SWAP_FADE_DURATION_KEY] ?: 1000 // 1 секунда по умолчанию
        }
    }
    
    suspend fun saveChannelSwapFadeDuration(durationMs: Int) {
        dataStore.edit { preferences ->
            preferences[CHANNEL_SWAP_FADE_DURATION_KEY] = durationMs
        }
    }
    
    // Методы для нормализации громкости
    
    fun getVolumeNormalizationEnabled(): Flow<Boolean> {
        return dataStore.data.map { preferences ->
            preferences[VOLUME_NORMALIZATION_ENABLED_KEY] ?: true  // Включено по умолчанию
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
    
    // Методы для интервала обновления частот
    
    /**
     * Получить интервал обновления частот в миллисекундах
     * По умолчанию 100мс (баланс между плавностью и энергосбережением)
     */
    fun getFrequencyUpdateInterval(): Flow<Int> {
        return dataStore.data.map { preferences ->
            preferences[FREQUENCY_UPDATE_INTERVAL_KEY] ?: 100 // 100мс по умолчанию
        }
    }
    
    /**
     * Сохранить интервал обновления частот
     * @param intervalMs интервал в миллисекундах (100-5000)
     */
    suspend fun saveFrequencyUpdateInterval(intervalMs: Int) {
        dataStore.edit { preferences ->
            preferences[FREQUENCY_UPDATE_INTERVAL_KEY] = intervalMs.coerceIn(100, 5000)
        }
    }
    
    // Методы для работы с пресетами
    
    /**
     * Получить список всех пресетов
     */
    fun getPresets(): Flow<List<BinauralPreset>> {
        return dataStore.data.map { preferences ->
            preferences[PRESETS_KEY]?.let { jsonString ->
                deserializePresets(jsonString)
            } ?: BinauralPreset.defaultPresets()
        }
    }
    
    /**
     * Сохранить список пресетов
     */
    suspend fun savePresets(presets: List<BinauralPreset>) {
        dataStore.edit { preferences ->
            preferences[PRESETS_KEY] = serializePresets(presets)
        }
    }
    
    /**
     * Получить ID активного пресета
     */
    fun getActivePresetId(): Flow<String?> {
        return dataStore.data.map { preferences ->
            preferences[ACTIVE_PRESET_ID_KEY]
        }
    }
    
    /**
     * Сохранить ID активного пресета
     */
    suspend fun saveActivePresetId(id: String?) {
        dataStore.edit { preferences ->
            if (id != null) {
                preferences[ACTIVE_PRESET_ID_KEY] = id
            } else {
                preferences.remove(ACTIVE_PRESET_ID_KEY)
            }
        }
    }
    
    /**
     * Добавить новый пресет
     */
    suspend fun addPreset(preset: BinauralPreset) {
        val currentPresets = getPresets().map { it.toMutableList() }.first()
        currentPresets.add(preset)
        savePresets(currentPresets)
    }
    
    /**
     * Обновить пресет
     */
    suspend fun updatePreset(preset: BinauralPreset) {
        val currentPresets = getPresets().map { it.toMutableList() }.first()
        val index = currentPresets.indexOfFirst { it.id == preset.id }
        if (index >= 0) {
            currentPresets[index] = preset
            savePresets(currentPresets)
        }
    }
    
    /**
     * Удалить пресет
     */
    suspend fun deletePreset(presetId: String) {
        val currentPresets = getPresets().map { it.toMutableList() }.first()
        currentPresets.removeAll { it.id == presetId }
        savePresets(currentPresets)
    }
    
    private fun serializePresets(presets: List<BinauralPreset>): String {
        val serializable = presets.map { preset ->
            SerializablePreset(
                id = preset.id,
                name = preset.name,
                curve = SerializableFrequencyCurve(
                    points = preset.frequencyCurve.points.map { point ->
                        SerializableFrequencyPoint(
                            hour = point.time.hour,
                            minute = point.time.minute,
                            carrierFrequency = point.carrierFrequency,
                            beatFrequency = point.beatFrequency
                        )
                    },
                    carrierRange = SerializableFrequencyRange(
                        preset.frequencyCurve.carrierRange.min,
                        preset.frequencyCurve.carrierRange.max
                    ),
                    beatRange = SerializableFrequencyRange(
                        preset.frequencyCurve.beatRange.min,
                        preset.frequencyCurve.beatRange.max
                    ),
                    interpolationType = preset.frequencyCurve.interpolationType.name
                ),
                createdAt = preset.createdAt,
                updatedAt = preset.updatedAt
            )
        }
        return json.encodeToString(serializable)
    }
    
    private fun deserializePresets(jsonString: String): List<BinauralPreset> {
        return try {
            val serializableList = json.decodeFromString<List<SerializablePreset>>(jsonString)
            serializableList.map { serializable ->
                BinauralPreset(
                    id = serializable.id,
                    name = serializable.name,
                    frequencyCurve = FrequencyCurve(
                        points = serializable.curve.points.map { point ->
                            FrequencyPoint(
                                time = LocalTime(point.hour, point.minute),
                                carrierFrequency = point.carrierFrequency,
                                beatFrequency = point.beatFrequency
                            )
                        },
                        carrierRange = serializable.curve.carrierRange?.let {
                            FrequencyRange(it.min, it.max)
                        } ?: FrequencyRange.DEFAULT_CARRIER,
                        beatRange = serializable.curve.beatRange?.let {
                            FrequencyRange(it.min, it.max)
                        } ?: FrequencyRange.DEFAULT_BEAT,
                        interpolationType = serializable.curve.interpolationType?.let {
                            try { InterpolationType.valueOf(it) } catch (e: Exception) { InterpolationType.LINEAR }
                        } ?: InterpolationType.LINEAR
                    ),
                    createdAt = serializable.createdAt,
                    updatedAt = serializable.updatedAt
                )
            }
        } catch (e: Exception) {
            BinauralPreset.defaultPresets()
        }
    }
}
